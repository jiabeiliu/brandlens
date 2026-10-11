package com.jiabeiliu.brandlens.service;

import com.jiabeiliu.brandlens.repository.BrandChunkRepository;
import com.pgvector.PGvector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Vector search over ingested chunks using pgvector cosine distance,
 * scoped to one brand so answers never mix brands.
 */
@Service
public class RetrievalService {

    private final BrandChunkRepository repository;

    public RetrievalService(BrandChunkRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the top-K chunks of {@code brand} most similar to the query
     * embedding, ordered by ascending cosine distance.
     */
    @Transactional(readOnly = true)
    public List<ScoredChunk> retrieve(String brand, float[] queryEmbedding, int topK) {
        String literal = new PGvector(queryEmbedding).getValue();
        List<Object[]> rows = repository.findNearestByBrand(brand, literal, topK);
        List<ScoredChunk> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new ScoredChunk(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    ((Number) r[2]).intValue(),
                    (String) r[3],
                    ((Number) r[4]).doubleValue()));
        }
        return out;
    }
}
