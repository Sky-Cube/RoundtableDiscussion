package com.aipanel.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：真实 Spring 上下文 + 本轮新建的临时 SQLite 文件库。
 *
 * <p>数据库路径通过动态属性注入，默认业务配置（data/panel.db）永远不会被打开。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractPanelIntegrationTest {

    protected static final Path TEST_ROOT = TestDirectories.create("p05");
    protected static final Path TEST_DATABASE = TEST_ROOT.resolve("panel-test.db");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> TestDirectories.deleteRecursively(TEST_ROOT)));
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("panel.db.path", TEST_DATABASE::toString);
        registry.add("panel.db.allowed-root", TEST_ROOT::toString);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected StubModelGateway gateway;

    @Autowired
    protected TestFaultInjector faultInjector;

    @BeforeEach
    void resetTestDouble() {
        gateway.reset();
        faultInjector.reset();
    }

    protected JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    protected JsonNode errorOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected JsonNode getData(String path) throws Exception {
        return dataOf(mockMvc.perform(get(path)).andReturn());
    }

    protected MvcResult createDraft(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/discussions").contentType(APPLICATION_JSON).content(body)).andReturn();
    }

    protected MvcResult copyPreset(String slug) throws Exception {
        return mockMvc.perform(post("/api/v1/presets/" + slug + "/discussions")).andReturn();
    }

    protected int count(String sql, Object... arguments) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    protected String createBody(String topic) {
        return "{\"topic\":\"" + topic + "\"}";
    }

    protected String createBody(String topic, Integer expertCount) {
        return "{\"topic\":\"" + topic + "\",\"expertCount\":" + expertCount + "}";
    }
}