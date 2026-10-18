"""Token Factory (Nebius, OpenAI-compatible) client with a single retry and a scripted fake."""
import hashlib
import json
from dataclasses import dataclass, field
from functools import lru_cache

import httpx
import openai

from app.config import settings


class LLMUnavailable(Exception):
    """LLM timed out, was rate-limited, errored (5xx) or returned unusable output."""


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: dict


@dataclass
class ChatResult:
    content: str | None
    tool_calls: list[ToolCall] = field(default_factory=list)


class LLM:
    def __init__(self, base_url: str, api_key: str, model: str, embed_model: str,
                 vision_model: str = "", embed_dim: int | None = None):
        self.model = model
        self.embed_model = embed_model
        self.vision_model = vision_model
        self.embed_dim = embed_dim
        self._client = openai.OpenAI(base_url=base_url, api_key=api_key or "unset", max_retries=0,
                                    http_client=httpx.Client(timeout=20))

    def _call(self, fn, **kwargs):
        last: Exception | None = None
        for _ in range(2):  # one retry
            try:
                return fn(**kwargs)
            except (openai.APITimeoutError, openai.APIConnectionError, openai.RateLimitError,
                    openai.InternalServerError) as e:
                last = e
            except openai.APIError as e:
                raise LLMUnavailable(str(e)) from e
        raise LLMUnavailable(str(last)) from last

    def chat(self, messages: list[dict], tools: list[dict] | None = None,
             timeout: float = 20, model: str | None = None) -> ChatResult:
        kwargs: dict = {"model": model or self.model, "messages": messages, "timeout": timeout}
        if tools:
            kwargs["tools"] = tools
        resp = self._call(self._client.chat.completions.create, **kwargs)
        if not resp.choices:
            raise LLMUnavailable("empty choices")
        msg = resp.choices[0].message
        calls: list[ToolCall] = []
        for t in msg.tool_calls or []:
            try:
                args = json.loads(t.function.arguments or "{}")
            except (json.JSONDecodeError, TypeError) as e:
                raise LLMUnavailable(f"malformed tool-call arguments: {e}") from e
            if not isinstance(args, dict):
                raise LLMUnavailable("tool-call arguments must be a JSON object")
            calls.append(ToolCall(id=t.id, name=t.function.name, arguments=args))
        return ChatResult(content=msg.content, tool_calls=calls)

    def embed(self, texts: list[str]) -> list[list[float]]:
        resp = self._call(self._client.embeddings.create, model=self.embed_model, input=texts)
        return [d.embedding for d in sorted(resp.data, key=lambda d: d.index)]


class FakeLLM:
    """Scripted stand-in: `script` is a list of ChatResult or Exception, consumed in order."""

    def __init__(self, script: list[ChatResult | Exception] | None = None, embed_dim: int | None = None):
        self.script = list(script or [])
        self.calls: list[dict] = []
        self.embed_dim = embed_dim or settings.embed_dim

    def chat(self, messages, tools=None, timeout: float = 20, model: str | None = None) -> ChatResult:
        self.calls.append({"messages": messages, "tools": tools, "timeout": timeout, "model": model})
        assert self.script, "FakeLLM script exhausted"
        item = self.script.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    def embed(self, texts: list[str]) -> list[list[float]]:
        out = []
        for t in texts:
            digest = hashlib.sha256(t.encode()).digest()
            out.append([digest[i % len(digest)] / 255.0 for i in range(self.embed_dim)])
        return out


@lru_cache
def get_llm() -> LLM:
    return LLM(settings.token_factory_base_url, settings.token_factory_api_key, settings.llm_model,
               settings.embed_model, settings.vision_model, settings.embed_dim)
