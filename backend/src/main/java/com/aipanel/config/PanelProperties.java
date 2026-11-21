package com.aipanel.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 面板运行配置。
 *
 * @param db            数据库路径、允许目录与初始化脚本
 * @param gateway       模型网关连接、模板与查证配置
 * @param request       回环 Host 与写请求来源端口白名单
 * @param orchestration 讨论执行默认值：并发容量、专家发言上限与相邻发言间隔
 */
@ConfigurationProperties("panel")
public record PanelProperties(Database db, Gateway gateway, Request request, Orchestration orchestration) {

    public PanelProperties {
        db = db == null ? new Database("data/panel.db", "data", "classpath:db/init.sql", true) : db;
        gateway = gateway == null
                ? new Gateway(null, null, null, null, null, null, 0, null, false, null, null)
                : gateway;
        request = request == null ? new Request(List.of("127.0.0.1", "localhost", "::1"), List.of(5173, 5183, 8080))
                : request;
        orchestration = orchestration == null ? new Orchestration(2, 0, 0) : orchestration;
    }


    public record Database(String path, String allowedRoot, String initScript, boolean initialize) {

        public Database {
            path = blankTo(path, "data/panel.db");
            allowedRoot = blankTo(allowedRoot, "data");
            initScript = blankTo(initScript, "classpath:db/init.sql");
        }
    }

    public record Gateway(String mode, String baseUrl, String model, String apiKey, String templateDirectory,
            String templateVersion, int contextWindow, Map<String, String> templates, boolean evidenceEnabled,
            Timeouts timeouts, String reasoningEffort) {

        public Gateway {
            mode = blankTo(mode, "real");
            templateVersion = blankTo(templateVersion, "v2.8");
            contextWindow = contextWindow <= 0 ? 32768 : contextWindow;
            templates = templates == null ? Map.of() : Map.copyOf(templates);
            timeouts = timeouts == null ? Timeouts.DEFAULT : timeouts;
            reasoningEffort = blankTo(reasoningEffort, "low");
        }

        public boolean scripted() {
            return "scripted".equalsIgnoreCase(mode);
        }

        @Override
        public String toString() {
            return "Gateway[mode=" + mode + ", templateVersion=" + templateVersion + "]";
        }
    }

    /**
     * 模型调用超时预算（秒）。非正数按默认值处理，便于只覆盖其中一项。
     *
     * @param rosterSeconds     R01 阵容生成，输出较长，默认 50 秒
     * @param utteranceSeconds  R02/R03/R05/R06 短发言，默认 35 秒
     * @param insightSeconds    R04 实时提炼，需核对全部实录，默认 45 秒
     * @param summarySeconds    R07 主持收尾，默认 30 秒
     * @param discussionSeconds 单场讨论总预算，默认 600 秒
     * @param evidenceTimeoutMillis 后台查证总时限，默认 12000 毫秒，0 表示关闭
     */
    public record Timeouts(int rosterSeconds, int utteranceSeconds, int insightSeconds, int summarySeconds,
            int discussionSeconds, int evidenceTimeoutMillis) {

        public static final Timeouts DEFAULT = new Timeouts(0, 0, 0, 0, 0, -1);

        public Timeouts {
            rosterSeconds = positiveTo(rosterSeconds, 50);
            utteranceSeconds = positiveTo(utteranceSeconds, 35);
            insightSeconds = positiveTo(insightSeconds, 45);
            summarySeconds = positiveTo(summarySeconds, 30);
            discussionSeconds = positiveTo(discussionSeconds, 600);
            // 允许显式设为 0，表示关闭外部查证。
            evidenceTimeoutMillis = evidenceTimeoutMillis < 0 ? 12000 : evidenceTimeoutMillis;
        }
    }

    public record Request(List<String> allowedHosts, List<Integer> allowedOriginPorts) {

        public Request {
            allowedHosts = allowedHosts == null || allowedHosts.isEmpty() ? List.of("127.0.0.1", "localhost", "::1")
                    : List.copyOf(allowedHosts);
            allowedOriginPorts = allowedOriginPorts == null || allowedOriginPorts.isEmpty() ? List.of(5173, 8080)
                    : List.copyOf(allowedOriginPorts);
        }
    }

    public record Orchestration(int capacity, int maxExpertUtterances, int stageDelayMillis) {

        public Orchestration {
            capacity = capacity < 1 ? 1 : capacity;
            if (maxExpertUtterances < 0) {
                throw new IllegalArgumentException("专家发言上限须为非负整数，0 表示按讨论进展收尾");
            }
            stageDelayMillis = Math.max(0, stageDelayMillis);
        }

        public boolean reachedTurnLimit(int turns) {
            return maxExpertUtterances > 0 && turns >= maxExpertUtterances;
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int positiveTo(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }
}
