package com.jiabeiliu.brandlens.web;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/ingest}. */
public record IngestRequest(
        @NotBlank String brand,
        @NotBlank String docId,
        @NotBlank String text) {
}
