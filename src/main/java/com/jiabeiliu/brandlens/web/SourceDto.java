package com.jiabeiliu.brandlens.web;

/** One retrieved chunk cited in an insight. {@code score} is cosine distance (lower = more similar). */
public record SourceDto(
        long chunkId,
        String docId,
        double score) {
}
