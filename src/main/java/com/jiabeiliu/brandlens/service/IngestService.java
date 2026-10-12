package com.jiabeiliu.brandlens.service;

import com.jiabeiliu.brandlens.model.BrandChunk;
import com.jiabeiliu.brandlens.repository.BrandChunkRepository;
import com.pgvector.PGvector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ingest pipeline: chunk -&gt; embed (batched) -&gt; persist, tagged by brand.
 */
@Service
public class IngestService {

    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final BrandChunkRepository repository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public IngestService(ChunkingService chunkingService,
                         EmbeddingService embeddingService,
                         BrandChunkRepository repository, org.springframework.jdbc.core.JdbcTemplate jdbc,
                         org.springframework.transaction.support.TransactionTemplate transaction) {
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.jdbc = jdbc; this.transaction = transaction;
    }

    /**
     * Ingests one document for a brand. Returns the number of chunks stored.
     */
    public int ingest(String brand, String docId, String text) {
        List<String> chunks = chunkingService.chunk(text);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Document must not be empty");
        }
        List<float[]> embeddings = embeddingService.embedBatch(chunks);
        if (embeddings.size() != chunks.size()) throw new IllegalStateException("Embedding count mismatch");
        return transaction.execute(status -> {
            lock(brand);
            repository.deleteByBrandAndDocId(brand, docId);
            repository.flush();
            for (int i=0;i<chunks.size();i++) repository.save(new BrandChunk(brand,docId,i,chunks.get(i),new PGvector(embeddings.get(i))));
            return chunks.size();
        });
    }
    private void lock(String brand) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, "brand:" + brand);
    }

    public long deleteDocument(String brand, String docId) {
        return transaction.execute(status -> { lock(brand); return repository.deleteByBrandAndDocId(brand,docId); });
    }

    /** Removes all chunks for a brand (re-ingest cleanly). */
    @Transactional
    public long clearBrand(String brand) {
        lock(brand);
        long n = repository.countByBrand(brand);
        repository.deleteByBrand(brand);
        return n;
    }
}
