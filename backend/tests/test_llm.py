import json

import httpx
import pytest
import respx

from app.llm import LLM, ChatResult, FakeLLM, LLMUnavailable, ToolCall

BASE = "https://tf.test/v1/"
URL = BASE + "chat/completions"


def make() -> LLM:
    return LLM(base_url=BASE, api_key="k", model="m", embed_model="e", vision_model="v")


def completion(message: dict) -> dict:
    return {
        "id": "x", "object": "chat.completion", "created": 0, "model": "m",
        "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", **message}}],
    }


def tc(args: str) -> dict:
    return {"id": "c1", "type": "function", "function": {"name": "lookup", "arguments": args}}


@respx.mock
def test_success_content():
    respx.post(URL).mock(return_value=httpx.Response(200, json=completion({"content": "hi"})))
    r = make().chat([{"role": "user", "content": "yo"}])
    assert r == ChatResult(content="hi", tool_calls=[])


@respx.mock
def test_tool_call_parsed():
    respx.post(URL).mock(return_value=httpx.Response(
        200, json=completion({"content": None, "tool_calls": [tc('{"q": "a"}')]})))
    r = make().chat([{"role": "user", "content": "yo"}], tools=[{"type": "function", "function": {"name": "lookup", "parameters": {}}}])
    assert r.tool_calls == [ToolCall(id="c1", name="lookup", arguments={"q": "a"})]


@respx.mock
def test_model_override_and_image_parts_passthrough():
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=completion({"content": "ok"})))
    parts = [{"type": "text", "text": "t"}, {"type": "image_url", "image_url": {"url": "data:image/png;base64,AA"}}]
    make().chat([{"role": "user", "content": parts}], model="vis")
    body = json.loads(route.calls[0].request.content)
    assert body["model"] == "vis"
    assert body["messages"][0]["content"] == parts


@respx.mock
def test_429_twice_raises():
    route = respx.post(URL).mock(return_value=httpx.Response(429, json={"error": {"message": "slow"}}))
    with pytest.raises(LLMUnavailable):
        make().chat([{"role": "user", "content": "x"}])
    assert route.call_count == 2


@respx.mock
def test_retry_once_then_success():
    respx.post(URL).mock(side_effect=[
        httpx.Response(503, json={"error": {"message": "x"}}),
        httpx.Response(200, json=completion({"content": "ok"})),
    ])
    assert make().chat([{"role": "user", "content": "x"}]).content == "ok"


@respx.mock
def test_timeout_raises():
    respx.post(URL).mock(side_effect=httpx.ConnectTimeout("t"))
    with pytest.raises(LLMUnavailable):
        make().chat([{"role": "user", "content": "x"}])


@respx.mock
def test_malformed_arguments_raise_llm_unavailable():
    respx.post(URL).mock(return_value=httpx.Response(
        200, json=completion({"content": None, "tool_calls": [tc("{not json")]})))
    with pytest.raises(LLMUnavailable):
        make().chat([{"role": "user", "content": "x"}])


@respx.mock
def test_non_object_arguments_raise():
    respx.post(URL).mock(return_value=httpx.Response(
        200, json=completion({"content": None, "tool_calls": [tc("[1]")]})))
    with pytest.raises(LLMUnavailable):
        make().chat([{"role": "user", "content": "x"}])


@respx.mock
def test_embed():
    route = respx.post(BASE + "embeddings").mock(return_value=httpx.Response(200, json={
        "object": "list", "model": "e", "usage": {"prompt_tokens": 1, "total_tokens": 1},
        "data": [{"object": "embedding", "index": 1, "embedding": [3.0]},
                 {"object": "embedding", "index": 0, "embedding": [1.0]}]}))
    assert make().embed(["a", "b"]) == [[1.0], [3.0]]
    assert json.loads(route.calls[0].request.content)["model"] == "e"


def test_fake_llm_scripted_and_records():
    f = FakeLLM([ChatResult("a", []), LLMUnavailable("boom")])
    assert f.chat([{"role": "user", "content": "1"}], model="z").content == "a"
    with pytest.raises(LLMUnavailable):
        f.chat([], tools=[{"x": 1}])
    assert len(f.calls) == 2 and f.calls[0]["model"] == "z" and f.calls[1]["tools"] == [{"x": 1}]
    with pytest.raises(AssertionError):
        f.chat([])


def test_fake_embed_deterministic():
    f = FakeLLM()
    a, b = f.embed(["x", "y"]), f.embed(["x"])
    assert a[0] == b[0] and a[0] != a[1] and len(a[0]) == 1024
