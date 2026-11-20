package com.aipanel.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aipanel.config.PanelProperties;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RuntimeTask;
import com.aipanel.model.Participant;
import com.aipanel.model.Utterance;
import com.aipanel.service.debate.BidArbitrator;
import com.aipanel.service.debate.BidArbitrator.Bid;
import com.aipanel.service.debate.ContextSlice;
import com.aipanel.service.debate.DiscussionBudget;
import com.aipanel.service.debate.DiscussionContext;
import com.aipanel.service.debate.DiscussionWorkflow;
import com.aipanel.service.debate.PhasePlan;
import com.aipanel.service.debate.RuntimeCalls;
import com.aipanel.service.debate.SpeechEvidenceService;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class DiscussionOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(DiscussionOrchestrator.class);

    private final DiscussionStateService state;
    private final LiveInsightService insights;
    private final RuntimeCalls calls;
    private final SpeechEvidenceService evidence;
    private final PanelProperties properties;
    private final Clock clock;
    private final DiscussionWorkflow workflow;

    public DiscussionOrchestrator(DiscussionStateService state, LiveInsightService insights, RuntimeCalls calls,
            SpeechEvidenceService evidence, PanelProperties properties, Clock clock, DiscussionWorkflow workflow) {
        this.state = state;
        this.insights = insights;
        this.calls = calls;
        this.evidence = evidence;
        this.properties = properties;
        this.clock = clock;
        this.workflow = workflow;
    }

    public void run(String discussionId) {
        DiscussionBudget budget = new DiscussionBudget(clock);
        try {
            DiscussionContext context = state.snapshot(discussionId);
            if (!"RUNNING".equals(context.room().status())) {
                return;
            }
            open(context, budget);
            String stopReason = discuss(discussionId, workflow.forGoal(context.room().discussionGoal()), budget);
            LOG.info("进入总结 roomId={} reason={}", discussionId, stopReason);
            state.summarizing(discussionId);
            summarize(state.snapshot(discussionId), budget, stopReason);
        } catch (ModelGatewayException error) {
            String code = switch (error.kind()) {
                case CONFIG_MISSING -> "B4001";
                case TIMEOUT, STALE_RESPONSE -> "B9002";
                default -> "B9001";
            };
            LOG.warn("讨论模型任务失败 roomId={} kind={} requests={}", discussionId, error.kind(), budget.requests());
            state.fail(discussionId, code);
        } catch (RuntimeException error) {
            LOG.error("讨论事务或执行失败 roomId={}", discussionId, error);
            state.fail(discussionId, "B0005");
        } finally {
            LOG.info("讨论执行结束 roomId={} requests={}", discussionId, budget.requests());
        }
    }

    private void open(DiscussionContext context, DiscussionBudget budget) {
        Participant host = context.host();
        Map<String, Object> input = context.base("OPENING");
        input.put("host", context.role(host));
        input.put("perspectives", context.experts().stream().map(Participant::perspective).toList());
        Instant deadline = budget.deadline(false);
        String text = calls.call(RuntimeTask.R05, input, budget, false, deadline,
                () -> state.stage(context, host, "SPEAKING", null, deadline),
                output -> hostSpeech(output, 120));
        state.speak(context, host, "OPENING", text, null, deadline);
        pause();
    }

    private String discuss(String id, List<PhasePlan> phases, DiscussionBudget budget) {
        int turns = 0;
        boolean followedUp = false;
        for (int phaseIndex = 0; phaseIndex < phases.size(); phaseIndex++) {
            PhasePlan phase = phases.get(phaseIndex);
            int phaseTurns = 0;
            for (int index = 0; ; index++) {
                boolean review = index >= phase.windows() || phaseTurns >= phase.turns();
                if (review && (!Boolean.TRUE.equals(phase.reviewBeforeSummary()) || turns == 0)) {
                    break;
                }
                DiscussionContext context = state.snapshot(id);
                if (properties.orchestration().reachedTurnLimit(turns)) {
                    return "TURN_LIMIT";
                }
                if (!budget.openWindow(context.experts().size())) {
                    return "BUDGET";
                }
                List<Bid> bids;
                try {
                    boolean evidenceWindow = index == 0 && properties.gateway().evidenceEnabled();
                    bids = bids(context, phase, budget, evidenceWindow, review);
                    if (bids.isEmpty()) {
                        if (!clock.instant().isBefore(budget.deadline(false))) {
                            return "BUDGET";
                        }
                        if (review) {
                            return "REVIEW_INCOMPLETE";
                        }
                        throw new ModelGatewayException(ModelGatewayException.Kind.UPSTREAM_ERROR, "整窗报名失败");
                    }
                    var candidates = review ? bids.stream().filter(bid -> isExpertReply(bid, context)).toList() : bids;
                    var winner = BidArbitrator.select(phase, candidates, context.transcript(), evidenceWindow);
                    if (winner.isPresent()) {
                        if (speak(context, phase, winner.orElseThrow(), budget)) {
                            phaseTurns++;
                            turns++;
                            insights.refresh(state.snapshot(id), phase.id(), budget);
                        } else if (review) {
                            return "REVIEW_INCOMPLETE";
                        }
                        continue;
                    }
                    if (review) {
                        return bids.size() == context.experts().size() ? "NO_FURTHER_REPLY" : "REVIEW_INCOMPLETE";
                    }
                } finally {
                    budget.closeWindow();
                }
                if (!followedUp && index + 1 < phase.windows() && budget.canRun(context.experts().size() + 3)) {
                    followedUp = true;
                    intervene(context, phase, "FOLLOW_UP", budget);
                } else {
                    break;
                }
            }
            if (phaseIndex + 1 < phases.size() && phaseTurns > 0 && budget.canRun(1)
                    && !properties.orchestration().reachedTurnLimit(turns)) {
                intervene(state.snapshot(id), phases.get(phaseIndex + 1), "BRIDGE", budget);
            }
        }
        return "PHASES_FINISHED";
    }

    private static boolean isExpertReply(Bid bid, DiscussionContext context) {
        return ("ADD".equals(bid.intent()) || "REBUT".equals(bid.intent())) && bid.target() != null
                && context.expertLines().stream().anyMatch(line -> line.seq() == bid.target());
    }

    private List<Bid> bids(DiscussionContext context, PhasePlan phase, DiscussionBudget budget, boolean evidenceWindow,
            boolean review) {
        Map<String, Object> base = context.base(phase.id());
        base.put("evidenceWindow", evidenceWindow);
        base.put("closingReview", review);
        Utterance question = moderatorQuestion(context);
        base.put("moderatorQuestion", question.content());
        List<Participant> experts = context.experts();
        ContextSlice slice = ContextSlice.fit(calls, RuntimeTask.R02, context, base, question.seq(),
                experts.stream().map(context::role).toList());
        Instant deadline = RuntimeCalls.earlier(budget.deadline(false), clock.instant().plusSeconds(20));
        List<Supplier<Bid>> requests = experts.stream().<Supplier<Bid>>map(expert -> () -> {
            Map<String, Object> input = new HashMap<>(slice.input());
            input.put("expert", context.role(expert));
            return calls.invoke(RuntimeTask.R02, input, budget, true, deadline,
                    result -> bid(result, expert, slice.allowed()));
        }).toList();
        List<Bid> results = calls.collect(requests, deadline);
        if (results.size() < experts.size()) {
            LOG.info("报名窗口部分或全部失败 roomId={} phase={} responses={} experts={}",
                    context.room().id(), phase.id(), results.size(), experts.size());
        }
        return results;
    }

    private boolean speak(DiscussionContext context, PhasePlan phase, Bid bid, DiscussionBudget budget) {
        Instant deadline = budget.deadline(false);
        String focus = PhasePlan.focusLabel(bid.focus());
        boolean committed = false;
        try {
            state.stage(context, bid.expert(), "PREPARING", focus, deadline);
            Map<String, Object> base = context.base(phase.id());
            base.put("expert", context.role(bid.expert()));
            base.put("intent", bid.intent());
            Utterance question = moderatorQuestion(context);
            base.put("moderatorQuestion", question.content());
            base.put("replyToUtteranceSeq", bid.target());
            base.put("proposal", bid.claim());
            base.put("evidencePremise", bid.verificationClaim());
            base.put("externalEvidence", List.of());
            ContextSlice slice = ContextSlice.fit(calls, RuntimeTask.R03, context, base, question.seq(), List.of());
            Map<String, Object> input = new HashMap<>(slice.input());
            var sources = evidence.find(bid, context, budget, deadline);
            if (!sources.isEmpty()) {
                var kept = new java.util.ArrayList<SpeechEvidenceService.Source>();
                for (var source : sources) {
                    var candidate = new java.util.ArrayList<>(kept);
                    candidate.add(source);
                    input.put("externalEvidence", List.copyOf(candidate));
                    if (calls.fits(RuntimeTask.R03, input)) {
                        kept.add(source);
                    }
                }
                input.put("externalEvidence", List.copyOf(kept));
            }
            String text = calls.call(RuntimeTask.R03, input, budget, true, deadline,
                    () -> state.stage(context, bid.expert(), "SPEAKING", focus, deadline), output -> {
                        String value = speech(output, 120);
                        rejectRepeat(value, slice.allowed());
                        return value;
                    });
            state.speak(context, bid.expert(), "EXPERT", text, bid.target(), deadline);
            pause();
            committed = true;
            return true;
        } catch (ModelGatewayException error) {
            return false;
        } finally {
            if (!committed) {
                state.stage(context, bid.expert(), "IDLE", null, null);
            }
        }
    }

    private void intervene(DiscussionContext context, PhasePlan phase, String kind, DiscussionBudget budget) {
        Instant deadline = budget.deadline(false);
        Participant host = context.host();
        boolean committed = false;
        try {
            Map<String, Object> input = context.base(phase.id());
            input.put("mode", kind);
            input.put("host", context.role(host));
            ContextSlice slice = ContextSlice.fit(calls, RuntimeTask.R06, context, input,
                    moderatorQuestion(context).seq(), List.of());
            Intervention result = calls.call(RuntimeTask.R06, slice.input(), budget, false, deadline,
                    () -> state.stage(context, host, "SPEAKING", null, deadline), output -> {
                        Integer target = target(output.path("replyToUtteranceSeq"), slice.allowed());
                        if (target == null ? "BRIDGE".equals(kind) : !"EXPERT".equals(slice.allowed().get(target).kind())) {
                            throw RuntimeCalls.invalid("INVALID_TARGET");
                        }
                        return new Intervention(hostSpeech(output, 120), target);
                    });
            state.speak(context, host, kind, result.text(), result.target(), deadline);
            pause();
            committed = true;
        } catch (ModelGatewayException error) {
            LOG.info("主持介入降级 roomId={} kind={} reason={}", context.room().id(), kind, error.kind());
        } finally {
            if (!committed) {
                state.stage(context, host, "IDLE", null, null);
            }
        }
    }

    private void summarize(DiscussionContext context, DiscussionBudget budget, String stopReason) {
        Participant host = context.host();
        Instant deadline = budget.deadline(true);
        boolean limited = context.expertLines().stream().map(Utterance::participantId).distinct().count() < 2;
        Map<String, Object> input = context.base("SUMMARY");
        input.put("mode", limited ? "LIMITED" : "WITH_EXPERT_INPUT");
        input.put("stopReason", stopReason);
        input.put("host", context.role(host));
        ContextSlice slice = ContextSlice.fit(calls, RuntimeTask.R07, context, input,
                moderatorQuestion(context).seq(), List.of());
        String text = calls.call(RuntimeTask.R07, slice.input(), budget, false, deadline,
                () -> state.stage(context, host, "SPEAKING", null, deadline), output -> {
                    JsonNode references = output.path("evidenceUtteranceSeqs");
                    if ((!limited || output.path("sentences").toString().matches(".*(?:主张|认为|指出|提出).*"))
                            && references.isEmpty()) {
                        throw RuntimeCalls.invalid("INVALID_EVIDENCE");
                    }
                    for (JsonNode reference : references) {
                        Integer seq = target(reference, slice.allowed());
                        if (!"EXPERT".equals(slice.allowed().get(seq).kind())) {
                            throw RuntimeCalls.invalid("INVALID_EVIDENCE");
                        }
                    }
                    return hostSpeech(output, 200);
                });
        state.speak(context, host, "SUMMARY", text, null, deadline);
        pause();
    }

    private void pause() {
        long delay = properties.orchestration().stageDelayMillis();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static Bid bid(JsonNode json, Participant expert, Map<Integer, Utterance> allowed) {
        String intent = json.path("intent").asText();
        if ("PASS".equals(intent)) {
            return new Bid(expert, intent, null, null, null);
        }
        Integer target = target(json.path("targetUtteranceSeq"), allowed);
        Utterance referenced = target == null ? null : allowed.get(target);
        if (referenced != null && (expert.id().equals(referenced.participantId())
                || ("REBUT".equals(intent) && !"EXPERT".equals(referenced.kind())))) {
            throw RuntimeCalls.invalid("INVALID_TARGET");
        }
        String claim = json.path("proposedClaim").asText();
        rejectRepeat(claim, allowed);
        return new Bid(expert, intent, target, json.path("focusCode").asText(), claim,
                json.path("searchQuery").asText(null), json.path("searchPremise").asText(null));
    }

    private static Integer target(JsonNode node, Map<Integer, Utterance> allowed) {
        if (node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt() || !allowed.containsKey(node.asInt())) {
            throw RuntimeCalls.invalid("INVALID_TARGET");
        }
        return node.asInt();
    }

    private static String speech(JsonNode output, int limit) {
        StringBuilder text = new StringBuilder();
        for (JsonNode sentence : output.path("sentences")) {
            String value = sentence.asText().strip();
            if (value.substring(0, value.length() - 1).isBlank()) {
                throw RuntimeCalls.invalid("INVALID_SENTENCES");
            }
            text.append(value);
        }
        String value = text.toString();
        if (value.codePointCount(0, value.length()) > limit) {
            throw RuntimeCalls.invalid("INVALID_SENTENCES");
        }
        return value;
    }

    private static String hostSpeech(JsonNode output, int limit) {
        String value = speech(output, limit);
        if (value.matches(".*(?:我认为应|我建议选择|综合来看.{0,18}更优|既然.{0,16}更稳).*")) {
            throw RuntimeCalls.invalid("LOW_QUALITY");
        }
        return value;
    }

    private static void rejectRepeat(String text, Map<Integer, Utterance> allowed) {
        String normalized = compare(text);
        if (normalized.isBlank() || allowed.values().stream().anyMatch(line ->
                normalized.equals(compare(line.content())) || java.util.Arrays.stream(line.content().split("[。！？]"))
                        .anyMatch(sentence -> normalized.equals(compare(sentence))))) {
            throw RuntimeCalls.invalid("LOW_QUALITY");
        }
    }

    private static String compare(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .strip().replaceAll("[\\s。！？!?]+", "");
    }

    private static Utterance moderatorQuestion(DiscussionContext context) {
        return context.transcript().reversed().stream().filter(line -> !"EXPERT".equals(line.kind()))
                .findFirst().orElseThrow();
    }

    private record Intervention(String text, Integer target) {
    }
}
