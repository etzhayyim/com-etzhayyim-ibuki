(ns ibuki.cells.fleet-beat.cell
  "LangGraph Pregel wrapper for 息吹 (ibuki) fleet beat — R2 RUNNABLE.
  1:1 port of cells/fleet_beat/cell.py.

  Per the founder direction of 2026-06-10, the Council gate for code-level completion is
  exercised as PR review+merge — so .solve() RUNS the fleet beat instead of raising. One
  solve = `cycles` durable beats over this node's shard (env-resolved, exactly like the
  kotodama fleet cell: UNSPSC_ORGANISM_SHARD_ALL / UNSPSC_ORGANISM_SHARD_INDEX /
  ETZHAYYIM_NODE), each appended as one content-addressed tx to the local kotoba Datom log.

  What stays structural regardless of merge (Tier-1, not gate-flippable here):
    - posting: the beat only PREPARES member-sign-ready envelopes (serverHeldKey:false);
      live submission is the member's own runtime (member_submit), which refuses cron
      contexts outright — this cell can never post;
    - inference: Murakumo-only (infer allowlist); offline → deterministic template;
    - perception: live observation only via IBUKI_PERCEPTION_LIVE=1 (read-only public
      XRPC, allowlisted); default = bounded representative pattern."
  (:require [ibuki.methods.fleet :as fleet]))

(defn solve
  "Run `cycles` durable fleet beats over this node's shard. Mirrors FleetBeatCell.solve:
  cycles/shard/batch/fresh/log_path/queue_path are read from input-state (string keys, as
  the Python dict), shard nil → env resolution. Returns the beat summary."
  [input-state]
  (let [res (fleet/fleet-autorun
             (int (get input-state "cycles" 1))
             {:shard-index (get input-state "shard")          ; nil → env resolution
              :batch-size  (int (get input-state "batch" 256))
              :fresh       (boolean (get input-state "fresh" false))
              :log-path    (get input-state "log_path")
              :queue-path  (get input-state "queue_path")})]
    {"shard"            (:shard res)
     "beats"           (:beats res)
     "organisms_alive" (:organisms-alive res)
     "shard_size"      (:shard-size res)
     "cursor"          (:cursor res)
     "head_cid"        (:head res)
     "chain_ok"        (get-in res [:chain :ok])}))
