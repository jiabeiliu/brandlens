package com.jiabeiliu.brandlens.repository;

import com.jiabeiliu.brandlens.model.BrandChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Cosine-distance nearest-neighbor search via pgvector's {@code <=>} operator,
 * scoped to a single brand.
 *
 * <p>The query embedding is bound as a text literal and cast to {@code vector}
 * inside SQL. Rows come back as {@code Object[]} = [id, doc_id, chunk_index,
 * content, score] where {@code score} is the cosine distance (lower = more
 * similar).
 */
public interface BrandChunkRepository extends JpaRepository<BrandChunk, Long> {

    @Query(value = """
            SELECT bc.id, bc.doc_id, bc.chunk_index, bc.content,
                   (bc.embedding <=> CAST(:embedding AS vector)) AS score
            FROM brand_chunk bc
            WHERE bc.brand = :brand
            ORDER BY score ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findNearestByBrand(@Param("brand") String brand,
                                      @Param("embedding") String embeddingLiteral,
                                      @Param("limit") int limit);

    long deleteByBrandAndDocId(String brand, String docId);

    long countByBrand(String brand);

    @Modifying
    void deleteByBrand(String brand);
}
