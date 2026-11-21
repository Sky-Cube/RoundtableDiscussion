package com.aipanel.unit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.aipanel.config.PanelProperties;
import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RuntimeContract;
import com.aipanel.gateway.RuntimeGateway;
import com.aipanel.gateway.RuntimeTask;
import com.aipanel.model.Discussion;
import com.aipanel.model.Insight;
import com.aipanel.model.Participant;
import com.aipanel.model.Utterance;
import com.aipanel.service.LiveInsightService;
import com.aipanel.service.debate.BidArbitrator;
import com.aipanel.service.debate.BidArbitrator.Bid;
import com.aipanel.service.debate.ContextSlice;
import com.aipanel.service.debate.DiscussionBudget;
import com.aipanel.service.debate.DiscussionContext;
import com.aipanel.service.debate.DiscussionWorkflow;
import com.aipanel.service.debate.PhasePlan;
import com.aipanel.service.debate.RuntimeCalls;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class DiscussionRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ranksByPhaseRegardlessOfReturnOrderAndYieldsAfterTwoTurns() throws Exception {
        Bid tradeOff = new Bid(expert("e1"), "ADD", 1, "TRADE_OFF", "合成比较依据。");
        Bid evidence = new Bid(expert("e2"), "ADD", 1, "EVIDENCE", "合成查证依据。");
        List<PhasePlan> phases = DiscussionWorkflow.load(null, mapper).forGoal("EVALUATE");
        for (var arrival : List.of(List.of(tradeOff, evidence), List.of(evidence, tradeOff))) {
            assertThat(BidArbitrator.select(phases.getFirst(), arrival, List.of())).contains(tradeOff);
            assertThat(BidArbitrator.select(phases.get(1), arrival, List.of())).contains(evidence);
            assertThat(BidArbitrator.select(phases.getFirst(), arrival,
                    List.of(line(2, "e1", null), line(3, "e1", null)))).contains(evidence);
        }
        assertThat(BidArbitrator.select(phases.getFirst(), List.of(tradeOff),
                List.of(line(2, "e1", null), line(3, "e1", null)))).contains(tradeOff);
    }

    @Test
    void ranksResearchAndOrdinaryBidsByTheSameSpeakingRules() throws Exception {
        var phase = DiscussionWorkflow.load(null, mapper).forGoal("EVALUATE").getFirst();
        Bid question = new Bid(expert("e1"), "REBUT", 1, "TRADE_OFF", "比较岗位录用率。");
        Bid empirical = new Bid(expert("e2"), "RAISE", null, "IMPACT", "比较应届生机会。", "graduate hiring");
        var bids = List.of(question, empirical);
        assertThat(BidArbitrator.select(phase, bids.reversed(), List.of())).contains(question);
        assertThat(BidArbitrator.select(phase, bids, List.of())).contains(question);
        assertThat(BidArbitrator.select(phase, bids, List.of(line(2, "e2", null), line(3, "e2", null))))
                .contains(question);
    }

    @Test
    void appliesTimeoutDefaultsAndKeepsExplicitOverrides() {
        var defaults = new PanelProperties(null, null, null, null).gateway().timeouts();
        assertThat(defaults.rosterSeconds()).isEqualTo(50);
        assertThat(defaults.utteranceSeconds()).isEqualTo(35);
        assertThat(defaults.insightSeconds()).isEqualTo(45);
        assertThat(defaults.summarySeconds()).isEqualTo(30);
        assertThat(defaults.discussionSeconds()).isEqualTo(600);
        assertThat(defaults.evidenceTimeoutMillis()).isEqualTo(12000);

        // 只覆盖部分项时，非正数继续使用默认值，避免手工构造配置漏项。
        var custom = new PanelProperties.Timeouts(80, 0, 60, -1, 900, 0);
        assertThat(custom.rosterSeconds()).isEqualTo(80);
        assertThat(custom.utteranceSeconds()).isEqualTo(35);
        assertThat(custom.insightSeconds()).isEqualTo(60);
        assertThat(custom.summarySeconds()).isEqualTo(30);
        assertThat(custom.discussionSeconds()).isEqualTo(900);
        // 查证时限设为 0 即关闭查证。
        assertThat(custom.evidenceTimeoutMillis()).isZero();
    }

    @Test
    void protectsCallAndTimeBudgetsForSummary() {
        MutableClock clock = new MutableClock();
        var budget = new DiscussionBudget(clock, 600, 30);
        assertThat(budget.openWindow(6)).isTrue();
        for (int number = 0; number < 102; number++) {
            assertThat(budget.acquire(false, false)).isTrue();
        }
        assertThat(budget.acquire(false, false)).isFalse();
        for (int number = 0; number < 8; number++) {
            assertThat(budget.acquire(false, true)).isTrue();
        }
        budget.closeWindow();
        clock.move(570);
        assertThat(budget.openWindow(2)).isFalse();
        assertThat(budget.acquire(true, false)).isTrue();
        assertThat(budget.acquire(true, false)).isTrue();
        assertThat(budget.acquire(true, false)).isFalse();
        assertThat(budget.requests()).isEqualTo(112);
    }

    @Test
    void discardsLateResponsesBeforeValidationAndKeepsSummaryBudget() throws Exception {
        MutableClock clock = new MutableClock();
        DiscussionBudget budget = new DiscussionBudget(clock, 600, 30);
        RuntimeGateway gateway = (task, input, codes, deadline) -> {
            clock.move(571);
            return "{\"sentences\":[\"合成过期台词。\"]}";
        };
        RuntimeCalls calls = new RuntimeCalls(gateway, new RuntimeContract(mapper), new ContentGuard(),
                new ThreadPoolTaskExecutor(), clock, new PanelProperties(null, null, null, null));
        assertThatExceptionOfType(ModelGatewayException.class)
                .isThrownBy(() -> calls.invoke(RuntimeTask.R05, Map.of(), budget, false, budget.deadline(false), json -> {
                    throw new AssertionError("过期回包不应进入业务校验");
                }))
                .satisfies(error -> assertThat(error.kind()).isEqualTo(ModelGatewayException.Kind.STALE_RESPONSE));
        assertThat(budget.requests()).isEqualTo(1);
        assertThat(budget.acquire(true, false)).isTrue();
    }

    @Test
    void isolatesUnexpectedExceptionFromOnStartIntoCurrentCall() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.initialize();
        try {
            RuntimeCalls calls = new RuntimeCalls((task, input, codes, deadline) -> {
                throw new AssertionError("阶段写入失败不应进入模型调用");
            }, new RuntimeContract(mapper), new ContentGuard(), executor, Clock.systemUTC(),
                    new PanelProperties(null, null, null, null));
            DiscussionBudget budget = new DiscussionBudget(Clock.systemUTC(), 600, 30);
            assertThatExceptionOfType(ModelGatewayException.class)
                    .isThrownBy(() -> calls.call(RuntimeTask.R05, Map.of(), budget, false,
                            Instant.now().plusSeconds(5), () -> {
                                throw new IllegalStateException("合成阶段写入失败");
                            }, json -> "ok"))
                    .satisfies(error -> {
                        assertThat(error.kind()).isEqualTo(ModelGatewayException.Kind.UPSTREAM_ERROR);
                        assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
                    });
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void preservesRequiredReplyChainAndRejectsQuotesOutsideTheSlice() throws Exception {
        RuntimeGateway measured = new RuntimeGateway() {
            @Override
            public String generate(RuntimeTask task, Map<String, Object> input, List<String> codes, Instant deadline) {
                throw new AssertionError("引用裁剪无需调用模型");
            }

            @Override
            public int inputLimit(RuntimeTask task) {
                return 4;
            }

            @Override
            public int estimate(RuntimeTask task, Map<String, Object> input) {
                return ((List<?>) input.get("utterances")).size();
            }
        };
        RuntimeCalls calls = new RuntimeCalls(measured, new RuntimeContract(mapper), new ContentGuard(),
                new ThreadPoolTaskExecutor(), Clock.systemUTC(), new PanelProperties(null, null, null, null));
        Discussion room = new Discussion("A", "合成议题", "EVALUATE", 2, "RUNNING", 0, "PENDING", null,
                "2026-11-20T00:00:00Z", "2026-11-20T00:00:00Z", null, null);
        var transcript = List.of(host(1), line(2, "e1", null), line(3, "e2", null),
                line(4, "e2", null), host(5), line(6, "e1", 4));
        var context = new DiscussionContext(room, List.of(), transcript,
                List.of(new Insight("old-id", "CONSENSUS", "需要保留旧证据", List.of(1, 2))), 0);
        var base = context.base("COMPARE_CRITERIA");
        base.put("replyToUtteranceSeq", 2);
        var slice = ContextSlice.fit(calls, RuntimeTask.R03, context, base, 5, List.of());
        assertThat(slice.allowed().keySet()).containsExactlyInAnyOrder(2, 4, 5, 6);
        assertThat((List<?>) slice.input().get("insights")).isEmpty();
        assertThatExceptionOfType(ModelGatewayException.class)
                .isThrownBy(() -> LiveInsightService.validate(insight(2, 3, "阶段验收"), slice.allowed()))
                .withMessage("INVALID_EVIDENCE");
    }

    @Test
    void validatesExactQuoteAndTwoDifferentExperts() throws Exception {
        Map<Integer, Utterance> available = Map.of(2, line(2, "e1", null), 4, line(4, "e1", null),
                6, line(6, "e2", null));
        assertThat(LiveInsightService.validate(insight(2, 6, "阶段验收"), available)).hasSize(1);
        assertThatExceptionOfType(ModelGatewayException.class)
                .isThrownBy(() -> LiveInsightService.validate(insight(2, 4, "阶段验收"), available))
                .withMessage("INVALID_EVIDENCE");
        assertThatExceptionOfType(ModelGatewayException.class)
                .isThrownBy(() -> LiveInsightService.validate(insight(2, 6, "未知证据"), available))
                .withMessage("INVALID_QUOTE");
    }

    private com.fasterxml.jackson.databind.JsonNode insight(int first, int second, String quote) throws Exception {
        return mapper.readTree("""
                {"insights":[{"kind":"CONSENSUS","text":"试点需要阶段验收","evidence":[
                {"utteranceSeq":%d,"quote":"%s"},{"utteranceSeq":%d,"quote":"%s"}]}]}
                """.formatted(first, quote, second, quote));
    }

    private static Participant expert(String id) {
        return new Participant("A", id, "EXPERT", id, "测试职业", "测试职称", "合成测试立场", "EVIDENCE",
                "blue", "IDLE", null);
    }

    private static Utterance line(int sequence, String author, Integer reply) {
        return new Utterance(sequence, author, "EXPERT", "建议保留阶段验收。", reply, "2026-11-20T00:00:00Z");
    }

    private static Utterance host(int seq) {
        return new Utterance(seq, "host", "OPENING", "请补充具体条件。", null, "2026-11-20T00:00:00Z");
    }

    private static final class MutableClock extends Clock {

        private static final Instant START = Instant.parse("2026-11-20T00:00:00Z");
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        void move(long seconds) {
            now.set(START.plusSeconds(seconds));
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
