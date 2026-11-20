package com.aipanel.gateway;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 按运行时 Schema 严格解析 rosterOutput：未声明字段、类型不符与缺失字段一律拒绝。 */
@Component
public class RosterParser {

    private final ObjectMapper objectMapper;

    public RosterParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RosterOutput parse(String json) {
        if (json == null || json.isBlank()) {
            throw new RosterStructureException("模型输出为空");
        }
        try {
            RosterOutput output = objectMapper.readValue(json, RosterOutput.class);
            if (output == null || output.participants() == null) {
                throw new RosterStructureException("模型输出缺少 participants");
            }
            return output;
        } catch (JsonProcessingException error) {
            throw new RosterStructureException(error.getOriginalMessage());
        }
    }
}
