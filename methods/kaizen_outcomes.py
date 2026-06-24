"""kaizen_outcomes.py — R3: collect real PR outcomes for the Wave-4 feedback loop.

ADR-2606101200 §R3. kaizen_feedback.py learns from an outcomes NDJSON that was, until now,
operator-hand-written. This module fills it from the real source of truth: the GitHub PR
state of each proposal's PR, read via the operator's own `gh` CLI session.

Operator-principal, same boundary as member_submit:
  - runs with the OPERATOR's own `gh` auth (the platform holds no token — `gh` resolves
    the operator's keychain credential at invocation time);
  - REFUSES cron contexts (`IBUKI_CRON=1`): a platform job may never wield an operator
    credential (ADR-2605231525 discipline);
  - read-only: only `gh pr view --json state` — this module merges nothing, closes
    nothing, comments nothing. The humans stay the decision-makers (ADR-2605240200);
    this just tells the colony what the humans decided.

Mapping (closed): MERGED → merged · CLOSED → rejected · OPEN → pending.
The runner is injectable for hermetic tests. Stdlib only.
"""

from __future__ import annotations

import json
import os
import pathlib
import subprocess

from kaizen_feedback import OUTCOMES, read_proposals

ENV_CRON = "IBUKI_CRON"

STATE_TO_OUTCOME = {"MERGED": "merged", "CLOSED": "rejected", "OPEN": "pending"}


class OperatorContextRequired(RuntimeError):
    """Raised when outcome collection is attempted from a scheduled platform context."""


def refuse_if_cron() -> None:
    if os.environ.get(ENV_CRON) == "1":
        raise OperatorContextRequired(
            "refusing to query gh from a cron/scheduled context (IBUKI_CRON=1) — the "
            "operator's gh credential belongs to the operator's own runtime, never a "
            "platform job (ADR-2605231525)")


def _default_runner(pr: int) -> str:
    """`gh pr view <n> --json state` with the OPERATOR's own gh auth. Read-only."""
    out = subprocess.run(["gh", "pr", "view", str(pr), "--json", "state"],
                         capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError(f"gh pr view {pr} failed: {out.stderr.strip()}")
    return json.loads(out.stdout)["state"]


def collect(proposals_path: pathlib.Path, *, runner=None) -> list[dict]:
    """Resolve every proposal that names a PR (`pr` field, set by the PR agent) to an
    outcome record. Proposals without a PR yet are skipped (nothing to learn from).
    An unknown gh state raises — closed vocab, never guessed."""
    refuse_if_cron()
    run = runner or _default_runner
    out: list[dict] = []
    for p in read_proposals(proposals_path):
        pr = p.get("pr")
        if not pr:
            continue
        state = run(int(pr))
        if state not in STATE_TO_OUTCOME:
            raise ValueError(f"unknown PR state {state!r} for #{pr} "
                             f"(closed vocab: {sorted(STATE_TO_OUTCOME)})")
        outcome = STATE_TO_OUTCOME[state]
        assert outcome in OUTCOMES
        out.append({"proposalId": p["proposalId"], "rule": p["rule"],
                    "pr": int(pr), "outcome": outcome})
    return out


def write_outcomes(outcomes: list[dict], path: pathlib.Path) -> int:
    """Write the outcomes NDJSON kaizen_feedback.read_outcomes consumes (overwrite — the
    file is a SNAPSHOT of current PR states; the as-of history lives on the Datom log via
    kaizen_feedback.feedback_datoms, not in this file)."""
    p = pathlib.Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text("".join(json.dumps(o, ensure_ascii=False, sort_keys=True) + "\n"
                         for o in outcomes), encoding="utf-8")
    return len(outcomes)


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(
        description="ibuki kaizen outcome collector — operator-principal, read-only gh")
    ap.add_argument("--proposals", type=pathlib.Path, required=True)
    ap.add_argument("--outcomes", type=pathlib.Path, required=True)
    args = ap.parse_args()
    res = collect(args.proposals)
    n = write_outcomes(res, args.outcomes)
    print(f"# ibuki kaizen-outcomes — {n} proposal outcomes collected → {args.outcomes}")
