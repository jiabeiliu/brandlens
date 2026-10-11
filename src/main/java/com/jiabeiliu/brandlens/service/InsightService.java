package com.jiabeiliu.brandlens.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiabeiliu.brandlens.web.CompareResponse;
import com.jiabeiliu.brandlens.web.EvalResponse;
import com.jiabeiliu.brandlens.web.InsightResponse;
import com.jiabeiliu.brandlens.web.SourceDto;
import com.jiabeiliu.brandlens.web.Turn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brand-scoped retrieve-then-generate: given a brand and an advertiser
 * question, retrieves the brand's top-K chunks from pgvector, injects the
 * brand's entity profile (products, competitors, audience segments), and
 * asks the LLM to write an audience insight grounded in both.
 *
 * <p>The model is instructed to answer <b>using only the provided brand
 * context</b> and to cite chunk ids, so insights stay grounded in the
 * ingested brand knowledge instead of hallucinating.
 *
 * <p>Also hosts the newer GenAI-flavored capabilities:
 * <ul>
 *   <li>{@link #compare} — side-by-side insight for two brands in one LLM call;</li>
 *   <li>{@link #evaluate} — LLM-as-judge scoring (groundedness, relevance);</li>
 *   <li>multi-turn follow-ups via an optional, stateless {@code history}.</li>
 * </ul>
 */
@Service
public class InsightService {

    private static final int MAX_HISTORY_TURNS = 10;
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final BrandCatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String chatModel;

    /** Retrieved chunks plus the rendered prompt text for them. */
    private record Ctx(List<SourceDto> sources, String text) {
    }

    /** Parsed LLM-judge scores (-1 when the judge output was unparseable). */
    private record Scores(int groundedness, String groundednessRationale,
                          int relevance, String relevanceRationale) {
    }

    public InsightService(EmbeddingService embeddingService,
                          RetrievalService retrievalService,
                          BrandCatalogService catalogService,
                          ObjectMapper objectMapper,
                          RestClient.Builder builder,
                          @Value("${llm.base-url:http://localhost:11434/v1}") String baseUrl,
                          @Value("${llm.api-key:}") String apiKey,
                          @Value("${llm.chat-model:llama3.1:8b}") String chatModel) {
        this.embeddingService = embeddingService;
        this.retrievalService = retrievalService;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
        RestClient.Builder b = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (apiKey != null && !apiKey.isBlank()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.restClient = b.build();
    }

    /** Single-turn insight (existing behavior preserved). */
    public InsightResponse insight(String brand, String question, int topK) {
        return insight(brand, question, topK, List.of());
    }

    /**
     * Insight with optional multi-turn history. History only resolves
     * conversational context (e.g. "their" → Nike); every factual claim must
     * still come from the retrieved chunks.
     */
    public InsightResponse insight(String brand, String question, int topK, List<Turn> history) {
        JsonNode profile = requireProfile(brand);
        float[] queryEmbedding = embeddingService.embed(question);
        Ctx ctx = retrieveCtx(brand, queryEmbedding, topK, null);

        String system = insightSystemPrompt(!usableHistory(history).isEmpty());
        String user = "Brand entity profile:\n" + catalogService.renderForPrompt(profile)
                + "\nRetrieved brand knowledge:\n" + ctx.text()
                + "\nAdvertiser question: " + question;

        String answer = chat(buildMessages(system, history, user), 0.2);
        return new InsightResponse(brand, answer, ctx.sources());
    }

    /**
     * Side-by-side comparison of two brands on one advertiser question.
     * Retrieves each brand's chunks separately (brand-scoped, never mixed),
     * then makes a single LLM call with per-brand chunk labels so citations
     * stay attributable ([A:chunk:12] vs [B:chunk:7]).
     */
    public CompareResponse compare(String brandA, String brandB, String question, int topK) {
        if (brandA.equals(brandB)) {
            throw new IllegalArgumentException("brandA and brandB must be different brands");
        }
        JsonNode profileA = requireProfile(brandA);
        JsonNode profileB = requireProfile(brandB);

        float[] queryEmbedding = embeddingService.embed(question);
        Ctx ctxA = retrieveCtx(brandA, queryEmbedding, topK, "A");
        Ctx ctxB = retrieveCtx(brandB, queryEmbedding, topK, "B");

        String system = """
                You are an advertising insights assistant (in the spirit of Google Ads' \
                Insights Finder). An advertiser wants a side-by-side comparison of two \
                brands. Use ONLY the entity profiles and retrieved chunks below. Chunks \
                from brand A are labeled [A:chunk:id], brand B as [B:chunk:id] — never \
                mix them up. Structure your answer as: 1) Audience contrast — who each \
                brand targets and how they differ, 2) Message angles — what would \
                resonate for each brand, 3) Positioning gap — where one brand has an \
                edge over the other. Be specific and concise. Cite chunks like \
                [A:chunk:12] and [B:chunk:7]. If the context does not cover something, \
                say so explicitly.""";
        String user = "Brand A entity profile:\n" + catalogService.renderForPrompt(profileA)
                + "\nBrand A knowledge:\n" + ctxA.text()
                + "\nBrand B entity profile:\n" + catalogService.renderForPrompt(profileB)
                + "\nBrand B knowledge:\n" + ctxB.text()
                + "\nAdvertiser question: " + question;

        String answer = chat(buildMessages(system, List.of(), user), 0.2);
        return new CompareResponse(brandA, brandB, answer, ctxA.sources(), ctxB.sources());
    }

    /**
     * LLM-as-judge: generates the insight, then a second, strict LLM call
     * scores it 1-5 on groundedness (claims supported by cited chunks?) and
     * relevance (answers the question?). Demonstrates GenAI eval rigor.
     */
    public EvalResponse evaluate(String brand, String question, int topK, List<Turn> history) {
        JsonNode profile = requireProfile(brand);
        float[] queryEmbedding = embeddingService.embed(question);
        Ctx ctx = retrieveCtx(brand, queryEmbedding, topK, null);

        String system = insightSystemPrompt(!usableHistory(history).isEmpty());
        String user = "Brand entity profile:\n" + catalogService.renderForPrompt(profile)
                + "\nRetrieved brand knowledge:\n" + ctx.text()
                + "\nAdvertiser question: " + question;
        String answer = chat(buildMessages(system, history, user), 0.2);

        Scores scores = judge(question, ctx.text(), answer);
        return new EvalResponse(brand, answer, ctx.sources(),
                scores.groundedness(), scores.groundednessRationale(),
                scores.relevance(), scores.relevanceRationale());
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private JsonNode requireProfile(String brand) {
        JsonNode profile = catalogService.profile(brand);
        if (profile == null) {
            throw new IllegalArgumentException("Unknown brand: " + brand
                    + ". Known brands: " + catalogService.brands());
        }
        return profile;
    }

    private String insightSystemPrompt(boolean hasHistory) {
        String base = """
                You are an advertising insights assistant (in the spirit of Google Ads' \
                Insights Finder). An advertiser wants audience insights about a brand. \
                Answer using ONLY the brand entity profile and the retrieved context \
                chunks below. Structure your answer as: 1) Target audience, \
                2) Key message angles, 3) One watch-out. Be specific and concise. \
                If the context does not contain the answer, say so explicitly. \
                Cite the chunks you used like [chunk:12].""";
        if (hasHistory) {
            base += " Conversation history is provided only to resolve context (e.g. "
                    + "pronouns like \"they\"); ground every factual claim in the "
                    + "retrieved chunks below, not in prior turns.";
        }
        return base;
    }

    /**
     * Retrieves top-K chunks for one brand. When {@code label} is non-blank
     * (e.g. "A"), chunks are tagged {@code [A:chunk:id]} for the comparison
     * prompt; otherwise the original {@code [chunk:id]} format is kept.
     */
    private Ctx retrieveCtx(String brand, float[] queryEmbedding, int topK, String label) {
        List<ScoredChunk> chunks = retrievalService.retrieve(brand, queryEmbedding, topK);
        StringBuilder context = new StringBuilder();
        List<SourceDto> sources = new ArrayList<>();
        for (ScoredChunk c : chunks) {
            String tag = (label == null || label.isBlank())
                    ? "[chunk " + c.chunkId() + " | doc " + c.docId() + "]"
                    : "[" + label + ":chunk:" + c.chunkId() + " | doc " + c.docId() + "]";
            context.append(tag).append("\n").append(c.content()).append("\n\n");
            sources.add(new SourceDto(c.chunkId(), c.docId(), c.score()));
        }
        return new Ctx(sources, context.toString());
    }

    private List<Turn> usableHistory(List<Turn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Turn> usable = history.stream().filter(Turn::isUsable).toList();
        int skip = Math.max(0, usable.size() - MAX_HISTORY_TURNS);
        return usable.subList(skip, usable.size());
    }

    private List<Map<String, String>> buildMessages(String system, List<Turn> history, String userContent) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        for (Turn t : usableHistory(history)) {
            messages.add(Map.of("role", t.role().toLowerCase(), "content", t.content()));
        }
        messages.add(Map.of("role", "user", "content", userContent));
        return messages;
    }

    private String chat(List<Map<String, String>> messages, double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", chatModel);
        body.put("temperature", temperature);
        body.put("messages", messages);

        JsonNode root = restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return root.path("choices").path(0).path("message").path("content").asText("").strip();
    }

    /** Second LLM call: strict judge scoring groundedness and relevance 1-5. */
    private Scores judge(String question, String chunkDump, String answer) {
        String judgeSystem = """
                You are a strict evaluator of AI-generated brand insights. Score the \
                INSIGHT below on two dimensions, each an integer 1-5:
                - groundedness: every factual claim must be supported by a cited chunk \
                ([chunk:id]). 1 = key claims uncited or contradict the chunks; \
                5 = all claims trace to cited chunks. Be strict: a polished but \
                uncited answer scores at most 2.
                - relevance: does it directly answer the advertiser's question? \
                1 = off-topic; 5 = directly answers with specifics.
                Respond with ONLY this JSON object, nothing else:
                {"groundedness": <1-5>, "groundedness_rationale": "<one sentence>", \
                "relevance": <1-5>, "relevance_rationale": "<one sentence>"}""";
        String judgeUser = "Advertiser question: " + question
                + "\nRetrieved chunks:\n" + chunkDump
                + "\nINSIGHT:\n" + answer;

        String raw = chat(buildMessages(judgeSystem, List.of(), judgeUser), 0.0);
        return parseJudgeScores(raw);
    }

    private Scores parseJudgeScores(String raw) {
        try {
            Matcher m = JSON_OBJECT.matcher(raw);
            if (m.find()) {
                JsonNode n = objectMapper.readTree(m.group());
                return new Scores(
                        n.path("groundedness").asInt(-1),
                        n.path("groundedness_rationale").asText(""),
                        n.path("relevance").asInt(-1),
                        n.path("relevance_rationale").asText(""));
            }
        } catch (Exception ignored) {
            // fall through to the unparseable fallback below
        }
        return new Scores(-1, "judge returned non-JSON output",
                -1, "judge returned non-JSON output");
    }
}
