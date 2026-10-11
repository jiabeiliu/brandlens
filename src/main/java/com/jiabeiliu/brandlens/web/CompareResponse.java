package com.jiabeiliu.brandlens.web;

import java.util.List;

/**
 * Response body for {@code POST /api/compare}: a side-by-side comparison
 * with per-brand source lists so citations stay attributable.
 */
public record CompareResponse(
        String brandA,
        String brandB,
        String comparison,
        List<SourceDto> sourcesA,
        List<SourceDto> sourcesB) {
}
