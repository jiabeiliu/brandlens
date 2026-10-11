#!/usr/bin/env python3
"""
Seed BrandLens: ingest data/<brand>/*.txt into the running service.

Incremental by default: each doc's SHA-256 is stored in .seed-state.json
(project root). On re-run, brands whose docs are all unchanged are skipped;
a brand with any new/changed doc is cleared and fully re-ingested so chunks
never go stale or duplicate.

Validation per doc:
  - empty docs are REJECTED (counted as failed, never sent)
  - docs < 200 chars get a WARNING but are still ingested
  - chunk counts are logged per doc

Usage:
    python3 scripts/seed.py            # incremental ingest (skips unchanged)
    python3 scripts/seed.py --force    # re-ingest everything (clears per brand)
    python3 scripts/seed.py --clear    # wipe all brand data first, then ingest
    python3 scripts/seed.py --dry-run  # show what would happen, change nothing
"""
import hashlib
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = "http://localhost:8081"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = PROJECT_ROOT / "data"
STATE_FILE = PROJECT_ROOT / ".seed-state.json"
MIN_WARN_CHARS = 200


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8")).get("docs", {})
        except (json.JSONDecodeError, OSError) as e:
            print(f"[state] WARNING: could not read {STATE_FILE.name} ({e}); treating all as new")
    return {}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps({"version": 1, "docs": state}, indent=2), encoding="utf-8")


def post(path, payload):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode())


def delete(path):
    req = urllib.request.Request(BASE + path, method="DELETE")
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode())


def main() -> int:
    force = "--force" in sys.argv
    clear = "--clear" in sys.argv
    dry_run = "--dry-run" in sys.argv

    state = load_state()
    new_state = dict(state)
    summary = {}  # brand -> {ingested, skipped, failed, chunks}
    failures = 0

    brand_dirs = sorted(d for d in DATA_ROOT.iterdir() if d.is_dir() and not d.name.startswith("."))
    if not brand_dirs:
        print(f"No brand directories found in {DATA_ROOT}")
        return 1

    for brand_dir in brand_dirs:
        brand = brand_dir.name
        stats = summary.setdefault(brand, {"ingested": 0, "skipped": 0, "failed": 0, "chunks": 0})

        # --- validate + hash every doc first (no API calls yet) ---
        valid_docs = []  # (doc_id, text, changed)
        for f in sorted(brand_dir.glob("*.txt")):
            doc_id = f"{brand}-{f.stem}"
            text = f.read_text(encoding="utf-8")
            if not text.strip():
                stats["failed"] += 1
                failures += 1
                print(f"[{brand}] {f.name}: REJECTED (empty doc, not ingested)")
                continue
            if len(text) < MIN_WARN_CHARS:
                print(f"[{brand}] {f.name}: WARNING ({len(text)} chars < {MIN_WARN_CHARS}; ingesting anyway)")
            digest = sha256(text)
            changed = new_state.get(doc_id) != digest
            valid_docs.append((doc_id, f.name, text, digest, changed))

        if not valid_docs:
            print(f"[{brand}] no valid docs, skipping")
            continue

        needs_ingest = clear or force or any(changed for _, _, _, _, changed in valid_docs)

        if dry_run:
            action = "would re-ingest" if needs_ingest else "would skip (unchanged)"
            print(f"[{brand}] {action}: {len(valid_docs)} docs")
            continue

        if not needs_ingest:
            stats["skipped"] = len(valid_docs)
            print(f"[{brand}] skipped ({len(valid_docs)} docs unchanged)")
            continue

        # --- (re-)ingest: clear brand once so chunks never duplicate or go stale ---
        try:
            r = delete(f"/api/ingest/{brand}")
            print(f"[{brand}] cleared {r['removedChunks']} old chunks")
        except urllib.error.HTTPError as e:
            # Brand may have no chunks yet, or the service may not know the
            # brand (missing entities JSON) — the per-doc POSTs will surface it.
            print(f"[{brand}] clear returned HTTP {e.code} (continuing)")

        for doc_id, fname, text, digest, _ in valid_docs:
            try:
                r = post("/api/ingest", {"brand": brand, "docId": doc_id, "text": text})
                stats["ingested"] += 1
                stats["chunks"] += r["chunks"]
                new_state[doc_id] = digest
                print(f"[{brand}] {fname} -> {r['chunks']} chunks")
            except urllib.error.HTTPError as e:
                stats["failed"] += 1
                failures += 1
                body = e.read().decode(errors="replace")[:200]
                print(f"[{brand}] {fname}: FAILED (HTTP {e.code}: {body})")
            except OSError as e:
                stats["failed"] += 1
                failures += 1
                print(f"[{brand}] {fname}: FAILED ({e})")

    if not dry_run:
        save_state(new_state)

    print("\n--- summary ---")
    total_chunks = 0
    for brand, s in summary.items():
        total_chunks += s["chunks"]
        print(f"{brand}: ingested={s['ingested']} skipped={s['skipped']} "
              f"failed={s['failed']} chunks={s['chunks']}")
    print(f"total chunks this run: {total_chunks}")
    if failures:
        print(f"WARNING: {failures} doc(s) failed — see above")
    return 0


if __name__ == "__main__":
    sys.exit(main())
