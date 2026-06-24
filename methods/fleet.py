"""fleet.py — R1: bind the 18,342-organism UNSPSC fleet to ibuki's durable checkpoints.

ADR-2606101200 §R1. The kotodama fleet cell (ADR-2605240000) ticks organisms from process
RAM — an LRU cache of 4,096 live organisms whose cooldowns/state die with the pod. ibuki R1
replaces that fragility with the Datom log: the WHOLE fleet's state (joucho events, heartbeat
checkpoints, sweep cursor) lives as-of on the append-only log, so

  - no LRU is needed for *correctness* (an organism's state is never "evicted" — it is
    replayed from the log when its slice comes around);
  - a beat covers a bounded BATCH of organisms (round-robin over the shard via a DURABLE
    `:fleet.shard/*` cursor) — tx sizes stay bounded at any fleet size;
  - killing the process mid-sweep loses nothing: the next beat resumes from the cursor the
    log remembers, and the resulting chain is byte-identical to an uninterrupted run.

Sharding mirrors the kotodama fleet cell's segment ranges (the registry partition is asserted
complete + disjoint in tests; per-shard counts live in the registry, not hard-coded here):

  shard -1  jacob     segments  0-99   (whole fleet)
  shard  0  joseph    segments 10-29
  shard  1  issachar  segments 30-44
  shard  2  dan       segments 45-60

The code universe is the committed monorepo registry `00-contracts/actor-registry/
unispsc.json` (18,342 agents: code / did / title / segment). Resolution order for the path:
`IBUKI_UNSPSC_REGISTRY_PATH` env → walk up from this file to the monorepo root. Shard index
resolution mirrors fleet_cell_main: `UNISPSC_ORGANISM_SHARD_ALL=1` → -1, else
`UNISPSC_ORGANISM_SHARD_INDEX`, else `ETZHAYYIM_NODE` (joseph/issachar/dan), else 0.

Stdlib only. Deterministic (logical beat time; no wall clock). Live deploy of the cron cell
stays G8-gated (`cells/fleet_beat/cell.py` raises).
"""

from __future__ import annotations

import json
import os
import pathlib

import datoms
import drainer
import ecosystem
import heartbeat
import joucho
import perception
from infer import narrate

REGISTRY_REL = pathlib.Path("00-contracts") / "actor-registry" / "unispsc.json"

# shard index → (name, segment-lo, segment-hi); ranges mirror kotodama fleet_cell_main
SHARDS: dict[int, tuple[str, int, int]] = {
    -1: ("jacob", 0, 99),
    0: ("joseph", 10, 29),
    1: ("issachar", 30, 44),
    2: ("dan", 45, 60),
}

_NODE_TO_SHARD = {"joseph": 0, "issachar": 1, "dan": 2}

BEAT_MS = 45 * 60_000
AS_OF_BASE = 2606100000
DEFAULT_BATCH = 256
HEALTH_EVERY = 10       # colony self-audit cadence (health.py → :health/* checkpoints)


def resolve_registry_path() -> pathlib.Path:
    """Locate the monorepo UNSPSC actor registry (env override → walk up to repo root)."""
    env = os.environ.get("IBUKI_UNSPSC_REGISTRY_PATH")
    if env:
        return pathlib.Path(env)
    here = pathlib.Path(__file__).resolve()
    for parent in here.parents:
        cand = parent / REGISTRY_REL
        if cand.exists():
            return cand
    raise FileNotFoundError(f"UNSPSC registry not found above {here} (set "
                            f"IBUKI_UNSPSC_REGISTRY_PATH)")


def load_registry(path: pathlib.Path | None = None) -> list[dict]:
    """The fleet's code universe: [{code, did, title, segment}] in registry order."""
    p = path or resolve_registry_path()
    doc = json.loads(pathlib.Path(p).read_text(encoding="utf-8"))
    agents = doc["agents"]
    out = []
    for a in agents:
        out.append({"code": a["code"], "did": a["did"], "title": a["title"],
                    "segment": int(a["segment"])})
    return out


