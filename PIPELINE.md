# BrandLens data pipeline

How brand knowledge gets from raw text files into the queryable vector store —
and how to re-run it or extend it with a new brand.

## Stages

```
data/<brand>/*.txt
  │  fetch  (manual for now: official about pages, company profiles, press;
  │           each file notes its source at the top)
  ▼
scripts/seed.py
  │  validate   empty docs rejected · <200 chars warns · per-doc chunk counts logged
  │  hash       SHA-256 per doc → .seed-state.json (incremental: unchanged
  │             brands are skipped; a changed doc clears + re-ingests its brand)
  ▼
POST /api/ingest  {brand, docId, text}
  │  clean    (ChunkingService: whitespace-normalized)
  │  chunk    1500 chars, 200 overlap  (ChunkingService)
  │  embed    nomic-embed-text, 768-dim, batched  (EmbeddingService → Ollama)
  ▼
PostgreSQL + pgvector
  brand_chunk(brand, doc_id, chunk_index, content, embedding vector(768))

POST /api/insights | /api/compare | /api/insights/eval
  │  retrieve  pgvector cosine distance, ALWAYS scoped by brand
  │            (WHERE brand = ? — Nike's chunks can never leak into Tesla's answers)
  │  generate  brand entity profile (entities/*.json, the lightweight "KG") +
  │            top-K chunks → llama3.1:8b, temp 0.2, cited [chunk:id]
```

**Brand scoping is the load-bearing design decision.** Every chunk carries a
`brand` column and every retrieval filters on it — the same problem Google's
KG entity clusters solve at scale: bind retrieved knowledge to the right
entity so answers never mix brands.

## Re-running

```bash
python3 scripts/seed.py            # incremental: skips brands whose docs are unchanged
python3 scripts/seed.py --dry-run  # show what would happen, change nothing
python3 scripts/seed.py --force    # re-ingest everything (clears each brand first)
python3 scripts/seed.py --clear    # wipe all brand data, then ingest fresh
```

State lives in `.seed-state.json` (project root, git-ignored). Delete it to
force a full re-ingest the dumb way.

## Adding a new brand (e.g. Adidas)

1. **Docs**: drop 3–5 `.txt` files in `data/adidas/` (aim ≥ 200 chars each;
   note the source at the top of each file). Suggested split:
   `01-company-overview.txt`, `02-products.txt`, `03-recent-news.txt`,
   `04-marketing.txt`.
2. **Entity profile**: draft one with the LLM helper, then hand-verify EVERY field:
   ```bash
   python3 scripts/extract_entities.py adidas --out   # writes entities/adidas.json.draft
   # review, fix, then:
   mv src/main/resources/entities/adidas.json.draft src/main/resources/entities/adidas.json
   ```
   Or write it by hand — required keys: `brand, displayName, tagline, industry,
   headquarters, founded, products, competitors, audienceSegments,
   marketingChannels, keyFacts` (see `entities/nike.json` for the shape).
3. **Rebuild + restart** the service (entities/*.json load at startup):
   ```bash
   mvn spring-boot:run
   ```
4. **Seed**: `python3 scripts/seed.py` — picks up the new brand automatically.
5. **Verify**: `curl localhost:8081/api/brands` should list `adidas`,
   then try an insight + a compare against nike.

## Known limitations (honest)

- **Fetch is manual.** A production pipeline would schedule crawls, dedupe, and
  handle updates; here "fetch" is a human saving public pages to text files.
- **No per-doc delete.** Changed docs clear and re-ingest the whole brand —
  fine at this scale, wasteful at 10k docs.
- **Entity profiles are hand-curated.** `extract_entities.py` is a drafting aid;
  its output must be verified field by field (LLMs hallucinate facts).
- **Eval is LLM-judged, not human-judged.** Good for iteration speed, not a
  substitute for real relevance labels.
