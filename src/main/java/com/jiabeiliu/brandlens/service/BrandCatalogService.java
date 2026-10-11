package com.jiabeiliu.brandlens.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight brand-entity layer: reads the curated {@code entities/*.json}
 * profiles from the classpath.
 *
 * <p>This is the "knowledge graph" stand-in — in the real Ads Planning world
 * this would be KG entity clusters for brands; here each brand gets a small,
 * hand-curated entity profile (products, competitors, audience segments,
 * key facts) that is injected into the insight prompt alongside retrieved
 * chunks.
 */
@Service
public class BrandCatalogService {

    private final Map<String, JsonNode> brands = new LinkedHashMap<>();

    public BrandCatalogService(ObjectMapper objectMapper) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource r : resolver.getResources("classpath:entities/*.json")) {
                JsonNode node = objectMapper.readTree(r.getInputStream());
                brands.put(node.path("brand").asText(), node);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load brand entity profiles", e);
        }
    }

    /** All known brands (e.g. nike, starbucks, tesla). */
    public List<String> brands() {
        return new ArrayList<>(brands.keySet());
    }

    /** Full entity profile for a brand, or {@code null} if unknown. */
    public JsonNode profile(String brand) {
        return brands.get(brand);
    }

    public boolean known(String brand) {
        return brands.containsKey(brand);
    }

    /** Renders the entity profile as compact text for the LLM prompt. */
    public String renderForPrompt(JsonNode profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("Brand: ").append(profile.path("displayName").asText()).append("\n");
        sb.append("Tagline: ").append(profile.path("tagline").asText()).append("\n");
        sb.append("Industry: ").append(profile.path("industry").asText()).append("\n");
        sb.append("Products: ").append(join(profile.path("products"))).append("\n");
        sb.append("Competitors: ").append(join(profile.path("competitors"))).append("\n");
        sb.append("Audience segments: ").append(join(profile.path("audienceSegments"))).append("\n");
        sb.append("Marketing channels: ").append(join(profile.path("marketingChannels"))).append("\n");
        sb.append("Key facts: ").append(join(profile.path("keyFacts"))).append("\n");
        return sb.toString();
    }

    private String join(JsonNode arr) {
        List<String> items = new ArrayList<>();
        arr.forEach(n -> items.add(n.asText()));
        return String.join("; ", items);
    }
}