def resolve_shard() -> int:
    """Shard index from the environment — same resolution order as fleet_cell_main."""
    if os.environ.get("UNISPSC_ORGANISM_SHARD_ALL") == "1":
        return -1
    idx = os.environ.get("UNISPSC_ORGANISM_SHARD_INDEX")
    if idx is not None:
        return int(idx)
    node = os.environ.get("ETZHAYYIM_NODE", "")
    if node in _NODE_TO_SHARD:
        return _NODE_TO_SHARD[node]
    return 0


def shard_agents(agents: list[dict], shard_index: int) -> list[dict]:
    """The shard's slice of the fleet (registry order preserved — the sweep order)."""
    if shard_index not in SHARDS:
        raise ValueError(f"unknown shard index {shard_index} (known: {sorted(SHARDS)})")
    _, lo, hi = SHARDS[shard_index]
    return [a for a in agents if lo <= a["segment"] <= hi]


# ── single-pass log index (fleet-scale replay) ────────────────────────────


class LogIndex:
    """One pass over the log → per-organism state. fold_entity/events_for are O(log) per
    entity — fine for 3 organisms, quadratic poison for 18,342. This index recovers the
    same facts in a single scan."""

    def __init__(self) -> None:
        self.events: dict[str, list[str]] = {}
        self.hb: dict[str, heartbeat.HeartbeatState] = {}
        self._hb_beat: dict[str, int] = {}
        self.cursor: dict[str, int] = {}        # shard name → next sweep offset
        self.drain_line: dict[str, int] = {}    # shard name → queue lines already drained
        self.followers: dict[str, int] = {}     # code → last live :perception/followers
        self._perc_beat: dict[str, int] = {}
        self.last_fed: dict[str, int] = {}       # code → last :event/symbiosis-fed beat (satiation)


def index_log(txs: list[dict]) -> LogIndex:
    """Build the fleet index in one scan (datom order = tx order, so 'latest wins' and
    event streams come out in lived order, exactly like the per-entity folds)."""
    idx = LogIndex()
    ev_entity: dict[str, dict] = {}
    hb_entity: dict[str, dict] = {}
    perc_entity: dict[str, dict] = {}
    ev_order: list[str] = []
    for tx in txs:
        for _op, e, a, v in tx.get(":tx/datoms", []):
            if a.startswith(":joucho.event/"):
                if e not in ev_entity:
                    ev_entity[e] = {}
                    ev_order.append(e)
                ev_entity[e][a] = v
            elif a.startswith(":heartbeat/"):
                hb_entity.setdefault(e, {})[a] = v
            elif a.startswith(":perception/"):
                perc_entity.setdefault(e, {})[a] = v
            elif a == ":fleet.shard/cursor":
                idx.cursor[e.removeprefix("fleet-")] = v
            elif a == ":fleet.shard/drain-line":
                idx.drain_line[e.removeprefix("fleet-")] = v
    for e in ev_order:
        ent = ev_entity[e]
        of, kind = ent.get(":joucho.event/of"), ent.get(":joucho.event/kind")
        if of and kind:
            idx.events.setdefault(of, []).append(kind)
            if kind == ecosystem.SYMBIOSIS_EVENT:
                b = ent.get(":joucho.event/beat", -1)
                idx.last_fed[of] = max(idx.last_fed.get(of, -1), b)
    for ent in hb_entity.values():
        of, beat = ent.get(":heartbeat/of"), ent.get(":heartbeat/beat", -1)
        if of is None:
            continue
        if of not in idx.hb or beat > idx._hb_beat[of]:
            idx.hb[of] = heartbeat.HeartbeatState(
                last_post_at_ms=ent.get(":heartbeat/last-post-at-ms", -1),
                beats=ent.get(":heartbeat/beats", 0),
                posts=ent.get(":heartbeat/posts", 0))
            idx._hb_beat[of] = beat
    for ent in perc_entity.values():
        of, beat = ent.get(":perception/of"), ent.get(":perception/beat", -1)
        if of is None or ":perception/followers" not in ent:
            continue
        if of not in idx.followers or beat > idx._perc_beat.get(of, -1):
            idx.followers[of] = ent[":perception/followers"]
            idx._perc_beat[of] = beat
    return idx


