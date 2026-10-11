package com.jiabeiliu.brandlens.web;

import java.util.List;

/** Response body for {@code POST /api/insights}. */
public record InsightResponse(
        String brand,
        String insight,
        List<SourceDto> sources) {
}
