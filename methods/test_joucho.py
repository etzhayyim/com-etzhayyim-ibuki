"""test_joucho.py — 息吹 (ibuki) evolving 5-axis mood. ADR-2606101200."""
from __future__ import annotations

import joucho
from _t import expect_raises, run


def test_default_scores_match_kotodama_stub():
    j = joucho.JouchoScores()
    assert (j.joy, j.calm, j.stress, j.gratitude, j.focus) == (50, 50, 30, 50, 50)


def test_personality_baseline_deterministic_and_distinct():
    a = joucho.personality_baseline("10101500")
    b = joucho.personality_baseline("10101500")
    c = joucho.personality_baseline("14111500")
    assert a == b
    assert a != c                                   # distinct temperament per organism
    for v in a.as_dict().values():
        assert 25 <= v <= 75


def test_determine_mood_stress_trumps():
    assert joucho.determine_mood(joucho.JouchoScores(joy=90, stress=70)) == "stressed"


def test_determine_mood_dominant_axis():
    assert joucho.determine_mood(joucho.JouchoScores(joy=65)) == "joyful"
    assert joucho.determine_mood(joucho.JouchoScores(gratitude=80, joy=61)) == "grateful"


def test_determine_mood_neutral_below_60():
    assert joucho.determine_mood(joucho.JouchoScores(joy=59, calm=59, stress=10,
                                                     gratitude=59, focus=59)) == "neutral"


def test_fold_event_unknown_kind_raises():
    base = joucho.JouchoScores()
    expect_raises(lambda: joucho.fold_event(base, ":event/made-up", base),
                  contains="closed vocab")


def test_fold_event_deltas_and_clamp():
    base = joucho.JouchoScores()
    j = joucho.fold_event(joucho.JouchoScores(joy=99), ":event/follower-gained", base)
    assert j.joy == 100 and j.gratitude == 52       # clamped at 100, +2 gratitude


def test_idle_drifts_toward_baseline():
    base = joucho.JouchoScores(joy=50, calm=50, stress=30, gratitude=50, focus=50)
    j = joucho.fold_event(joucho.JouchoScores(joy=60, stress=20), ":event/idle", base)
    assert j.joy == 59 and j.stress == 21           # homeostasis: 1 step toward baseline


def test_replay_events_is_the_as_of_query():
    base = joucho.personality_baseline("10101500")
    events = [":event/follower-gained"] * 10
    j = joucho.replay_events(base, events)
    assert j.joy >= base.joy and j.gratitude >= base.gratitude
    # replaying a PREFIX gives the earlier state — mood history is recoverable
    j5 = joucho.replay_events(base, events[:5])
    assert j5.joy <= j.joy


def test_mood_emerges_from_lived_history():
    """The Gap-4 closure: two organisms with the same baseline-shape stub would have been
    permanently neutral; with event folds, history moves the mood."""
    base = joucho.JouchoScores()                    # the old constant stub: neutral forever
    assert joucho.determine_mood(base) == "neutral"
    lived = joucho.replay_events(base, [":event/follower-gained"] * 6)
    assert joucho.determine_mood(lived) in ("joyful", "grateful")   # personality emerged


def test_kaizen_events_move_calm_and_stress():
    base = joucho.JouchoScores()
    merged = joucho.fold_event(base, ":event/kaizen-merged", base)
    assert merged.calm == 53 and merged.stress == 27
    rejected = joucho.fold_event(base, ":event/kaizen-rejected", base)
    assert rejected.stress == 32 and rejected.focus == 51


def test_event_datoms_closed_vocab():
    expect_raises(lambda: joucho.event_datoms("c", [":event/nope"], beat=1, as_of=1),
                  contains="closed vocab")


def test_joucho_datoms_shape():
    j = joucho.JouchoScores()
    ds = joucho.joucho_datoms("10101500", j, "neutral", beat=2, as_of=2606100002)
    attrs = {d[2] for d in ds}
    assert {":joucho/of", ":joucho/mood", ":joucho/joy", ":joucho/beat"} <= attrs
    assert all(d[0] == ":db/add" for d in ds)
    assert all(d[1] == "joucho-10101500-2" for d in ds)   # new entity per beat (非終末論)


def test_post_cooldown_table_covers_all_moods():
    assert set(joucho.POST_COOLDOWN_MS) == set(joucho.MOODS)
    assert joucho.POST_ENABLED["stressed"] is False
    assert joucho.POST_COOLDOWN_MS["joyful"] < joucho.POST_COOLDOWN_MS["neutral"]


if __name__ == "__main__":
    run("joucho", [(n, f) for n, f in sorted(globals().items())
                   if n.startswith("test_") and callable(f)])
