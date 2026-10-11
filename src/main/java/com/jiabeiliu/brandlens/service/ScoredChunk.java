package com.jiabeiliu.brandlens.service;

/**
 * A retrieved chunk together with its cosine-distance score
 * (lower distance = more similar).
 */
public record ScoredChunk(
        long chunkId,
        String docId,
        int chunkIndex,
        String content,
        double score) {
}