# ── the fleet beat ────────────────────────────────────────────────────────


def fleet_beat(shard_slice: list[dict], idx: LogIndex, *, shard_name: str, beat: int,
               batch_size: int, queue_path: pathlib.Path, txs: list[dict]) -> list[list]:
    """One beat = the next `batch_size` organisms of the shard (durable round-robin cursor)
    + an incremental drain of queue lines this shard has not yet prepared. `txs` is the log
    read this beat (for the periodic health audit)."""
    now_ms = beat * BEAT_MS
    as_of = AS_OF_BASE + beat
    out: list[list] = []
    n = len(shard_slice)
    start = idx.cursor.get(shard_name, 0) % n if n else 0

    # ── Phase 1: feel + decide + act for the batch; defer checkpoints for the eco cascade ──
    batch = [shard_slice[(start + k) % n] for k in range(min(batch_size, n))]
    pending: list[dict] = []
    beat_moods: dict[str, joucho.JouchoScores] = {}
    for org in batch:
        code, did, title = org["code"], org["did"], org["title"]
        state = idx.hb.get(code, heartbeat.HeartbeatState())
        if state.beats == 0:    # first tick of this organism: birth assertion
            e = f"org-{code}"
            out += [datoms.add(e, ":organism/code", code),
                    datoms.add(e, ":organism/title", title),
                    datoms.add(e, ":organism/did", did),
                    datoms.add(e, ":organism/niche", ecosystem.niche_of(code)),
                    datoms.add(e, ":organism/born-beat", beat)]

        baseline = joucho.personality_baseline(code)
        events, snap = perception.events_for_beat(beat, actor=did,
                                                  prev_followers=idx.followers.get(code))
        if snap is not None:    # live observation: persist the durable follower snapshot
            out += perception.perception_datoms(code, snap["followers"],
                                                beat=beat, as_of=as_of)
            idx.followers[code] = snap["followers"]
        scores = joucho.replay_events(baseline, idx.events.get(code, []) + events)
        mood = joucho.determine_mood(scores)

        due, _reason = heartbeat.due_to_post(state, mood, now_ms)
        if due:
            nar = narrate(title, code, mood, "recordAnalysis")
            pid = f"post-{code}-{beat}"
            out += [datoms.add(pid, ":post/of", code),
                    datoms.add(pid, ":post/text", nar["text"]),
                    datoms.add(pid, ":post/via", f":{nar['via']}"),
                    datoms.add(pid, ":post/mood", f":{mood}"),
                    datoms.add(pid, ":post/beat", beat),
                    datoms.add(pid, ":post/as-of", as_of),
                    datoms.add(pid, ":post/status", ":dry-run")]
            queue_line(queue_path, did, code, title, mood, nar["text"], now_ms)
            scores = joucho.fold_event(scores, ":event/post-emitted", baseline)
            events = events + [":event/post-emitted"]
            state.last_post_at_ms = now_ms
            state.posts += 1
        state.beats += 1
        beat_moods[code] = scores
        pending.append({"code": code, "baseline": baseline, "events": events,
                        "scores": scores, "state": state})

    # ── Phase 2: 生態系 cascade over the co-active batch (niches hash-derived at fleet
    # scale; satiation from the durable last-fed index) ──
    sated = {c for c in beat_moods if beat - idx.last_fed.get(c, -ecosystem.SATIATION - 1)
             <= ecosystem.SATIATION}
    eco = ecosystem.cycle([{"code": o["code"]} for o in batch], beat_moods,
                          beat=beat, as_of=as_of, satiated=sated,
                          trails=ecosystem.trail_strengths(txs, beat))
    out += eco["datoms"]
    fed_count: dict[str, int] = {}
    for prod in eco["fed"]:
        fed_count[prod] = fed_count.get(prod, 0) + 1

    # ── Phase 3: fold symbiosis into the SAME-beat checkpoint; ONE event_datoms per organism ──
    final_moods: dict[str, str] = {}
    for p in pending:
        code, events, scores, state = p["code"], p["events"], p["scores"], p["state"]
        for _ in range(fed_count.get(code, 0)):
            events = events + [ecosystem.SYMBIOSIS_EVENT]
            scores = joucho.fold_event(scores, ecosystem.SYMBIOSIS_EVENT, p["baseline"])
        if fed_count.get(code, 0):
            idx.last_fed[code] = beat
        mood = joucho.determine_mood(scores)
        final_moods[code] = mood
        out += joucho.event_datoms(code, events, beat=beat, as_of=as_of)
        out += joucho.joucho_datoms(code, scores, mood, beat=beat, as_of=as_of)
        out += heartbeat.checkpoint_datoms(code, state, mood, beat=beat, as_of=as_of)
        idx.hb[code] = state
        idx.events.setdefault(code, []).extend(events)

    # 定足数 quorum sensing over the co-active batch — collective fruiting / dormancy
    import quorum
    beat_commons = sum(d[3] for d in eco["datoms"]
                       if d[2] == ":metabolite/nutrient" and d[1].startswith(("eco-refined-",
                                                                              "eco-detritus-")))
    out += quorum.sense(final_moods, beat_commons, beat=beat, as_of=as_of)["datoms"]

    # incremental drain: only lines this shard has not yet prepared (durable line cursor)
    from_line = idx.drain_line.get(shard_name, 0)
    drained, next_line = drain_incremental(queue_path, from_line, as_of=as_of, beat=beat)
    out += drained
    idx.drain_line[shard_name] = next_line

    # 健全化: periodic colony self-audit over this shard's log (health.py) + the colony's
    # human-readable report (digest.py) — the deployed fleet path reports to humanity too,
    # not just the seed demo. Both read `txs` (the log BEFORE this beat), so no self-read.
    if beat % HEALTH_EVERY == 0:
        import health
        import digest
        out += health.health_datoms(health.audit(txs), beat=beat, as_of=as_of)
        out += digest.make(txs, beat=beat, as_of=as_of)["datoms"]

    # durable sweep cursor checkpoint — the crash-resume point of the round-robin
    new_cursor = (start + min(batch_size, n)) % n if n else 0
    idx.cursor[shard_name] = new_cursor
    e = f"fleet-{shard_name}"
    out += [datoms.add(e, ":fleet.shard/cursor", new_cursor),
            datoms.add(e, ":fleet.shard/drain-line", next_line),
            datoms.add(e, ":fleet.shard/beat", beat),
            datoms.add(e, ":fleet.shard/size", n),
            datoms.add(e, ":fleet.shard/as-of", as_of)]
    return out


