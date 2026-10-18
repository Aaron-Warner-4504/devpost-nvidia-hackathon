"""Live smoke test: one tool-calling chat and one embed. Needs a real .env. Run: uv run python scripts/smoke_llm.py"""
import sys
import time

sys.path.insert(0, ".")
from app.config import settings  # noqa: E402
from app.llm import get_llm  # noqa: E402

TOOL = {"type": "function", "function": {
    "name": "get_time", "description": "Get the current time in a city",
    "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}}}


def main() -> None:
    llm = get_llm()
    print(f"chat model={settings.llm_model} embed model={settings.embed_model} base={settings.token_factory_base_url}")
    t = time.perf_counter()
    r = llm.chat([{"role": "user", "content": "What time is it in Paris? Use the tool."}], tools=[TOOL], timeout=30)
    print(f"chat latency={time.perf_counter() - t:.2f}s tool_calls_returned={bool(r.tool_calls)} calls={r.tool_calls} content={r.content!r}")
    t = time.perf_counter()
    v = llm.embed(["hello world"])
    print(f"embed latency={time.perf_counter() - t:.2f}s dim={len(v[0])} (settings.embed_dim={settings.embed_dim})")


if __name__ == "__main__":
    main()
