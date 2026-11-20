package com.aipanel.gateway;

/** 网关失败分类，决定是否进入一次补偿以及映射到哪个业务码。 */
public class ModelGatewayException extends RuntimeException {

    public enum Kind {
        /** 未配置模型服务，直接 503/B4001。 */
        CONFIG_MISSING,
        /** 上游超时，可补偿一次后 504/B9002。 */
        TIMEOUT,
        /** 内容拦截，直接 502/B9001，不进入补偿。 */
        CONTENT_BLOCKED,
        /** 上游调用或结构失败，可补偿一次。 */
        UPSTREAM_ERROR,
        TRANSIENT,
        FORMAT_INVALID,
        REJECTED,
        CONTEXT_UNAVAILABLE,
        STALE_RESPONSE
    }

    private final Kind kind;

    public ModelGatewayException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ModelGatewayException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean repairable() {
        return kind == Kind.TIMEOUT || kind == Kind.UPSTREAM_ERROR || kind == Kind.TRANSIENT
                || kind == Kind.FORMAT_INVALID;
    }

    public static ModelGatewayException configMissing() {
        return new ModelGatewayException(Kind.CONFIG_MISSING, "模型服务尚未配置");
    }

    public static ModelGatewayException timeout() {
        return new ModelGatewayException(Kind.TIMEOUT, "模型请求超时");
    }

    public static ModelGatewayException blocked() {
        return new ModelGatewayException(Kind.CONTENT_BLOCKED, "上游内容被拦截");
    }
}
