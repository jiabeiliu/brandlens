package com.jiabeiliu.brandlens.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.jiabeiliu.brandlens.service.BrandCatalogService;
import com.jiabeiliu.brandlens.service.IngestService;
import com.jiabeiliu.brandlens.service.InsightService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BrandController {

    private final BrandCatalogService catalogService;
    private final IngestService ingestService;
    private final InsightService insightService;

    public BrandController(BrandCatalogService catalogService,
                           IngestService ingestService,
                           InsightService insightService) {
        this.catalogService = catalogService;
        this.ingestService = ingestService;
        this.insightService = insightService;
    }

    /** List all brands with entity profiles (the lightweight "knowledge graph"). */
    @GetMapping("/brands")
    public List<String> brands() {
        return catalogService.brands();
    }

    /** Full entity profile for one brand: products, competitors, audience segments. */
    @GetMapping("/brands/{brand}")
    public JsonNode brandProfile(@PathVariable String brand) {
        JsonNode profile = catalogService.profile(brand);
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown brand: " + brand);
        }
        return profile;
    }

    /** Ingest a document for a brand: chunk it, embed the chunks, store them with vectors. */
    @PostMapping("/ingest")
    public Map<String, Object> ingest(@Valid @RequestBody IngestRequest request) {
        String brand = request.brand().toLowerCase();
        if (!catalogService.known(brand)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown brand: " + request.brand() + ". Known: " + catalogService.brands());
        }
        int chunks = ingestService.ingest(brand, request.docId(), request.text());
        return Map.of("brand", brand, "docId", request.docId(), "chunks", chunks);
    }

    /** Clear all chunks for a brand (re-ingest cleanly). */
    @DeleteMapping("/ingest/{brand}")
    public Map<String, Object> clearBrand(@PathVariable String brand) {
        long removed = ingestService.clearBrand(brand.toLowerCase());
        return Map.of("brand", brand, "removedChunks", removed);
    }

    /**
     * Generate an audience insight for a brand: retrieve its top-K chunks,
     * combine with its entity profile, and generate a grounded answer.
     *
     * <p>Optionally accepts a stateless {@code history} of prior turns so
     * follow-up questions resolve context ("their" → the brand).
     */
    @PostMapping("/insights")
    public InsightResponse insights(@Valid @RequestBody InsightRequest request) {
        String brand = request.brand().toLowerCase();
        if (!catalogService.known(brand)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown brand: " + request.brand() + ". Known: " + catalogService.brands());
        }
        try {
            return insightService.insight(brand, request.question(),
                    request.effectiveTopK(), request.effectiveHistory());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Side-by-side comparison of two brands on one advertiser question.
     * Each brand's chunks are retrieved separately (brand-scoped) and a
     * single LLM call produces the comparison with per-brand citations.
     */
    @PostMapping("/compare")
    public CompareResponse compare(@Valid @RequestBody CompareRequest request) {
        String brandA = request.brandA().toLowerCase();
        String brandB = request.brandB().toLowerCase();
        for (String brand : List.of(brandA, brandB)) {
            if (!catalogService.known(brand)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown brand: " + brand + ". Known: " + catalogService.brands());
            }
        }
        try {
            return insightService.compare(brandA, brandB, request.question(), request.effectiveTopK());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Generate an insight AND score it with a strict LLM judge (groundedness
     * 1-5: are claims supported by cited chunks? relevance 1-5: does it
     * answer the question?). Accepts the same body as {@code /api/insights},
     * including optional {@code history}.
     */
    @PostMapping("/insights/eval")
    public EvalResponse evalInsights(@Valid @RequestBody InsightRequest request) {
        String brand = request.brand().toLowerCase();
        if (!catalogService.known(brand)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown brand: " + request.brand() + ". Known: " + catalogService.brands());
        }
        try {
            return insightService.evaluate(brand, request.question(),
                    request.effectiveTopK(), request.effectiveHistory());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
