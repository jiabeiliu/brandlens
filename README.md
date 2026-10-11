# BrandLens — mini brand-insights engine

A weekend build: a tiny "Insights Finder" for brands. Given a brand and an
advertiser-style question, it retrieves that brand's knowledge from pgvector,
combines it with a lightweight brand-entity profile (products, competitors,
audience segments), and generates a grounded audience insight with cited
sources — using a local LLM, free.

Built for a Monday chat with Google's Ads Planning team (Insights Finder /
PARC): same retrieve → generate → cite architecture, applied to brands
instead of medical knowledge.

## Architecture

```
POST /api/ingest ──▶ ChunkingService ──▶ EmbeddingService ──▶ PostgreSQL + pgvector
  {brand, docId,     (1500 chars,        (nomic-embed-text,     brand_chunk(brand, embedding
   text}             200 overlap)         768-dim, batched)     vector(768))

POST /api/insights ──▶ RetrievalService ──▶ InsightService ──▶ {brand, insight, sources[]}
  {brand, question,    (pgvector <=>         (brand entity profile
   history?}           cosine distance,      + top-K chunks
                       brand-scoped)          → llama3.1:8b,
                                              answers ONLY from
                                              brand context,
                                              cites [chunk:id];
                                              optional stateless
                                              history resolves
                                              follow-up context)

POST /api/compare ──▶ retrieve BOTH brands ──▶ one LLM call ──▶ {brandA, brandB,
  {brandA, brandB,      (brand-scoped each)     audience contrast,     comparison,
   question}                               message angles,       sourcesA[],
                                           positioning gap,      sourcesB[]}
                                           cites [A:chunk:id] /
                                           [B:chunk:id]

POST /api/insights/eval ──▶ insight (as above) ──▶ strict LLM judge ──▶ {insight,
  {brand, question,                          2nd LLM call scores       sources[],
   history?}                                 groundedness 1-5          groundedness,
                                             (claims ← cited chunks?)  relevance,
                                             relevance 1-5             rationales}

GET /api/brands ──▶ BrandCatalogService ──▶ entities/*.json (the "knowledge graph" stand-in:
GET /api/brands/{brand}                     products, competitors, audience segments,
                                            key facts per brand)
```

Pipeline hardening (see PIPELINE.md): `scripts/seed.py` is incremental
(SHA-256 per doc, skips unchanged brands, `--force`/`--clear`/`--dry-run`),
validates docs (rejects empty, warns < 200 chars), and
`scripts/extract_entities.py` drafts entity JSONs via local Ollama for
hand-review when adding a brand.

Key design choices (good talking points):
- **Brand-scoped retrieval**: the `brand` column on every chunk keeps Nike's
  chunks out of Tesla's answers. In the real world this is what KG entity
  clusters do — bind retrieved knowledge to the right entity.
- **Entity profile + retrieval, not just retrieval**: the LLM prompt gets both
  the curated entity profile and the top-K retrieved chunks. Structured
  knowledge (entities) + unstructured knowledge (docs) in one prompt.
- **Grounded generation**: temperature 0.2, "answer only from context", chunk
  citations — the same anti-hallucination discipline as production insights
  products.
- **Ollama-first, OpenAI-compatible**: `POST /v1/embeddings` +
  `POST /v1/chat/completions`; switching providers is config, not code.
- **LLM-as-judge eval loop**: a second, strict LLM call scores every insight
  on groundedness (claims ← cited chunks?) and relevance 1-5 — the cheapest
  way to iterate on GenAI feature quality before human eval.
- **Comparison without cross-contamination**: `/api/compare` retrieves each
  brand separately and tags chunks `[A:chunk:id]` / `[B:chunk:id]` in a single
  prompt, so the model can't attribute Nike's facts to Tesla.
- **Stateless multi-turn**: history rides in the request, not server memory —
  easy to scale, easy to replay for debugging.

## Run it (Mac, ~3 commands)

Prerequisites (one-time, same as the RAG project):
```bash
brew services start postgresql   # with pgvector installed
brew services start ollama        # models: nomic-embed-text, llama3.1:8b
createdb -O rag brandlensdb      # once; reuses the existing 'rag' db user
```

