package com.jiabeiliu.brandlens.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Request body for {@code POST /api/compare}. */
public record CompareRequest(
        @NotBlank String brandA,
        @NotBlank String brandB,
        @NotBlank String question,
        @Positive Integer topK) {

    /** Defaults topK to 5 when omitted (applied per brand). */
    public int effectiveTopK() {
        return topK != null ? topK : 5;
    }
}
