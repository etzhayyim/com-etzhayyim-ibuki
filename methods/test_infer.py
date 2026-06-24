"""test_infer.py — 息吹 (ibuki) Murakumo-only narration. ADR-2606101200 + ADR-2605215000."""
from __future__ import annotations

import os

import infer
from _t import expect_raises, run


def test_allowlist_is_murakumo_fleet_only():
    for host in infer.MURAKUMO_ALLOWED_HOSTS:
        name = host.split(":")[0]
        assert name in ("127.0.0.1", "localhost", "192.168.1.70")   # loopback + EVO-X2 LAN


def test_commercial_endpoints_unrepresentable():
    for bad in ("https://api.openai.com/v1/chat/completions",
                "https://api.anthropic.com/v1/messages",
                "https://bedrock-runtime.us-east-1.amazonaws.com/model/x/invoke",
                "https://api.runpod.ai/v2/x/run",
                "http://203.0.113.7:4000/v1/chat/completions"):
        expect_raises(lambda b=bad: infer.assert_murakumo(b), contains="Murakumo")


def test_https_to_allowed_host_still_refused():
    # only the documented http fleet endpoints — a lookalike scheme is not the fleet
    expect_raises(lambda: infer.assert_murakumo("https://127.0.0.1:4000/v1"),
                  contains="Murakumo")


def test_default_endpoint_is_litellm_loopback():
    assert infer.DEFAULT_ENDPOINT.startswith("http://127.0.0.1:4000")
    infer.assert_murakumo(infer.DEFAULT_ENDPOINT)


def test_template_deterministic():
    a = infer.template_narrate("Live cattle stewardship", "10101500", "calm", "recordAnalysis")
    b = infer.template_narrate("Live cattle stewardship", "10101500", "calm", "recordAnalysis")
    assert a == b and "mirror, not advice" in a


def test_narrate_offline_uses_template():
    os.environ.pop(infer.LIVE_ENV, None)
    out = infer.narrate("Cereal grains provisioning", "50221000", "neutral", "recordAnalysis")
    assert out["via"] == "template" and "50221000" in out["text"]


def test_narrate_refuses_bad_endpoint_even_when_live():
    os.environ[infer.LIVE_ENV] = "1"
    try:
        expect_raises(lambda: infer.narrate("t", "c", "calm", "recordAnalysis",
                                            endpoint="https://api.openai.com/v1/chat/completions"),
                      contains="Murakumo")
    finally:
        os.environ.pop(infer.LIVE_ENV, None)


def test_narrate_live_fails_open_to_template():
    """Live env set but no Murakumo gateway listening (port 4 is reserved/unbound):
    the organism must keep living offline (fail-open), not crash."""
    os.environ[infer.LIVE_ENV] = "1"
    try:
        out = infer.narrate("t", "c", "calm", "recordAnalysis",
                            endpoint="http://127.0.0.1:4000/v1/chat/completions",
                            timeout_s=0.2)
        assert out["via"] in ("template", "murakumo")   # murakumo only if a real fleet is up
        assert out["text"]
    finally:
        os.environ.pop(infer.LIVE_ENV, None)


if __name__ == "__main__":
    run("infer", [(n, f) for n, f in sorted(globals().items())
                  if n.startswith("test_") and callable(f)])