Then:
```bash
cd ~/workspace/brandlens
mvn spring-boot:run              # terminal 1 — starts on :8081, schema.sql auto-creates the table
python3 scripts/seed.py          # terminal 2 — ingests all 12 brand docs (~2-3 min on Ollama)
```

Open **http://localhost:8081/** for the demo UI — single-brand insights, brand comparison,
LLM-judge eval scores, and multi-turn follow-ups, all in one page.

Try it (API):
```bash
# What brands are loaded?
curl localhost:8081/api/brands

# Brand entity profile (the "KG" layer)
curl localhost:8081/api/brands/nike

# Generate an audience insight
curl -X POST localhost:8081/api/insights -H 'Content-Type: application/json' \
  -d '{"brand":"nike","question":"Who is the target audience for Nike running shoes and what message angles would resonate?"}'

curl -X POST localhost:8081/api/insights -H 'Content-Type: application/json' \
  -d '{"brand":"tesla","question":"Who should Tesla target with robotaxi ads in Texas?"}'

curl -X POST localhost:8081/api/insights -H 'Content-Type: application/json' \
  -d '{"brand":"starbucks","question":"What audience is Starbucks trying to win back with its store redesign?"}'

# Multi-turn follow-up (stateless: client passes prior turns)
curl -X POST localhost:8081/api/insights -H 'Content-Type: application/json' \
  -d '{"brand":"nike","question":"What about their pricing strategy for that audience?",
       "history":[{"role":"user","content":"Who is the target audience for Nike running shoes?"},
                  {"role":"assistant","content":"Competitive runners and marathoners..."}]}'

# Side-by-side brand comparison (the Insights Finder-style use case)
curl -X POST localhost:8081/api/compare -H 'Content-Type: application/json' \
  -d '{"brandA":"nike","brandB":"tesla","question":"How do their target audiences differ, and what message angles would work for each?"}'

# LLM-as-judge: insight + groundedness/relevance scores (1-5)
curl -X POST localhost:8081/api/insights/eval -H 'Content-Type: application/json' \
  -d '{"brand":"nike","question":"Who is the target audience for Nike running shoes and what message angles would resonate?"}'
```

Re-ingest: `python3 scripts/seed.py` (incremental — skips unchanged brands),
`--dry-run` to preview, `--force` to redo everything,
`--clear` to wipe first, or `curl -X DELETE localhost:8081/api/ingest/nike`.
Pipeline details: see PIPELINE.md.

## Data

`data/{nike,starbucks,tesla}/` — 12 docs, summarized Oct 2026 from public
sources (official about pages, company profiles, press coverage). Each file
notes its source. `src/main/resources/entities/*.json` — hand-curated entity
profiles per brand.

## 30-second pitch (for the Monday chat)

> "Over the weekend I built a mini version of what your team does with
> Insights Finder. It's a brand-insights engine: brand knowledge goes into
> pgvector, each brand gets a small entity profile — products, competitors,
> audience segments, basically a tiny knowledge graph — and when an
> advertiser asks a question, it retrieves the brand's chunks and generates
> a grounded insight with citations, all on a local LLM. Then I added the
> three things I'd actually do on the job: a side-by-side brand comparison
> endpoint (retrieves both brands separately, one LLM call, per-brand
> citations), an LLM-as-judge eval that scores groundedness and relevance
> 1-5 so I can iterate on prompt quality, and stateless multi-turn
> follow-ups. Plus I hardened the data pipeline — incremental ingestion
> with content hashing, doc validation, and a documented path to add a new
> brand. The interesting part was scoping retrieval per brand so knowledge
> never leaks across entities, which is the same problem your KG entity
> clusters solve at Google scale. Happy to walk you through the code —
> it's Spring Boot, under 1,000 lines."

## Status / next steps

- [x] Compiles (`mvn compile`)
- [x] Brand comparison endpoint (`POST /api/compare`)
- [x] LLM-as-judge eval (`POST /api/insights/eval`)
- [x] Multi-turn follow-ups (optional `history` on `/api/insights`)
- [x] Pipeline hardening (incremental seed, validation, PIPELINE.md, entity drafter)
- [ ] Run + seed on Mac, verify one insight per brand end-to-end
- [ ] Optional: add a 4th brand (see PIPELINE.md — ~10 min), tune topK, try a bigger local model
- [ ] Not pushed to GitHub yet — push when ready to show it
