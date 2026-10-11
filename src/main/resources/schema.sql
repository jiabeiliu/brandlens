-- Runs before JPA startup (spring.sql.init.mode=always).
CREATE EXTENSION IF NOT EXISTS vector;

-- brand tags each chunk so retrieval can be scoped to one brand.
-- embedding is vector(768): nomic-embed-text dimension.
-- MUST match llm.embedding-dimensions in application.yml.
CREATE TABLE IF NOT EXISTS brand_chunk (
    id          BIGSERIAL PRIMARY KEY,
    brand       VARCHAR(64) NOT NULL,
    doc_id      VARCHAR(255) NOT NULL,
    chunk_index INT NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(768)
);

CREATE INDEX IF NOT EXISTS idx_brand_chunk_brand ON brand_chunk (brand);
CREATE INDEX IF NOT EXISTS idx_brand_chunk_doc_id ON brand_chunk (doc_id);
