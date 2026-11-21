package com.aipanel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aipanel.config.FaultInjector;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RuntimeTask;
import com.aipanel.model.Insight;
import com.aipanel.model.Utterance;
import com.aipanel.repository.DiscussionRepository;
import com.aipanel.repository.InsightRepository;
import com.aipanel.service.debate.ContextSlice;
import com.aipanel.service.debate.DiscussionBudget;
import com.aipanel.service.debate.DiscussionContext;
import com.aipanel.service.debate.RuntimeCalls;
import com.aipanel.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class LiveInsightService {

    private static final Logger LOG = LoggerFactory.getLogger(LiveInsightService.class);
    private final RuntimeCalls calls;
    private final TransactionTemplate transactions;
    private final DiscussionRepository discussions;
    private final InsightRepository insights;
    private final DiscussionEventPublisher publisher;
    private final FaultInjector faults;

    public LiveInsightService(RuntimeCalls calls, TransactionTemplate transactions, DiscussionRepository discussions,
            InsightRepository insights, DiscussionEventPublisher publisher, FaultInjector faults) {
        this.calls = calls;
        this.transactions = transactions;
        this.discussions = discussions;
        this.insights = insights;
        this.publisher = publisher;
        this.faults = faults;
    }

    public void refresh(DiscussionContext context, String phase, DiscussionBudget budget) {
        List<Insight> projection;
        try {
            if (context.expertLines().stream().map(Utterance::participantId).distinct().limit(2).count() < 2) {
                projection = List.of();
            } else {
                ContextSlice slice = ContextSlice.fit(calls, RuntimeTask.R04, context, context.base(phase), null, List.of());
                projection = calls.call(RuntimeTask.R04, slice.input(), budget, true, budget.deadline(false),
                        () -> { }, json -> validate(json, slice.allowed()));
            }
        } catch (ModelGatewayException failure) {
            commit(context, null);
            return;
        }
        try {
            commit(context, projection);
        } catch (RuntimeException error) {
            LOG.warn("提炼投影事务回滚 roomId={} sourceSeq={}", context.room().id(), context.version(), error);
            commit(context, null);
        }
    }

    void commit(DiscussionContext context, List<Insight> projection) {
        String id = context.room().id();
        transactions.executeWithoutResult(transaction -> {
            if (!discussions.lockVersion(id, "RUNNING", context.version(), context.insightEventSeq())) {
                return;
            }
            boolean fresh = projection != null;
            List<Insight> visible = fresh ? projection.stream().sorted(java.util.Comparator.comparing(Insight::id))
                    .toList() : context.insights();
            Integer through = fresh ? Integer.valueOf(context.expertLines().getLast().seq())
                    : context.room().insightsThroughUtteranceSeq();
            if (fresh) {
                insights.replaceAll(id, visible, Ids.now());
            }
            String status = fresh ? "FRESH" : "STALE";
            discussions.updateInsightProjection(id, status, through);
            publisher.publish(id, "insight.updated", Map.of("insightStatus", status,
                    "insightsThroughUtteranceSeq", through == null ? 0 : through, "insights", visible));
            if (fresh) {
                faults.afterInsightProjection(id);
            }
        });
    }

    public static List<Insight> validate(JsonNode json, Map<Integer, Utterance> allowed) {
        List<Insight> result = new ArrayList<>();
        Set<String> texts = new HashSet<>();
        Map<String, Integer> totals = new HashMap<>();
        for (JsonNode item : json.path("insights")) {
            String kind = item.path("kind").asText();
            String text = item.path("text").asText().strip();
            String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                    .replaceAll("[\\s。！？]+", "").toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || !texts.add(normalized) || totals.merge(kind, 1, Integer::sum) > 3) {
                throw RuntimeCalls.invalid("LOW_QUALITY");
            }
            Set<Integer> sequences = new HashSet<>();
            Set<String> speakers = new HashSet<>();
            for (JsonNode evidence : item.path("evidence")) {
                JsonNode pointer = evidence.path("utteranceSeq");
                int seq = pointer.asInt();
                Utterance line = pointer.canConvertToInt() ? allowed.get(seq) : null;
                String quote = evidence.path("quote").asText();
                if (line == null || !"EXPERT".equals(line.kind()) || !sequences.add(seq)) {
                    throw RuntimeCalls.invalid("INVALID_EVIDENCE");
                }
                if (quote.isBlank() || !line.content().contains(quote)) {
                    throw RuntimeCalls.invalid("INVALID_QUOTE");
                }
                speakers.add(line.participantId());
            }
            if (speakers.size() < 2) {
                throw RuntimeCalls.invalid("INVALID_EVIDENCE");
            }
            result.add(new Insight(Ids.uuid(), kind, text, sequences.stream().sorted().toList()));
        }
        return List.copyOf(result);
    }
}
