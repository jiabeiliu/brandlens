package com.jiabeiliu.brandlens.service;

import java.util.List;

/**
 * Turns text into embedding vectors. Implementations batch inputs to stay
 * within provider request limits.
 */
public interface EmbeddingService {

    /** Embeds a batch of texts; result order matches input order. */
    List<float[]> embedBatch(List<String> texts);

    /** Convenience single-text embedding. */
    default float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }
}
