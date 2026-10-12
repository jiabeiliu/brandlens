# Evaluation protocol
Run the real backend, ingest the documented fixtures, then run:
`python3 scripts/evaluate.py --base http://localhost:PORT`
Use 8081 for BrandLens, 8080 for RAG. Raw output is saved locally (not committed).
Mechanical citation checks do not measure factual accuracy. For comparisons, inspect A/B citations manually.
Review each answer against the retrieved text. Mark correctness, support, and (for BrandLens) actionability independently.
Include no-answer cases; false confident answers are failures. Compare prompt/chunk/top-k variants with identical cases and corpus.
Record model, corpus version, settings and latency alongside reviewed results. Do not report an accuracy until reviewed.
No RAG-versus-fine-tune superiority is established by the current fixtures.
