package com.jiabeiliu.brandlens.model;

import com.jiabeiliu.brandlens.config.PGvectorType;
import com.pgvector.PGvector;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

/**
 * One chunk of an ingested brand document, with its embedding stored in a
 * pgvector {@code vector(768)} column (matches nomic-embed-text).
 *
 * <p>The {@code brand} column scopes retrieval to a single brand, which is
 * how the insights endpoint keeps answers grounded in the right brand's
 * knowledge instead of mixing Nike with Tesla.
 */
@Entity
@Table(name = "brand_chunk")
public class BrandChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String brand;

    @Column(name = "doc_id", nullable = false)
    private String docId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Type(PGvectorType.class)
    @Column(columnDefinition = "vector(768)")
    private PGvector embedding;

    protected BrandChunk() {
    }

    public BrandChunk(String brand, String docId, int chunkIndex, String content, PGvector embedding) {
        this.brand = brand;
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.embedding = embedding;
    }

    public Long getId() {
        return id;
    }

    public String getBrand() {
        return brand;
    }

    public String getDocId() {
        return docId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public PGvector getEmbedding() {
        return embedding;
    }
}
