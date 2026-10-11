package com.jiabeiliu.brandlens.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Request body for {@code POST /api/insights} (and {@code /api/insights/eval}).
 *
 * <p>{@code history} is optional and enables stateless multi-turn follow-ups:
 * pass prior turns as {@code [{role, content}]} and the follow-up question in
 * {@code question}. History only resolves context (e.g. pronouns); factual
 * claims are still grounded in retrieved chunks.
 */
public record InsightRequest(
        @NotBlank String brand,
        @NotBlank String question,
        @Positive Integer topK,
        List<Turn> history) {

    /** Defaults topK to 5 when omitted. */
    public int effectiveTopK() {
        return topK != null ? topK : 5;
    }

    /** Empty list when no history is supplied; unusable turns are filtered later. */
    public List<Turn> effectiveHistory() {
        return history != null ? history : List.of();
    }
}
