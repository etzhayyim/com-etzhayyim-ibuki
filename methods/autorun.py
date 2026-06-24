"""autorun.py — 息吹: the autonomous organism heartbeat over the kotoba Datom log.

ADR-2606101200. The integrating loop that closes the organism autonomy survey gaps in one
beat cycle — shionome's autorun pattern (ADR-2606072200) applied to the organism layer:

  replay ─▶ perceive ─▶ feel ─▶ decide ─▶ narrate ─▶ act ─▶ checkpoint ─▶ append tx
  (log →    (this        (fold   (durable  (Murakumo-  (post   (joucho +    (content-
   durable   beat's       events  cooldown   only /      datom   heartbeat    addressed,
   state)    events)      → mood) due check) template)   :dry-run, NDJSON     verify_chain)
                                                          + queue) checkpoints)

Every beat is DURABLE: state is replayed from the append-only log, so killing the process
between beats loses nothing (Gap 1/2). Mood evolves from persisted events and is as-of
queryable (Gap 3/4). Narration goes through infer.py — Murakumo-only, template fallback
(Gap 5). Posts land as `:dry-run` datoms AND on the ADR-2605240100 NDJSON queue, which the
Wave-3 drainer turns into member-sign-ready envelopes (Gap 6). Kaizen outcomes, when present,
feed both rule suppression and the colony's mood (Gap 7).

Deterministic: logical time only (beat index × BEAT_MS); same seed + same cycle count → same
head CID. Live external I/O stays G8-gated — this loop's only side effects are the LOCAL log
+ the LOCAL queue file.

Usage:
  python3 autorun.py --cycles 3 [--fresh]
"""

from __future__ import annotations

import argparse
import pathlib

import datoms
import drainer
import ecosystem
import heartbeat
import joucho
import kaizen_feedback
from _edn import load_edn
from infer import narrate

ROOT = pathlib.Path(__file__).resolve().parents[1]
SEED = ROOT / "data" / "seed-organisms.kotoba.edn"
QUEUE = ROOT / "data" / "organism-posts.queue.ndjson"
PROPOSALS = ROOT / "data" / "kaizen-proposals.ndjson"
OUTCOMES = ROOT / "data" / "kaizen-outcomes.ndjson"

BEAT_MS = 45 * 60_000   # one beat = 45 logical minutes (crosses the joyful 30m cooldown,
                        # sits inside the calm/neutral 2h one — moods visibly change cadence)
AS_OF_BASE = 2606100000
HEALTH_EVERY = 10       # colony self-audit cadence (health.py → :health/* checkpoints)


def beat_events(beat: int) -> list[str]:
    """This beat's perceived events — the bounded :representative stimulus pattern
    (deterministic, no live I/O): every beat passes time, every 3rd beat a follower
    arrives, every 5th the inbox surges. The live perception membrane (R2) lives in
    perception.py and is wired into the fleet loop (fleet.py)."""
    import perception
    return perception.representative_events(beat)


