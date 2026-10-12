# Recorded local verification — 2026-10-11

Backend: Java 17 / Spring Boot, isolated PostgreSQL with pgvector, Ollama nomic-embed-text (768d) and llama3.1:8b.

- Real embedding and ingestion completed; repeated document ingestion did not increase chunk count.
- Final sourced-company question returned facts with valid chunk references, explicitly speculative audience hypotheses, a message test and evidence gaps. Three facts were checked against the official company summary by the coding assistant (not an independent human study).
- Four representative cases were captured after the corpus update with a 256-token test budget: audience, unavailable ROAS, contextual pricing follow-up and brand comparison. The pricing follow-up had valid references; three cases conservatively abstained without citations. No invalid references were returned. This is evidence of mechanical behavior, not semantic accuracy or product effectiveness.
- Default generation budget is 512 tokens; the final positive smoke test used that default and cited the supplied facts.
- The complete 20-case suite is provided, but has not been fully rerun after the corpus revision. No aggregate accuracy, audience validity or ROAS improvement is claimed.

Run scripts/evaluate.py to capture new outputs and perform independent claim-level review. Citation existence does not establish support.
