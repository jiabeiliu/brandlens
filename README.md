# BrandLens — mini brand-insights engine

**[Interactive demo](https://jiabeiliu.github.io/brandlens/)** — authored example walkthrough, not a live LLM endpoint.

A portfolio prototype: a tiny "Insights Finder" for brands. Given a brand and an
advertiser-style question, it retrieves that brand's knowledge from pgvector,
combines it with a lightweight brand-entity profile (products, competitors,
audience segments), and generates a grounded audience insight with cited
sources — using a local LLM, free.

Built for a Monday chat with Google's Ads Planning team (Insights Finder /
PARC): a retrieve → generate → cite exploration applied to brands. Google internal architecture is not known or reproduced.
<img width="1267" height="868" alt="ad30d8e34f28364910ab749a2c060a24" src="https://github.com/user-attachments/assets/f21a7352-b96a-4bb3-9f14-9fe28201a9bd" />
<img width="1270" height="697" alt="3e97a2e21bed29bdef59112079d6ddf4" src="https://github.com/user-attachments/assets/d8064ea1-c63c-4e97-9889-c10c50446573" />



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
python3 scripts/seed.py          # terminal 2 — ingests the three source-backed brand docs (~2-3 min on Ollama)
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

`data/{nike,starbucks,tesla}/` contains one paraphrased official company-page summary per brand, checked 2026-10-11, with source URL and evidence limits. Publication dates are not inferred from access dates. Curated profiles use that same limited evidence; audience segments are not asserted as measured facts. Original unchecked summaries are preserved in `archive/unverified-corpus/` and excluded from seeding. A larger sourced corpus is future work.

## Prototype scope

This prototype uses curated brand JSON profiles, not an automatically learned knowledge graph.
The default corpus includes dated source provenance; each generated claim still needs a support check, and company positioning cannot establish measured audience behavior.
The public demo is authored examples. The local service performs actual retrieval and generation.
No measured audience composition, ROAS impact or equivalence with Google products is claimed.

## Reliability and evaluation improvements

- Follow-up retrieval includes the last two user questions and current brand; assistant answers are not treated as evidence. This is contextual expansion, not trained query rewriting.
- Sources include excerpts and a `cited` flag; unknown references are rejected. Semantic support still needs human evaluation.
- Answers distinguish source facts, inferred audience hypotheses, suggested tests and evidence gaps.
- Empty model answers and unavailable upstream services return explicit errors; generation has bounded timeouts.
- Ingestion atomically replaces each document, with embeddings computed before the transaction. Seed updates no longer clear a brand unless `--clear` is explicit.
- `eval/cases.json` contains 20 cases; `scripts/evaluate.py` records real responses and latency for human review. No evaluation score is fabricated.
- Same-model judge scores are experimental; they see the profile as well as retrieved evidence and validate score bounds.

Run `mvn verify` and `python3 -m unittest discover -s tests -v`.
See [evaluation protocol](eval/REVIEW.md).

## Status / next steps

- [x] Compiles (`mvn compile`)
- [x] Brand comparison endpoint (`POST /api/compare`)
- [x] LLM-as-judge eval (`POST /api/insights/eval`)
- [x] Multi-turn follow-ups (optional `history` on `/api/insights`)
- [x] Pipeline hardening (incremental seed, validation, PIPELINE.md, entity drafter)
- [x] Local PostgreSQL/Ollama ingestion and repeated-document replacement verified; answer review is recorded separately
- [ ] Optional: add a 4th brand (see PIPELINE.md — ~10 min), tune topK, try a bigger local model
- [x] Public source repository and authored interactive walkthrough
