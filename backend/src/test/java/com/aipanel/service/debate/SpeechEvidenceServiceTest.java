package com.aipanel.service.debate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.aipanel.config.PanelProperties;
import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.RuntimeTask;
import com.aipanel.model.Discussion;
import com.aipanel.model.Participant;
import com.aipanel.model.Utterance;
import com.aipanel.service.debate.BidArbitrator.Bid;
import com.aipanel.service.debate.CrossrefEvidenceLookup.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpeechEvidenceServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUERY = "generative AI graduate software recruitment";
    private static final Participant EXPERT = new Participant("A", "e1", "EXPERT", "甲", "研究员", "组长",
            "比较录用变化", "IMPACT", "blue", "IDLE", null);
    private static final Bid BID = new Bid(EXPERT, "RAISE", null, "IMPACT", "校招录用变化需要区分同岗需求和筛选门槛。", QUERY);
    private static final Article ARTICLE = new Article("Graduate recruitment", "J Lee", "Labour", "2025",
            "10.1234/graduate", "The study measured entry level software hiring but did not establish a causal effect.");
    private final RuntimeCalls calls = mock(RuntimeCalls.class);
    private final CrossrefEvidenceLookup lookup = mock(CrossrefEvidenceLookup.class);
    private final DiscussionBudget budget = new DiscussionBudget(CLOCK, 600, 30);

    @Test
    void checksChineseImpactClaimUsingPublicKeywordsAndOriginalExcerpt() throws Exception {
        var context = context(List.of());
        when(lookup.search(eq(QUERY), any())).thenReturn(List.of(ARTICLE));
        JsonNode evidence = MAPPER.readTree("""
                {"discussion_id":"A","insight_id":"proposal-0","inner_evidence":[],
                "outer_academic_evidence":[{"title":"Graduate recruitment","author":"J Lee","journal":"Labour",
                "year":"2025","doi":"10.1234/graduate","snippet":"did not establish a causal effect."}]}
                """);
        when(calls.invoke(eq(RuntimeTask.EVIDENCE_CHECK), anyMap(), same(budget), eq(false), any(), any()))
                .thenAnswer(invocation -> {
                    Map<String,Object> input = invocation.getArgument(1);
                    assertThat(input).containsEntry("insight_text", BID.claim()).containsEntry("candidates", List.of(ARTICLE));
                    java.util.function.Function<JsonNode, JsonNode> validate = invocation.getArgument(5);
                    return validate.apply(evidence);
                });
        assertThat(service(true).find(BID, context, budget, NOW.plusSeconds(60))).containsExactly(
                new SpeechEvidenceService.Source(ARTICLE.title(), "https://doi.org/" + ARTICLE.doi(),
                        "did not establish a causal effect."));
        ((com.fasterxml.jackson.databind.node.ObjectNode)evidence.at("/outer_academic_evidence/0"))
                .put("snippet","The paper proves all graduates lost their jobs.");
        assertThat(SpeechEvidenceService.sources(evidence, List.of(ARTICLE))).isEmpty();
    }

    @Test
    void bindsOnlyVerbatimPremisesToTheWinningClaim() {
        String premise = "工具可能改变编码任务用时";
        String claim = premise + "，这一变化能否影响应届生录用仍待验证。";
        assertThat(new Bid(null, "RAISE", null, "IMPACT", claim, QUERY, premise).verificationClaim()).isEqualTo(premise);
        assertThat(new Bid(null, "RAISE", null, "IMPACT", claim, QUERY, "所有毕业生录用率已经下降")
                .verificationClaim()).isEqualTo(claim);
    }

    @Test
    void skipsNetworkForVerifiedInRoomSourcesAndDegradesAtDeadline() throws Exception {
        var lines = List.of(new Utterance(1, "e1", "EXPERT", "需要比较同岗需求变化。", null, ""),
                new Utterance(2, "e2", "EXPERT", "我同意比较同岗需求变化。", null, ""));
        var evidence = MAPPER.readTree("""
                {"inner_evidence":[{"utterance_seq":"1","participant_id":"e1","excerpt":"同岗需求变化"},
                {"utterance_seq":"2","participant_id":"e2","excerpt":"同岗需求变化"}]}
                """);
        when(calls.invoke(any(), anyMap(), same(budget), eq(false), any(), any())).thenReturn(evidence);
        assertThat(service(true).find(BID, context(lines), budget, NOW.plusSeconds(60))).isEmpty();
        assertThat(SpeechEvidenceService.coversTwo(evidence, lines.subList(0, 1))).isFalse();
        assertThat(service(true).find(BID, context(List.of()), budget, NOW)).isEmpty();
        assertThat(service(false).find(BID, context(List.of()), budget, NOW.plusSeconds(60))).isEmpty();
        verifyNoInteractions(lookup);
        when(lookup.search(anyString(), any())).thenThrow(new TimeoutException());
        assertThat(service(true).find(BID, context(List.of()), budget, NOW.plusSeconds(60))).isEmpty();
    }

    @Test
    void prefetchRunsBeforeSpeakingAndTakeReadsTheReadyResult() throws Exception {
        when(lookup.search(eq(QUERY), any())).thenReturn(List.of(ARTICLE));
        JsonNode evidence = MAPPER.readTree("""
                {"discussion_id":"A","insight_id":"proposal-0","inner_evidence":[],
                "outer_academic_evidence":[{"title":"Graduate recruitment","author":"J Lee","journal":"Labour",
                "year":"2025","doi":"10.1234/graduate","snippet":"did not establish a causal effect."}]}
                """);
        when(calls.invoke(eq(RuntimeTask.EVIDENCE_CHECK), anyMap(), same(budget), eq(false), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Function<JsonNode, JsonNode> validate = invocation.getArgument(5);
                    return validate.apply(evidence);
                });
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        try {
            try (var research = service(true).openResearch(executor)) {
                var ready = research.prepare(BID, context(List.of()), budget, research.nextWindow());
                assertThat(ready.get(2, java.util.concurrent.TimeUnit.SECONDS).sources())
                        .containsExactly(new SpeechEvidenceService.Source(ARTICLE.title(),
                                "https://doi.org/" + ARTICLE.doi(), "did not establish a causal effect."));
                research.prepare(BID, context(List.of()), budget, research.nextWindow()).join();
                verify(lookup, times(1)).search(eq(QUERY), any());
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void ordinaryBidIsImmediatelyReadyWithoutLookup() {
        var pool = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        try (var research = service(true).openResearch(pool)) {
            var bid = new Bid(EXPERT, "RAISE", null, "TRADE_OFF", "比较延长开放时间的人力成本。");
            var ready = research.prepare(bid, context(List.of()), budget, research.nextWindow());
            assertThat(ready).isCompleted();
            assertThat(ready.join().sources()).isEmpty();
        }
        verifyNoInteractions(lookup, calls);
    }

    private SpeechEvidenceService service(boolean enabled) {
        var gateway = new PanelProperties.Gateway("real", null, null, null, null, null, 0, null, enabled, null, null);
        return new SpeechEvidenceService(new PanelProperties(null, gateway, null, null), new ContentGuard(),
                CLOCK, calls, lookup);
    }

    private DiscussionContext context(List<Utterance> lines) {
        var room = new Discussion("A", "AI 对应届计算机毕业生的影响有多大？", "EXPLORE", 4, "RUNNING", 0,
                "PENDING", null, "", "", null, null);
        return new DiscussionContext(room, List.of(), lines, List.of(), 0);
    }
}
