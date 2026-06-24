#!/usr/bin/env bash
# 息吹 (ibuki) — run the whole test suite with one command.
# Tests are standalone-runnable (no pytest needed); each prints its own count and exits
# non-zero on failure. This aggregates them and reports a grand total.
set -uo pipefail
cd "$(dirname "$0")"

SUITES=(
  "methods/test_datoms.py"
  "methods/test_joucho.py"
  "methods/test_heartbeat.py"
  "methods/test_infer.py"
  "methods/test_drainer.py"
  "methods/test_kaizen_feedback.py"
  "methods/test_autorun.py"
  "methods/test_fleet.py"
  "methods/test_perception.py"
  "methods/test_member_submit.py"
  "methods/test_kotoba_bridge.py"
  "methods/test_delegation.py"
  "methods/test_kaizen_outcomes.py"
  "methods/test_health.py"
  "methods/test_symbiosis.py"
  "methods/test_quorum.py"
  "methods/test_digest.py"
  "methods/test_integration.py"
  "methods/test_sick_colony.py"
  "methods/test_ecosystem.py"
  "methods/test_charter_invariants.py"
)

fail=0
for s in "${SUITES[@]}"; do
  dir="$(dirname "$s")"; file="$(basename "$s")"
  if ( cd "$dir" && python3 "$file" ); then :; else
    echo "FAILED: $s"; fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "── ibuki: ALL suites green ──"
else
  echo "── ibuki: FAILURES above ──"; exit 1
fi
