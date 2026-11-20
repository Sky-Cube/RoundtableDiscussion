package com.aipanel.service.debate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

import com.aipanel.service.RosterValidator;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 同一份阶段配置供 Prompt 装配和房间调度使用。 */
public final class DiscussionWorkflow {

    private static final String FILE = "workflow.json";
    private static final int MAX_EXPERT_TURNS = 10;
    private static final int MAX_PLANNED_WINDOWS = 14;
    private static final Pattern PHASE_ID = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Set<String> RESERVED = Set.of("OPENING", "SUMMARY", "R01", "R02", "R03", "R04",
            "R05", "R06", "R07", "R08");

    private final Map<String, List<PhasePlan>> plans;

    private DiscussionWorkflow(Map<String, List<PhasePlan>> plans) {
        this.plans = Map.copyOf(plans);
    }

    public static DiscussionWorkflow load(String templateDirectory, ObjectMapper mapper) throws IOException {
        var reader = mapper.readerFor(new TypeReference<LinkedHashMap<String, List<PhasePlan>>>() { })
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (InputStream source = open(templateDirectory)) {
            Map<String, List<PhasePlan>> input = reader.readValue(source);
            if (input == null || !input.keySet().equals(Set.copyOf(RosterValidator.GOALS))) {
                throw new IOException("流程模板必须定义四种讨论目标");
            }
            for (String goal : RosterValidator.GOALS) {
                List<PhasePlan> phases = input.get(goal);
                validate(goal, phases);
                input.put(goal, List.copyOf(phases));
            }
            return new DiscussionWorkflow(input);
        }
    }

    public List<PhasePlan> forGoal(String goal) {
        List<PhasePlan> phases = plans.get(goal);
        if (phases == null) {
            throw new IllegalArgumentException("未知讨论目标");
        }
        return phases;
    }

    private static InputStream open(String templateDirectory) throws IOException {
        if (templateDirectory != null && !templateDirectory.isBlank()) {
            Path directory = Path.of(templateDirectory);
            Path candidate = directory.resolve(FILE);
            if (!Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                Path path = candidate.toRealPath();
                if (!path.startsWith(directory.toRealPath())) {
                    throw new IOException("流程模板超出配置目录");
                }
                return Files.newInputStream(path);
            }
        }
        return new ClassPathResource("prompts/" + FILE).getInputStream();
    }

    private static void validate(String goal, List<PhasePlan> phases) throws IOException {
        if (phases == null || phases.isEmpty() || phases.size() > MAX_EXPERT_TURNS
                || phases.stream().anyMatch(DiscussionWorkflow::invalidPhase)
                || phases.stream().map(PhasePlan::id).distinct().count() != phases.size()
                || phases.stream().mapToInt(PhasePlan::turns).sum() > MAX_EXPERT_TURNS
                || phases.stream().mapToInt(PhasePlan::windows).sum() > MAX_PLANNED_WINDOWS
                || phases.stream().limit(phases.size() - 1L).anyMatch(p -> Boolean.TRUE.equals(p.reviewBeforeSummary()))) {
            throw new IOException("流程模板包含非法阶段或超出全场配额，goal=" + goal);
        }
    }

    private static boolean invalidPhase(PhasePlan phase) {
        return phase == null || phase.id() == null || !PHASE_ID.matcher(phase.id()).matches()
                || RESERVED.contains(phase.id()) || phase.turns() < 1 || phase.turns() > MAX_EXPERT_TURNS
                || phase.windows() < phase.turns() || phase.windows() > MAX_PLANNED_WINDOWS
                || phase.focus().isEmpty() || phase.focus().stream().anyMatch(code -> PhasePlan.focusLabel(code) == null)
                || Set.copyOf(phase.focus()).size() != phase.focus().size();
    }
}
