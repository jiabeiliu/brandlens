package com.jiabeiliu.brandlens.web;

import java.util.List;

/**
 * Response body for {@code POST /api/insights/eval}: the generated insight
 * plus LLM-judge scores (1-5, or -1 if the judge output was unparseable).
 */
public record EvalResponse(
        String brand,
        String insight,
        List<SourceDto> sources,
        int groundedness,
        String groundednessRationale,
        int relevance,
        String relevanceRationale) {
}
