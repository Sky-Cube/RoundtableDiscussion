package com.aipanel.service.debate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.aipanel.config.PanelProperties;
import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.ModelGatewayException;
import com.aipanel.gateway.RuntimeTask;
import com.aipanel.model.Utterance;
import com.aipanel.service.debate.BidArbitrator.Bid;
import com.aipanel.service.debate.CrossrefEvidenceLookup.Article;
import com.fasterxml.jackson.databind.JsonNode;

/** 专家待机时核验复杂事实或前沿研究，仅将已核实的材料交给本轮候选。 */
@Service
public class SpeechEvidenceService {
    private static final Logger LOG = LoggerFactory.getLogger(SpeechEvidenceService.class);
    private static final Pattern KEYWORDS = Pattern.compile("[a-zA-Z][a-zA-Z-]{0,29}( [a-zA-Z][a-zA-Z-]{0,29}){1,13}");
    private final boolean enabled;
    private final ContentGuard guard;
    private final Clock clock;
    private final RuntimeCalls calls;
    private final CrossrefEvidenceLookup lookup;
    private final int timeoutMillis;

    public SpeechEvidenceService(PanelProperties properties, ContentGuard guard, Clock clock,
            RuntimeCalls calls, CrossrefEvidenceLookup lookup) {
        this.enabled = properties.gateway().evidenceEnabled();
        this.guard = guard;
        this.clock = clock;
        this.calls = calls;
        this.lookup = lookup;
        this.timeoutMillis = properties.gateway().timeouts().evidenceTimeoutMillis();
    }

    static boolean searchable(Bid bid) {
        String query = bid.searchQuery();
        return query != null && query.length() <= 100 && KEYWORDS.matcher(query).matches()
                && !"PASS".equals(bid.intent());
    }

    public record ReadyBid(Bid bid, List<Source> sources) { }

    public Research openResearch(ThreadPoolTaskExecutor executor) {
        return new Research(executor);
    }

    /** 一场讨论独占，跨窗口仅复用同一专家、同一前提和检索词的核验材料。 */
    public final class Research implements AutoCloseable {
        private final ThreadPoolTaskExecutor executor;
        private final Map<String, Job> jobs = new HashMap<>();
        private long window;
        private boolean closed;

        private Research(ThreadPoolTaskExecutor executor) {
            this.executor = executor;
        }

        public synchronized long nextWindow() {
            return ++window;
        }

        public synchronized String premise(String expertId) {
            Job job = jobs.get(expertId);
            return job == null ? null : job.premise();
        }

        public synchronized CompletableFuture<ReadyBid> prepare(Bid bid, DiscussionContext context,
                DiscussionBudget budget, long generation) {
            if (closed || generation != window || Thread.currentThread().isInterrupted()) {
                return CompletableFuture.failedFuture(ModelGatewayException.timeout());
            }
            if (!enabled || timeoutMillis == 0 || !searchable(bid)) {
                return CompletableFuture.completedFuture(new ReadyBid(bid, List.of()));
            }
            String expertId = bid.expert().id();
            Job previous = jobs.get(expertId);
            if (previous != null && previous.premise().equals(bid.verificationClaim())
                    && previous.query().equals(bid.searchQuery())) {
                return previous.result().thenApply(sources -> new ReadyBid(bid, sources));
            }
            if (previous != null) {
                previous.cancel();
            }
            var result = new CompletableFuture<List<Source>>();
            // 排队时间也计入预算。核验直接使用独立线程池，不再嵌套提交到报名池。
            Instant end = RuntimeCalls.earlier(budget.deadline(false), clock.instant().plusMillis(timeoutMillis));
            try {
                Future<?> task = executor.submit(() -> {
                    try {
                        result.complete(find(bid, context, budget, end));
                    } catch (RuntimeException error) {
                        LOG.warn("后台查证降级 roomId={} reason={}", context.room().id(), error.getClass().getSimpleName());
                        result.complete(List.of());
                    }
                });
                jobs.put(expertId, new Job(bid.verificationClaim(), bid.searchQuery(), result, task));
            } catch (TaskRejectedException busy) {
                jobs.remove(expertId);
                result.complete(List.of());
            }
            return result.completeOnTimeout(List.of(), Math.max(0L, java.time.Duration.between(clock.instant(), end).toMillis()),
                    java.util.concurrent.TimeUnit.MILLISECONDS).thenApply(sources -> new ReadyBid(bid, sources));
        }