def beat_events(beat: int) -> list[str]:
    """The offline stimulus pattern (kept for back-compat; the membrane itself lives in
    perception.py — live mode via IBUKI_PERCEPTION_LIVE=1)."""
    return perception.representative_events(beat)


def queue_line(queue_path: pathlib.Path, did: str, code: str, title: str, mood: str,
               text: str, ts_ms: int) -> None:
    """One ADR-2605240100 v=1 queue line (deterministic logical ts)."""
    queue_path.parent.mkdir(parents=True, exist_ok=True)
    line = {"v": drainer.SCHEMA_VERSION, "ts": ts_ms, "actorDid": did, "code": code,
            "title": title, "mood": mood, "contentSourceKind": "recordAnalysis",
            "text": text, "lexicon": "app.bsky.feed.post",
            "createdAt": f"2026-06-10T00:00:00.{ts_ms % 1000:03d}Z"}
    with queue_path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(line, ensure_ascii=False, sort_keys=True) + "\n")


def drain_incremental(queue_path: pathlib.Path, from_line: int, *, as_of: int,
                      beat: int) -> tuple[list[list], int]:
    """Drain only queue lines ≥ from_line into `:drain/*` `:prepared` datoms. Returns
    (datoms, next_line). The line cursor is durable (`:fleet.shard/drain-line`) so a
    restart never re-prepares what an earlier beat already prepared."""
    p = pathlib.Path(queue_path)
    if not p.exists():
        return [], from_line
    lines = p.read_text(encoding="utf-8").splitlines()
    out: list[list] = []
    prepared = 0
    for i in range(from_line, len(lines)):
        raw = lines[i].strip()
        if not raw:
            continue
        try:
            obj = json.loads(raw)
        except ValueError:
            continue
        if obj.get("v") != drainer.SCHEMA_VERSION:
            continue
        if any(k not in obj for k in drainer.REQUIRED_KEYS):
            continue
        env = drainer.envelope(obj)
        e = f"drain-{beat}-{prepared}"
        out += [datoms.add(e, ":drain/of", obj["actorDid"]),
                datoms.add(e, ":drain/lexicon", env["collection"]),
                datoms.add(e, ":drain/queue-ts", obj["ts"]),
                datoms.add(e, ":drain/status", ":prepared"),
                datoms.add(e, ":drain/requires-member-sig", True),
                datoms.add(e, ":drain/server-held-key", False),
                datoms.add(e, ":drain/beat", beat),
                datoms.add(e, ":drain/as-of", as_of)]
        prepared += 1
    return out, len(lines)


