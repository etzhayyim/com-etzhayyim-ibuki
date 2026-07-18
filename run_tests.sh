#!/usr/bin/env bash
# ibuki — standalone test entrypoint.
set -euo pipefail
cd "$(dirname "$0")"
if [[ -e ibuki || -L ibuki ]]; then
  echo "refusing to replace existing ./ibuki" >&2
  exit 2
fi
ln -s "$PWD" ibuki
trap 'unlink ibuki' EXIT
export IBUKI_UNSPSC_REGISTRY_PATH="${IBUKI_UNSPSC_REGISTRY_PATH:-$PWD/wire/actor-registry/unspsc.json}"
bb run_tests.clj
