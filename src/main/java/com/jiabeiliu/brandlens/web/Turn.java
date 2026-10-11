package com.jiabeiliu.brandlens.web;

/**
 * One prior conversation turn, for stateless multi-turn follow-ups on
 * {@code POST /api/insights}. Role must be "user" or "assistant".
 */
public record Turn(String role, String content) {

    /** Only well-formed turns are injected into the prompt. */
    public boolean isUsable() {
        return content != null && !content.isBlank()
                && ("user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role));
    }
}