        @Override
        public synchronized void close() {
            closed = true;
            jobs.values().forEach(Job::cancel);
            jobs.clear();
        }
    }

    private record Job(String premise, String query, CompletableFuture<List<Source>> result, Future<?> task) {
        void cancel() {
            task.cancel(true);
            result.complete(List.of());
        }
    }

    public List<Source> find(Bid bid, DiscussionContext context, DiscussionBudget budget, Instant deadline) {
        String query = bid.searchQuery();
        if (!enabled || !searchable(bid) || !guard.safeContext(query).equals(query)) {
            return List.of();
        }
        Instant end = RuntimeCalls.earlier(deadline, budget.deadline(false));
        if (!clock.instant().isBefore(end) || !budget.canRun(1)) {
            return List.of();
        }
        List<Utterance> lines = context.expertLines();
        String room = context.room().id();
        String claimId = "proposal-" + context.version();
        try {
            if (lines.stream().map(Utterance::participantId).distinct().limit(2).count() == 2) {
                var inner = check(bid, context, budget, claimId, List.of(), end);
                if (coversTwo(inner, lines)) {
                    LOG.info("专家查证 roomId={} result=INNER_SUFFICIENT", room);
                    return List.of();
                }
            }
            if (!budget.canRun(1) || !clock.instant().isBefore(end)) {
                return List.of();
            }
            var candidates = lookup.search(query, RuntimeCalls.earlier(end, clock.instant().plusSeconds(8)));
            if (candidates.isEmpty()) {
                LOG.info("专家查证 roomId={} result=NO_CANDIDATES", room);
                return List.of();
            }
            JsonNode verified = check(bid, context, budget, claimId, candidates, end);
            var sources = coversTwo(verified, lines) ? List.<Source>of() : sources(verified, candidates);
            LOG.info("专家查证 roomId={} candidates={} selected={} accepted={}", room, candidates.size(),
                    verified.path("outer_academic_evidence").size(), sources.size());
            return sources;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | TimeoutException | ModelGatewayException error) {
            LOG.info("专家查证暂缓 roomId={} reason={}", room, error.getClass().getSimpleName());
        }
        return List.of();
    }

    private JsonNode check(Bid bid, DiscussionContext context, DiscussionBudget budget, String claimId,
            List<Article> candidates, Instant end) {
        String room = context.room().id();
        Map<String, Object> input = Map.of("discussion_id", room, "insight_id", claimId,
                "insight_text", bid.verificationClaim(),
                "utterances", context.transcript().stream().map(context::line).toList(), "candidates", candidates);
        return calls.invoke(RuntimeTask.EVIDENCE_CHECK, input, budget, false, end, result -> {
            if (!room.equals(result.path("discussion_id").asText())
                    || !claimId.equals(result.path("insight_id").asText())) {
                throw RuntimeCalls.invalid("INVALID_TARGET");
            }
            return result;
        });
    }

    static boolean coversTwo(JsonNode result, List<Utterance> lines) {
        var speakers = new HashSet<String>();
        for (var quote : result.path("inner_evidence")) {
            String seq = quote.path("utterance_seq").asText();
            String id = quote.path("participant_id").asText();
            String excerpt = quote.path("excerpt").asText();
            if (!excerpt.isBlank() && lines.stream().anyMatch(line -> Integer.toString(line.seq()).equals(seq)
                    && "EXPERT".equals(line.kind()) && id.equals(line.participantId())
                    && line.content().contains(excerpt))) {
                speakers.add(id);
            }
        }
        return speakers.size() >= 2;
    }

    static List<Source> sources(JsonNode result, List<Article> candidates) {
        List<Source> accepted = new ArrayList<>();
        var seen = new HashSet<String>();
        for (var evidence : result.path("outer_academic_evidence")) {
            String snippet = evidence.path("snippet").asText().strip();
            for (var article : candidates) {
                if (article.doi().equals(evidence.path("doi").asText())
                        && article.title().equals(evidence.path("title").asText())
                        && article.author().equals(evidence.path("author").asText())
                        && article.year().equals(evidence.path("year").asText())
                        && article.journal().equals(evidence.path("journal").asText()) && snippet.length() >= 12
                        && article.abstractText().contains(snippet) && seen.add(article.doi())) {
                    accepted.add(new Source(article.title(), "https://doi.org/" + article.doi(), snippet));
                }
            }
        }
        return List.copyOf(accepted);
    }

    public record Source(String title, String url, String excerpt) {
    }
}
