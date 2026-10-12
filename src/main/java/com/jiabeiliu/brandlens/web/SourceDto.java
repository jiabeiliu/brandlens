package com.jiabeiliu.brandlens.web;
/** Retrieved evidence. cited validates only the reference ID, not semantic support. */
public record SourceDto(long chunkId, String docId, double score, String excerpt, boolean cited) {}
