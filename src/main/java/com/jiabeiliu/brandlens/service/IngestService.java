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

    public IngestService(ChunkingService chunkingService,
                         EmbeddingService embeddingService,
                         BrandChunkRepository repository) {
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.repository = repository;
    }

    /**
     * Ingests one document for a brand. Returns the number of chunks stored.
     */
    @Transactional
    public int ingest(String brand, String docId, String text) {
        List<String> chunks = chunkingService.chunk(text);
        if (chunks.isEmpty()) {
            return 0;
        }
        List<float[]> embeddings = embeddingService.embedBatch(chunks);
        for (int i = 0; i < chunks.size(); i++) {
            repository.save(new BrandChunk(brand, docId, i, chunks.get(i),
                    new PGvector(embeddings.get(i))));
        }
        return chunks.size();
    }

    /** Removes all chunks for a brand (re-ingest cleanly). */
    @Transactional
    public long clearBrand(String brand) {
        long n = repository.countByBrand(brand);
        repository.deleteByBrand(brand);
        return n;
    }
}