def run_beat(organisms: list[dict], txs: list[dict], *, beat: int) -> list[list]:
    """One full heartbeat over every organism. Pure over (log so far, beat) → new datoms."""
    now_ms = beat * BEAT_MS
    as_of = AS_OF_BASE + beat
    out: list[list] = []

    # Kaizen feedback (Gap 7): outcomes fold into rule suppression + colony mood events.
    outcomes = kaizen_feedback.read_outcomes(OUTCOMES)
    proposals = kaizen_feedback.read_proposals(PROPOSALS)
    kz_events = kaizen_feedback.mood_events(outcomes) if beat == 1 else []
    if outcomes or proposals:
        stats = kaizen_feedback.fold(proposals, outcomes)
        table = kaizen_feedback.suppression(stats, now_beat=beat)
        out += kaizen_feedback.feedback_datoms(stats, table, beat=beat, as_of=as_of)

    # ── Phase 1: feel + decide + act (per organism) — gather state, defer checkpoints ──
    # Checkpoints are deferred until after the ecosystem cascade so a fed producer's
    # :event/symbiosis-fed lands in the SAME beat's event stream AND checkpoint
    # (checkpoint == as-of replay — the ecosystem layer adds no divergence).
    pending: list[dict] = []
    beat_moods: dict[str, joucho.JouchoScores] = {}
    for org in organisms:
        code = org[":organism/code"]
        title = org[":organism/title"]
        did = org[":organism/did"]

        if beat == 1:  # birth: assert the organism entity once
            e = f"org-{code}"
            out += [datoms.add(e, ":organism/code", code),
                    datoms.add(e, ":organism/title", title),
                    datoms.add(e, ":organism/did", did),
                    datoms.add(e, ":organism/niche",
                               ecosystem.niche_of(code, org.get(":organism/niche"))),
                    datoms.add(e, ":organism/born-beat", beat)]

        baseline = joucho.personality_baseline(code)
        history = datoms.events_for(txs, code)
        events = beat_events(beat) + kz_events
        scores = joucho.replay_events(baseline, history + events)
        mood = joucho.determine_mood(scores)

        state = heartbeat.replay(txs, code)
        due, reason = heartbeat.due_to_post(state, mood, now_ms)
        if due:
            n = narrate(title, code, mood, "recordAnalysis")
            pid = f"post-{code}-{beat}"
            out += [datoms.add(pid, ":post/of", code),
                    datoms.add(pid, ":post/text", n["text"]),
                    datoms.add(pid, ":post/via", f":{n['via']}"),
                    datoms.add(pid, ":post/mood", f":{mood}"),
                    datoms.add(pid, ":post/beat", beat),
                    datoms.add(pid, ":post/as-of", as_of),
                    datoms.add(pid, ":post/status", ":dry-run")]
            _queue_line(did, code, title, mood, n["text"], now_ms)
            scores = joucho.fold_event(scores, ":event/post-emitted", baseline)
            events = events + [":event/post-emitted"]
            state.last_post_at_ms = now_ms
            state.posts += 1
        state.beats += 1
        beat_moods[code] = scores
        pending.append({"code": code, "baseline": baseline, "events": events,
                        "scores": scores, "mood": mood, "state": state})

    # ── Phase 2: 生態系 trophic cascade — producers fix substrate, 粘菌 routers relay the
    # richest to カビ decomposers, which excrete a refined commons metabolite (the citric
    # acid offered to humanity). Returns the producers the web fed. ──
    eco = ecosystem.cycle([{"code": o[":organism/code"], "niche": o.get(":organism/niche")}
                           for o in organisms], beat_moods, beat=beat, as_of=as_of,
                          satiated=ecosystem.satiated_producers(txs, beat),
                          trails=ecosystem.trail_strengths(txs, beat))
    out += eco["datoms"]
    fed_count: dict[str, int] = {}
    for prod in eco["fed"]:
        fed_count[prod] = fed_count.get(prod, 0) + 1

    # ── Phase 3: fold symbiosis feeding into each fed producer's SAME-beat checkpoint,
    # then write exactly ONE event_datoms + joucho + heartbeat per organism ──
    final_moods: dict[str, str] = {}
    for p in pending:
        code, events, scores = p["code"], p["events"], p["scores"]
        for _ in range(fed_count.get(code, 0)):
            events = events + [ecosystem.SYMBIOSIS_EVENT]
            scores = joucho.fold_event(scores, ecosystem.SYMBIOSIS_EVENT, p["baseline"])
        mood = joucho.determine_mood(scores)   # final mood, post-symbiosis (checkpoint==replay)
        final_moods[code] = mood
        out += joucho.event_datoms(code, events, beat=beat, as_of=as_of)
        out += joucho.joucho_datoms(code, scores, mood, beat=beat, as_of=as_of)
        out += heartbeat.checkpoint_datoms(code, p["state"], mood, beat=beat, as_of=as_of)

    # ── 定足数 quorum sensing: a colony phenotype no cell could trigger alone. When ≥2/3 of
    # the colony is flourishing the colony FRUITS (a collective commons burst, deepening 共生);
    # ≥2/3 stressed = dormancy (recorded, observational). ──
    import quorum
    beat_commons = sum(d[3] for d in eco["datoms"]
                       if d[2] == ":metabolite/nutrient" and d[1].startswith(("eco-refined-",
                                                                              "eco-detritus-")))
    out += quorum.sense(final_moods, beat_commons, beat=beat, as_of=as_of)["datoms"]

    # drain (Gap 6): queue → member-sign-ready envelopes, checkpointed :prepared
    drained = drainer.drain(QUEUE, as_of=as_of, beat=beat)
    out += drained["datoms"]

    # 健全化: every HEALTH_EVERY beats the colony audits its own log and checkpoints the
    # verdict (`:health/*`) — Wellbecoming as as-of history, measured not assumed
    if beat % HEALTH_EVERY == 0:
        import health
        out += health.health_datoms(health.audit(txs), beat=beat, as_of=as_of)
        # the colony REASONS about its ecosystem + reports to humanity (Murakumo-only,
        # dry-run): a mirror of where its life became a gift (digest.py). Uses `txs` (the log
        # BEFORE this beat) so it never reads its own in-flight datoms.
        import digest
        out += digest.make(txs, beat=beat, as_of=as_of)["datoms"]
    return out


