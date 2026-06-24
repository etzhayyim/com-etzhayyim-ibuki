"""test_datoms.py — 息吹 (ibuki) Datom log: chain + as-of fold. ADR-2606101200."""
from __future__ import annotations

import pathlib
import tempfile

import datoms
from _t import run


def _tx(body, tx_id, prev=""):
    return datoms.make_tx(body, tx_id=tx_id, as_of=2606100000 + tx_id, prev_cid=prev)


def test_datoms_are_append_only_adds():
    d = datoms.add("org-1", ":organism/code", "1")
    assert d[0] == ":db/add"            # no :db/retract exists in this module (非終末論)
    assert not hasattr(datoms, "retract")


def test_tx_cid_deterministic_and_prev_linked():
    body = [datoms.add("e", ":a/b", 1)]
    assert datoms.tx_cid(body, "") == datoms.tx_cid(body, "")
    assert datoms.tx_cid(body, "") != datoms.tx_cid(body, "bdeadbeef")


def test_append_read_verify_roundtrip():
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "log.edn"
        prev = ""
        for i in range(1, 4):
            prev = datoms.append_tx(_tx([datoms.add(f"e{i}", ":a/n", i)], i, prev), log)
        assert len(datoms.read_log(log)) == 3
        assert datoms.head_cid(log) == prev
        v = datoms.verify_chain(log)
        assert v["ok"] is True and v["length"] == 3


def test_tamper_breaks_chain():
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "log.edn"
        prev = ""
        for i in range(1, 3):
            prev = datoms.append_tx(_tx([datoms.add(f"e{i}", ":a/n", i)], i, prev), log)
        lines = log.read_text(encoding="utf-8").splitlines()
        lines[1] = lines[1].replace(":a/n 1", ":a/n 9")
        log.write_text("\n".join(lines) + "\n", encoding="utf-8")
        assert datoms.verify_chain(log)["ok"] is False


def test_fold_entity_latest_wins():
    txs = [_tx([datoms.add("e", ":a/x", 1)], 1),
           _tx([datoms.add("e", ":a/x", 2), datoms.add("e", ":a/y", "s")], 2)]
    assert datoms.fold_entity(txs, "e") == {":a/x": 2, ":a/y": "s"}


def test_fold_entity_as_of_cut():
    txs = [_tx([datoms.add("e", ":a/x", 1)], 1),
           _tx([datoms.add("e", ":a/x", 2)], 2)]
    assert datoms.fold_entity(txs, "e", up_to_tx=1)[":a/x"] == 1   # history preserved


def test_entities_by_attr():
    txs = [_tx([datoms.add("a", ":k/of", "x"), datoms.add("b", ":k/of", "y")], 1)]
    assert datoms.entities(txs, ":k/of") == ["a", "b"]


def test_events_for_ordered_and_as_of():
    txs = [_tx([datoms.add("jev-c-1-0", ":joucho.event/of", "c"),
                datoms.add("jev-c-1-0", ":joucho.event/kind", ":event/idle")], 1),
           _tx([datoms.add("jev-c-2-0", ":joucho.event/of", "c"),
                datoms.add("jev-c-2-0", ":joucho.event/kind", ":event/follower-gained"),
                datoms.add("jev-d-2-0", ":joucho.event/of", "d"),
                datoms.add("jev-d-2-0", ":joucho.event/kind", ":event/idle")], 2)]
    assert datoms.events_for(txs, "c") == [":event/idle", ":event/follower-gained"]
    assert datoms.events_for(txs, "c", up_to_tx=1) == [":event/idle"]
    assert datoms.events_for(txs, "d") == [":event/idle"]


def test_unicode_text_roundtrips():
    with tempfile.TemporaryDirectory() as dr:
        log = pathlib.Path(dr) / "log.edn"
        body = [datoms.add("p", ":post/text", "観測ノート\n\n日本語 with \"quotes\"")]
        datoms.append_tx(_tx(body, 1), log)
        assert datoms.verify_chain(log)["ok"] is True
        assert datoms.fold_entity(datoms.read_log(log), "p")[":post/text"].startswith("観測")


if __name__ == "__main__":
    run("datoms", [(n, f) for n, f in sorted(globals().items())
                   if n.startswith("test_") and callable(f)])
