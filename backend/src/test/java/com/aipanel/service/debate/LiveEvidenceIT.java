package com.aipanel.service.debate;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.aipanel.config.PanelProperties;
import com.aipanel.gateway.ContentGuard;
import com.aipanel.gateway.OpenAiModelGateway;
import com.aipanel.gateway.RuntimeContract;
import com.aipanel.model.Discussion;
import com.aipanel.service.debate.BidArbitrator.Bid;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式开启才访问 Crossref 和真实模型，检查中文主张能够引用英文原始摘要。 */
@EnabledIfSystemProperty(named = "panel.liveEvidence", matches = "true")
class LiveEvidenceIT {
    @Test
    void verifiesEarlyCareerHiringEvidenceWithinItsPopulation() throws Exception {
        var mapper = new ObjectMapper();
        var clock = Clock.systemUTC();
        var guard = new ContentGuard();
        var config = new PanelProperties.Gateway("real", System.getenv("PANEL_AI_BASE_URL"),
                System.getenv("PANEL_AI_MODEL"), System.getenv("PANEL_AI_API_KEY"), null, null, 32768, null, true, null,
                null);
        var properties = new PanelProperties(null, config, null, null);
        var contract = new RuntimeContract(mapper);
        var gateway = new OpenAiModelGateway(properties, contract, mapper, clock, guard, DiscussionWorkflow.load(null, mapper));
        var room = new Discussion("live-evidence-test", "AI 会如何影响初级岗位招聘？", "EXPLORE", 4,
                "RUNNING", 0, "PENDING", null, "", "", null, null);
        var bid = new Bid(null, "RAISE", null, "IMPACT",
                "美国季度劳动力指标中，AI暴露行业年轻组相对年长组的稳定招聘人数变化，主要体现在就业基数上。",
                "generative AI early career hiring counts entry rates employment base");
        var pool = new ThreadPoolTaskExecutor();
        pool.initialize();
        try {
            var calls = new RuntimeCalls(gateway, contract, guard, pool, clock, properties);
            var service = new SpeechEvidenceService(properties, guard, clock, calls,
                    new CrossrefEvidenceLookup(mapper, guard, clock));
            var sources = service.find(bid, new DiscussionContext(room, List.of(), List.of(), List.of(), 0),
                    new DiscussionBudget(clock, 600, 30), clock.instant().plusSeconds(60));
            assertThat(sources).anySatisfy(source -> {
                assertThat(source.url()).startsWith("https://doi.org/10.21203/rs.3.rs-11342200/");
                assertThat(source.excerpt()).containsIgnoringCase("base");
            });
        } finally {
            pool.shutdown();
        }
    }
}
