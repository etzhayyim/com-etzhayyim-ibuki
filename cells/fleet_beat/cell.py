"""LangGraph Pregel wrapper for 息吹 (ibuki) fleet beat — R2 RUNNABLE.

Per the founder direction of 2026-06-10, the Council gate for code-level completion is
exercised as PR review+merge — so `.solve()` RUNS the fleet beat instead of raising. One
solve = `cycles` durable beats over this node's shard (env-resolved, exactly like the
kotodama fleet cell: UNISPSC_ORGANISM_SHARD_ALL / UNISPSC_ORGANISM_SHARD_INDEX /
ETZHAYYIM_NODE), each appended as one content-addressed tx to the local kotoba Datom log.

What stays structural regardless of merge (Tier-1, not gate-flippable here):
  - posting: the beat only PREPARES member-sign-ready envelopes (`serverHeldKey:false`);
    live submission is the member's own runtime (member_submit.py), which refuses cron
    contexts outright — this cell can never post;
  - inference: Murakumo-only (infer.py allowlist); offline → deterministic template;
  - perception: live observation only via IBUKI_PERCEPTION_LIVE=1 (read-only public
    XRPC, allowlisted); default = bounded representative pattern.
"""
from __future__ import annotations

import pathlib
import sys
from typing import Any

_METHODS = pathlib.Path(__file__).resolve().parents[2] / "methods"


class FleetBeatCell:
    def solve(self, input_state: dict[str, Any]) -> dict[str, Any]:
        if str(_METHODS) not in sys.path:
            sys.path.insert(0, str(_METHODS))
        import fleet
        res = fleet.fleet_autorun(
            int(input_state.get("cycles", 1)),
            shard_index=input_state.get("shard"),          # None → env resolution
            batch_size=int(input_state.get("batch", 256)),
            fresh=bool(input_state.get("fresh", False)),
            log_path=pathlib.Path(input_state["log_path"]) if "log_path" in input_state else None,
            queue_path=pathlib.Path(input_state["queue_path"]) if "queue_path" in input_state else None,
        )
        return {
            "shard": res["shard"],
            "beats": res["beats"],
            "organisms_alive": res["organisms_alive"],
            "shard_size": res["shard_size"],
            "cursor": res["cursor"],
            "head_cid": res["head"],
            "chain_ok": res["chain"]["ok"],
        }
