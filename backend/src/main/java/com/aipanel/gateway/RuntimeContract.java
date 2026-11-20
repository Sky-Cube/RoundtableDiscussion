package com.aipanel.gateway;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

@Component
public class RuntimeContract {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeContract.class);
    private static final int MAX_RESPONSE_CHARS = 32_768;

    private final ObjectReader reader;
    private final Map<RuntimeTask, Schema> validators = new EnumMap<>(RuntimeTask.class);
    private final Map<RuntimeTask, String> schemas = new EnumMap<>(RuntimeTask.class);

    public RuntimeContract(ObjectMapper mapper) throws IOException {
        reader = mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        try (var source = new ClassPathResource("docs/runtime-contract.schema.json").getInputStream()) {
            JsonNode contract = mapper.readTree(source);
            for (RuntimeTask task : RuntimeTask.values()) {
                ObjectNode schema = mapper.createObjectNode();
                schema.set("$schema", contract.required("$schema"));
                schema.set("$defs", contract.required("$defs"));
                schema.put("$ref", "#/$defs/" + task.schema());
                validators.put(task, registry.getSchema(schema));
                var definitions = contract.required("$defs");
                if (task == RuntimeTask.R01) {
                    // 阵容中主持与专家共用成员结构，保留引用避免向模型重复发送整份结构。
                    ObjectNode compact = mapper.createObjectNode();
                    compact.put("$ref", "#/$defs/rosterOutput");
                    ObjectNode used = compact.putObject("$defs");
                    for (String key : java.util.List.of("rosterOutput", "participant", "discussionGoal", "perspective")) {
                        used.set(key, definitions.required(key));
                    }
                    schemas.put(task, compact.toString());
                } else {
                    schemas.put(task, expand(definitions.required(task.schema()), definitions, mapper).toString());
                }
            }
        }
    }

    public String schema(RuntimeTask task) {
        return schemas.get(task);
    }

    public JsonNode parse(RuntimeTask task, String raw) {
        if (raw == null || raw.length() > MAX_RESPONSE_CHARS) {
            throw invalid("JSON_INVALID");
        }
        JsonNode node;
        try {
            node = reader.readTree(raw);
        } catch (JsonProcessingException error) {
            throw invalid("JSON_INVALID");
        }
        if (node == null || !node.isObject()) {
            throw invalid("SHAPE_INVALID");
        }
        var violations = validators.get(task).validate(node);
        if (!violations.isEmpty()) {
            var keywords = violations.stream().map(com.networknt.schema.Error::getKeyword)
                    .distinct().sorted().toList();
            LOG.info("模型结构校验失败 task={} keywords={}", task, keywords);
            if (keywords.contains("pattern")) {
                throw invalid("INVALID_SENTENCES");
            }
            if (keywords.stream().anyMatch(keyword -> keyword.equals("maxLength") || keyword.equals("minLength")
                    || keyword.equals("minItems") || keyword.equals("maxItems"))) {
                throw invalid("OUT_OF_RANGE");
            }
            throw invalid("SHAPE_INVALID");
        }
        return node;
    }

    private static ModelGatewayException invalid(String code) {
        return new ModelGatewayException(ModelGatewayException.Kind.FORMAT_INVALID, code);
    }

    private static JsonNode expand(JsonNode node, JsonNode definitions, ObjectMapper mapper) {
        if (node.has("$ref")) {
            return expand(definitions.required(node.get("$ref").asText().substring("#/$defs/".length())),
                    definitions, mapper);
        }
        if (node.isObject()) {
            var resolved = mapper.createObjectNode();
            node.fields().forEachRemaining(field -> resolved.set(field.getKey(),
                    expand(field.getValue(), definitions, mapper)));
            return resolved;
        }
        if (node.isArray()) {
            var resolved = mapper.createArrayNode();
            node.forEach(value -> resolved.add(expand(value, definitions, mapper)));
            return resolved;
        }
        return node;
    }
}
