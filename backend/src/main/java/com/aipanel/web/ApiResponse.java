package com.aipanel.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.aipanel.exception.ApiErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 统一响应体。
 *
 * @param code      与 HTTP 状态一致的整数
 * @param message   中文提示
 * @param data      业务数据，失败时为 null
 * @param timestamp UTC ISO-8601 时间戳
 */
public record ApiResponse<T>(int code, String message, T data, String timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return success(HttpStatus.OK, data);
    }

    public static <T> ApiResponse<T> success(HttpStatus status, T data) {
        return new ApiResponse<>(status.value(), "成功", data, now());
    }

    public static ApiResponse<Void> failure(ApiErrorCode errorCode) {
        return failure(errorCode, errorCode.message());
    }

    public static ApiResponse<Void> failure(ApiErrorCode errorCode, String message) {
        return failure(errorCode.status(), message);
    }

    public static ApiResponse<Void> failure(HttpStatusCode status, String message) {
        return new ApiResponse<>(status.value(), message, null, now());
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
