package com.aipanel.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

import com.aipanel.config.PanelProperties.Gateway;
import com.aipanel.service.RosterValidator;
import com.aipanel.service.debate.DiscussionWorkflow;

public final class PromptTemplates {

    private static final Pattern HEADER = Pattern.compile("(?m)^\\[([A-Z][A-Z0-9_]*)]\\h*\\R");
    private static final Map<RuntimeTask, String> FILES = Map.of(
            RuntimeTask.R01, "roles/roster.md", RuntimeTask.R02, "roles/expert-bid.md",
            RuntimeTask.R03, "roles/expert-speech.md", RuntimeTask.R04, "tasks/insights.md",
            RuntimeTask.R05, "roles/host-opening.md", RuntimeTask.R06, "roles/host-intervention.md",
            RuntimeTask.R07, "roles/host-summary.md");

    private final Map<String, String> files;
    private final Map<String, Map<String, String>> strategies;
    private final RuntimeContract contract;
    private final String version;
    private final String evidenceSkill;

    public PromptTemplates(Gateway config, RuntimeContract contract, DiscussionWorkflow workflow) throws IOException {
        this.contract = contract;
        version = config.templateVersion();
        evidenceSkill = new ClassPathResource("skills/insight-evidence-retrieval/SKILL.md")
                .getContentAsString(StandardCharsets.UTF_8);
        Map<String, String> templates = new HashMap<>();
        for (String file : List.of("common.md", "roles/host.md", "roles/host-profile.md", "roles/expert-profile.md",
                "route.md", "tasks/repair.md")) {
            templates.put(file, read(config, file));
        }
        for (String file : FILES.values()) {
            templates.put(file, read(config, file));
        }
        files = Map.copyOf(templates);
        Map<String, Map<String, String>> goals = new LinkedHashMap<>();
        for (String goal : RosterValidator.GOALS) {
            var sections = sections(read(config, "goals/" + goal.toLowerCase(Locale.ROOT) + ".md"));
            boolean allTasks = FILES.keySet().stream().allMatch(task -> sections.containsKey(task.name()));
            if (!allTasks || workflow.forGoal(goal).stream().anyMatch(phase -> !sections.containsKey(phase.id()))) {
                throw new IOException("议题策略缺少任务或阶段段落，goal=" + goal);
            }
            goals.put(goal, sections);
        }
        strategies = java.util.Collections.unmodifiableMap(goals);
    }

    public String version() {
        return version;
    }

    public String assemble(RuntimeTask task, Map<String, Object> input, boolean repair) {
        StringBuilder prompt = new StringBuilder(files.get("common.md"));
        if (task == RuntimeTask.R05 || task == RuntimeTask.R06 || task == RuntimeTask.R07) {
            append(prompt, files.get("roles/host.md"));
        }
        if (task == RuntimeTask.EVIDENCE_CHECK) {
            append(prompt, evidenceSkill);
            append(prompt, "本次仅核验输入中提供的本场实录与由工具取得的原始摘要。网络访问由应用执行。"
                    + "按技能格式回传标识。外部证据从 candidates 复制元数据，snippet 逐字摘取连续原文，"
                    + "区分研究对象、对照、观察期和因果强度。对象或条件不匹配时留空。");
            append(prompt, "输出 JSON Schema\n" + contract.schema(task));
            return prompt.toString();
        }
        append(prompt, files.get(FILES.get(task)));
        if (task == RuntimeTask.R01) {
            append(prompt, files.get("roles/host-profile.md"));
            append(prompt, files.get("roles/expert-profile.md"));
            append(prompt, files.get("route.md"));
            strategies.forEach((goal, strategy) -> append(prompt, goal + "\n" + strategy.get("R01")));
        } else {
            Map<String, String> strategy = strategies.get(input.get("discussionGoal"));
            if (strategy == null) {
                throw new ModelGatewayException(ModelGatewayException.Kind.CONTEXT_UNAVAILABLE, "未知议题策略");
            }
            append(prompt, strategy.get(task.name()));
            String phase = strategy.get(input.get("phase"));
            if (phase != null) {
                append(prompt, phase);
            }
        }
        if (repair) {
            append(prompt, files.get("tasks/repair.md"));
        }
        append(prompt, "输出 JSON Schema\n" + contract.schema(task));
        return prompt.toString();
    }

    private static void append(StringBuilder prompt, String text) {
        prompt.append("\n\n").append(text);
    }

    private static String read(Gateway config, String key) throws IOException {
        String file = config.templates().getOrDefault(key, key);
        String directory = config.templateDirectory();
        String text;
        if (directory == null || directory.isBlank()) {
            try (var stream = new ClassPathResource("prompts/" + file).getInputStream()) {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            Path root = Path.of(directory).toRealPath();
            Path path = root.resolve(file).toRealPath();
            if (!path.startsWith(root)) {
                throw new IOException("模板超出配置目录");
            }
            text = Files.readString(path, StandardCharsets.UTF_8);
        }
        if (text.isBlank()) {
            throw new IOException("模板正文为空");
        }
        return text.strip();
    }

    private static Map<String, String> sections(String text) throws IOException {
        var matcher = HEADER.matcher(text + "\n");
        Map<String, String> sections = new HashMap<>();
        String section = null;
        int start = 0;
        while (matcher.find()) {
            if (section != null && sections.putIfAbsent(section, text.substring(start, matcher.start()).strip()) != null) {
                throw new IOException("议题策略段落重复");
            }
            section = matcher.group(1);
            start = matcher.end();
        }
        if (section == null || sections.putIfAbsent(section, text.substring(Math.min(start, text.length())).strip()) != null
                || sections.values().stream().anyMatch(String::isBlank)) {
            throw new IOException("议题策略段落不完整");
        }
        return Map.copyOf(sections);
    }
}
