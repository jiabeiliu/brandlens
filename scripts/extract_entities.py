#!/usr/bin/env python3
"""
DRAFTING AID (not production): use the local Ollama LLM to draft a brand
entity-profile JSON from the brand's docs in data/<brand>/.

The output ALWAYS needs hand review — the LLM will guess at facts it can't
see. Treat this as a first draft: verify every field, then save the final
version to src/main/resources/entities/<brand>.json.

Usage:
    python3 scripts/extract_entities.py nike          # print draft JSON to stdout
    python3 scripts/extract_entities.py nike --out    # also write entities/nike.json.draft

Requires: Ollama running locally (brew services start ollama), model llama3.1:8b.
"""
import json
import sys
import urllib.request
from pathlib import Path

OLLAMA = "http://localhost:11434/v1"
MODEL = "llama3.1:8b"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = PROJECT_ROOT / "data"
ENTITIES_DIR = PROJECT_ROOT / "src" / "main" / "resources" / "entities"

REQUIRED_KEYS = ["brand", "displayName", "tagline", "industry", "headquarters",
                 "founded", "products", "competitors", "audienceSegments",
                 "marketingChannels", "keyFacts"]

SCHEMA_HINT = """{
  "brand": "nike (lowercase id)",
  "displayName": "Nike",
  "tagline": "short tagline",
  "industry": "industry string",
  "headquarters": "City, State, Country",
  "founded": 1964,
  "products": ["product line 1", "product line 2"],
  "competitors": ["Competitor A", "Competitor B"],
  "audienceSegments": ["segment 1", "segment 2"],
  "marketingChannels": ["channel 1", "channel 2"],
  "keyFacts": ["verifiable fact 1", "verifiable fact 2"]
}"""


def chat(messages):
    payload = {"model": MODEL, "temperature": 0.1, "messages": messages}
    req = urllib.request.Request(
        OLLAMA + "/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        body = json.loads(resp.read().decode())
    return body["choices"][0]["message"]["content"]


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1].startswith("--"):
        print(__doc__)
        return 1
    brand = sys.argv[1].lower()
    write_out = "--out" in sys.argv

    brand_dir = DATA_ROOT / brand
    docs = sorted(brand_dir.glob("*.txt"))
    if not docs:
        print(f"No docs found in {brand_dir}")
        return 1

    corpus = "\n\n---\n\n".join(
        f"[doc {f.stem}]\n{f.read_text(encoding='utf-8')[:4000]}" for f in docs
    )
    print(f"Drafting entity profile for '{brand}' from {len(docs)} docs ...", file=sys.stderr)

    system = (
        "You extract a structured brand entity profile from the provided brand documents. "
        "Use ONLY facts stated in the documents — never invent revenue, dates, or names. "
        "If a field is not in the documents, use an empty string, 0, or an empty list. "
        "Respond with ONLY the JSON object, no markdown fences, no commentary. "
        "The JSON must have exactly these keys: " + ", ".join(REQUIRED_KEYS) + ".\n"
        "Example shape:\n" + SCHEMA_HINT
    )
    raw = chat([
        {"role": "system", "content": system},
        {"role": "user", "content": f"Brand documents:\n{corpus}"},
    ]).strip()

    # Strip accidental markdown fences.
    if raw.startswith("```"):
        raw = raw.strip("`").split("\n", 1)[-1].rsplit("```", 1)[0].strip()

    try:
        draft = json.loads(raw)
    except json.JSONDecodeError:
        print("!! LLM output was not valid JSON — printing raw for hand-editing:\n", file=sys.stderr)
        print(raw)
        return 2

    missing = [k for k in REQUIRED_KEYS if k not in draft]
    if missing:
        print(f"!! Draft is missing keys {missing} — hand-edit before use.", file=sys.stderr)
    draft["brand"] = brand  # enforce lowercase id

    pretty = json.dumps(draft, indent=2, ensure_ascii=False)
    print(pretty)

    if write_out:
        out_path = ENTITIES_DIR / f"{brand}.json.draft"
        out_path.write_text(pretty + "\n", encoding="utf-8")
        print(f"\nDraft written to {out_path} — REVIEW EVERY FIELD, then rename to {brand}.json",
              file=sys.stderr)
    else:
        print("\n# DRAFT ONLY — verify every field, then save to "
              f"src/main/resources/entities/{brand}.json", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