def fleet_autorun(cycles: int, *, shard_index: int | None = None,
                  batch_size: int = DEFAULT_BATCH, fresh: bool = False,
                  log_path: pathlib.Path | None = None,
                  queue_path: pathlib.Path | None = None,
                  registry_path: pathlib.Path | None = None) -> dict:
    """Run `cycles` fleet beats over one shard, one content-addressed tx per beat. Resumes
    sweep cursor + drain cursor + every organism's state from the log."""
    root = pathlib.Path(__file__).resolve().parents[1]
    log = log_path or (root / "data" / "ibuki-fleet.datoms.kotoba.edn")
    queue = queue_path or (root / "data" / "fleet-posts.queue.ndjson")
    if fresh:
        log.unlink(missing_ok=True)
        queue.unlink(missing_ok=True)
    shard = resolve_shard() if shard_index is None else shard_index
    shard_name = SHARDS[shard][0]
    slice_ = shard_agents(load_registry(registry_path), shard)
    for _ in range(cycles):
        txs = datoms.read_log(log)
        idx = index_log(txs)
        beat = len(txs) + 1
        body = fleet_beat(slice_, idx, shard_name=shard_name, beat=beat,
                          batch_size=batch_size, queue_path=queue, txs=txs)
        tx = datoms.make_tx(body, tx_id=beat, as_of=AS_OF_BASE + beat,
                            prev_cid=datoms.head_cid(log))
        datoms.append_tx(tx, log)
    chain = datoms.verify_chain(log)
    if not chain["ok"]:
        raise RuntimeError(f"kotoba Datom chain broken: {chain}")
    final = index_log(datoms.read_log(log))
    return {"beats": chain["length"], "head": datoms.head_cid(log), "chain": chain,
            "shard": shard_name, "shard_size": len(slice_),
            "organisms_alive": len(final.hb),
            "cursor": final.cursor.get(shard_name, 0)}


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(description="ibuki R1 fleet heartbeat (durable, sharded)")
    ap.add_argument("--cycles", type=int, default=3)
    ap.add_argument("--shard", type=int, default=None,
                    help="-1 jacob (all) / 0 joseph / 1 issachar / 2 dan; default = env")
    ap.add_argument("--batch", type=int, default=DEFAULT_BATCH)
    ap.add_argument("--fresh", action="store_true")
    args = ap.parse_args()
    res = fleet_autorun(args.cycles, shard_index=args.shard, batch_size=args.batch,
                        fresh=args.fresh)
    full = (f"{res['organisms_alive']}/{res['shard_size']}")
    print(f"# ibuki fleet — shard {res['shard']}: {res['beats']} beats, "
          f"{full} organisms alive on the log, cursor={res['cursor']}, "
          f"head={res['head'][:18]}…")