def _queue_line(did: str, code: str, title: str, mood: str, text: str, ts_ms: int) -> None:
    """Append one ADR-2605240100 v=1 line to the post queue (deterministic logical ts)."""
    import json
    QUEUE.parent.mkdir(parents=True, exist_ok=True)
    created = f"2026-06-10T00:00:00.{ts_ms % 1000:03d}Z"
    line = {"v": drainer.SCHEMA_VERSION, "ts": ts_ms, "actorDid": did, "code": code,
            "title": title, "mood": mood, "contentSourceKind": "recordAnalysis",
            "text": text, "lexicon": "app.bsky.feed.post", "createdAt": created}
    with QUEUE.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(line, ensure_ascii=False, sort_keys=True) + "\n")


def autorun(cycles: int, *, fresh: bool = False,
            log_path: pathlib.Path = datoms.LOG_DEFAULT,
            queue_path: pathlib.Path | None = None) -> dict:
    """Run `cycles` heartbeats, each appended as one content-addressed transaction. Resumes
    from whatever the log already holds (crash-resume is just... running again)."""
    global QUEUE
    if queue_path is not None:
        QUEUE = queue_path
    if fresh:
        log_path.unlink(missing_ok=True)
        QUEUE.unlink(missing_ok=True)
    organisms = load_edn(SEED)[":seed/organisms"]
    for _ in range(cycles):
        txs = datoms.read_log(log_path)
        beat = len(txs) + 1
        body = run_beat(organisms, txs, beat=beat)
        tx = datoms.make_tx(body, tx_id=beat, as_of=AS_OF_BASE + beat,
                            prev_cid=datoms.head_cid(log_path))
        datoms.append_tx(tx, log_path)
    chain = datoms.verify_chain(log_path)
    if not chain["ok"]:
        raise RuntimeError(f"kotoba Datom chain broken: {chain}")
    return {"beats": chain["length"], "head": datoms.head_cid(log_path), "chain": chain}


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="ibuki autonomous organism heartbeat")
    ap.add_argument("--cycles", type=int, default=3)
    ap.add_argument("--fresh", action="store_true")
    args = ap.parse_args()
    res = autorun(args.cycles, fresh=args.fresh)
    txs = datoms.read_log()
    organisms = load_edn(SEED)[":seed/organisms"]
    print(f"# ibuki — {res['beats']} beats on the kotoba Datom log, head={res['head'][:18]}…")
    for org in organisms:
        code = org[":organism/code"]
        base = joucho.personality_baseline(code)
        for at in (1, res["beats"]):
            ev = datoms.events_for(txs, code, up_to_tx=at)
            mood = joucho.determine_mood(joucho.replay_events(base, ev))
            print(f"  {org[':organism/title']} mood as-of tx {at}: {mood} ({len(ev)} events)")
