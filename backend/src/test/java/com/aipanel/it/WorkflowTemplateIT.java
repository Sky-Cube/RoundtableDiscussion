package com.aipanel.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.aipanel.gateway.RuntimeTask;
import com.aipanel.service.debate.ScriptedRuntimeGateway;
import com.aipanel.support.AbstractPanelIntegrationTest;
import com.aipanel.support.RosterFixtures;
import com.aipanel.support.StubModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class WorkflowTemplateIT extends AbstractPanelIntegrationTest {

    @MockitoSpyBean
    private ScriptedRuntimeGateway runtime;

    @DynamicPropertySource
    static void workflowProperties(DynamicPropertyRegistry registry) throws IOException {
        Path directory = Files.createDirectory(TEST_ROOT.resolve("custom-workflow"));
        var mapper = new ObjectMapper();
        try (var source = new ClassPathResource("prompts/workflow.json").getInputStream()) {
            ObjectNode workflow = (ObjectNode) mapper.readTree(source);
            var phases = workflow.putArray("EVALUATE");
            phases.addObject().put("phase", "CHALLENGE_CONDITIONS").put("maxExpertTurns", 1).put("maxWindows", 1)
                    .putArray("preferredFocusCodes").add("EVIDENCE");
            mapper.writeValue(directory.resolve("workflow.json").toFile(), workflow);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        registry.add("panel.gateway.template-directory", directory::toString);
    }

    @Test
    void runsConfiguredPhaseAndRepairsBidAgainstHost() throws Exception {
        var turns = new ConcurrentLinkedQueue<String>();
        var repaired = new ConcurrentLinkedQueue<String>();
        doAnswer(invocation -> {
            RuntimeTask task = invocation.getArgument(0);
            Map<String, Object> input = invocation.getArgument(1);
            if (task == RuntimeTask.R02) {
                java.util.List<String> codes = invocation.getArgument(2);
                if (codes.isEmpty()) {
                    return """
                            {"intent":"REBUT","targetUtteranceSeq":1,"focusCode":"EVIDENCE",
                             "proposedClaim":"需要明确检验条件。"}
                            """;
                }
                repaired.addAll(codes);
            }
            if (task == RuntimeTask.R03) {
                turns.add(input.get("phase").toString());
            }
            return invocation.callRealMethod();
        }).when(runtime).generate(any(), anyMap(), anyList(), any(Instant.class));
        String topic = "流程模板独立执行";
        gateway.script(topic, StubModelGateway.Step.json(RosterFixtures.valid("EVALUATE", 2)));
        var created = createDraft(createBody(topic, 2));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String id = dataOf(created).path("id").asText();
        assertThat(mockMvc.perform(post("/api/v1/discussions/" + id + "/start")).andReturn()
                .getResponse().getStatus()).isEqualTo(202);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            var snapshot = getData("/api/v1/discussions/" + id);
            String status = snapshot.path("status").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                assertThat(status).isEqualTo("COMPLETED");
                assertThat(turns).containsExactly("CHALLENGE_CONDITIONS");
                assertThat(repaired).containsExactly("INVALID_TARGET", "INVALID_TARGET");
                assertThat(snapshot.path("transcript").valueStream().map(line -> line.path("kind").asText()))
                        .containsExactly("OPENING", "EXPERT", "SUMMARY");
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("未在测试时限内完成使用自定义流程的讨论");
    }
}
