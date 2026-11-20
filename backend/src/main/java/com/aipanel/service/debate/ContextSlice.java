package com.aipanel.service.debate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RuntimeTask;
import com.aipanel.model.Insight;
import com.aipanel.model.Utterance;

public record ContextSlice(Map<String, Object> input, Map<Integer, Utterance> allowed) {

    public static ContextSlice fit(RuntimeCalls calls, RuntimeTask task, DiscussionContext context,
            Map<String, Object> base, Integer target, List<Map<String, Object>> roles) {
        String insightKey = task == RuntimeTask.R04 ? "previousInsights" : "insights";
        Set<Integer> required = new java.util.HashSet<>();
        List<Utterance> experts = context.expertLines();
        if (task == RuntimeTask.R04 || task == RuntimeTask.R07) {
            experts.forEach(line -> required.add(line.seq()));
        } else {
            experts.reversed().stream().limit(2).forEach(line -> required.add(line.seq()));
        }
        if (target != null) {
            required.add(target);
        }
        if (base.get("replyToUtteranceSeq") instanceof Integer reply) {
            required.add(reply);
        }

        Map<Integer, Utterance> transcript = new HashMap<>();
        context.transcript().forEach(line -> transcript.put(line.seq(), line));
        TreeMap<Integer, Utterance> kept = new TreeMap<>();
        for (int seq : required) {
            addChain(seq, transcript, kept);
        }
        List<Insight> insights = new ArrayList<>();
        if (!fits(calls, task, materialize(context, base, insightKey, kept, insights), roles)) {
            throw new ModelGatewayException(ModelGatewayException.Kind.CONTEXT_UNAVAILABLE, "必留实录超限");
        }
        List<Insight> ordered = context.insights().stream().sorted(
                Comparator.<Insight>comparingInt(insight -> insight.evidenceUtteranceSeqs().stream()
                        .mapToInt(Integer::intValue).max().orElse(0)).reversed().thenComparing(Insight::id)).toList();
        for (Insight insight : ordered) {
            if (!transcript.keySet().containsAll(insight.evidenceUtteranceSeqs())) {
                continue;
            }
            var candidate = new TreeMap<>(kept);
            var nextInsights = new ArrayList<>(insights);
            insight.evidenceUtteranceSeqs().forEach(seq -> addChain(seq, transcript, candidate));
            nextInsights.add(insight);
            if (fits(calls, task, materialize(context, base, insightKey, candidate, nextInsights), roles)) {
                kept = candidate;
                insights = nextInsights;
            }
        }
        for (Utterance line : context.transcript().reversed()) {
            var candidate = new TreeMap<>(kept);
            try {
                addChain(line.seq(), transcript, candidate);
            } catch (ModelGatewayException invalidChain) {
                continue;
            }
            if (fits(calls, task, materialize(context, base, insightKey, candidate, insights), roles)) {
                kept = candidate;
            }
        }
        return new ContextSlice(materialize(context, base, insightKey, kept, insights), Map.copyOf(kept));
    }

    private static boolean fits(RuntimeCalls calls, RuntimeTask task, Map<String, Object> input,
            List<Map<String, Object>> roles) {
        if (roles.isEmpty()) {
            return calls.fits(task, input);
        }
        return roles.stream().allMatch(role -> {
            Map<String, Object> candidate = new HashMap<>(input);
            candidate.put("expert", role);
            return calls.fits(task, candidate);
        });
    }

    private static Map<String, Object> materialize(DiscussionContext context, Map<String, Object> base,
            String insightKey, Map<Integer, Utterance> kept, List<Insight> insights) {
        Map<String, Object> input = new HashMap<>(base);
        input.put("utterances", kept.values().stream().map(context::line).toList());
        input.put(insightKey, insights.stream()
                .map(insight -> Map.of("kind", insight.kind(), "text", insight.text(),
                        "evidenceUtteranceSeqs", insight.evidenceUtteranceSeqs())).toList());
        context.projection(input);
        return java.util.Collections.unmodifiableMap(input);
    }

    private static void addChain(int seq, Map<Integer, Utterance> transcript, Map<Integer, Utterance> kept) {
        Integer next = seq;
        while (next != null && !kept.containsKey(next)) {
            Utterance line = transcript.get(next);
            if (line == null || (line.replyToUtteranceSeq() != null && line.replyToUtteranceSeq() >= next)) {
                throw new ModelGatewayException(ModelGatewayException.Kind.CONTEXT_UNAVAILABLE, "实录引用链无效");
            }
            kept.put(next, line);
            next = line.replyToUtteranceSeq();
        }
    }
}
