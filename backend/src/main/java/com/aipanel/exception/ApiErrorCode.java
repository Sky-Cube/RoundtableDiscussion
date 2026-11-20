package com.aipanel.exception;

import org.springframework.http.HttpStatus;

/** 业务异常及历史持久化错误标识对应的 HTTP 状态。 */
public enum ApiErrorCode {

    B0001(HttpStatus.BAD_REQUEST, "请求参数无效"),
    B0002(HttpStatus.NOT_FOUND, "资源不存在"),
    B0003(HttpStatus.CONFLICT, "状态冲突"),
    B0004(HttpStatus.TOO_MANY_REQUESTS, "容量已满，请稍后重试"),
    B0005(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用"),
    B0006(HttpStatus.FORBIDDEN, "请求来源不被允许"),
    B4001(HttpStatus.SERVICE_UNAVAILABLE, "模型服务尚未配置"),
    B9001(HttpStatus.BAD_GATEWAY, "暂时无法生成合格的专家阵容"),
    B9002(HttpStatus.GATEWAY_TIMEOUT, "模型请求超时");

    private final HttpStatus status;
    private final String message;

    ApiErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }

    public static Integer fromStoredCode(String code) {
        if (code == null) {
            return null;
        }
        try {
            return valueOf(code).status().value();
        } catch (IllegalArgumentException error) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
    }
}
