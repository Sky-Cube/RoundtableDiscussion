package com.aipanel.web.vo;

import com.fasterxml.jackson.databind.JsonNode;

/** 管理台事件流单条：事件序号、类型、时间与完整载荷。 */
public record AdminEventVO(int seq, String type, String createdAt, JsonNode payload) {
}