package com.aipanel.exception;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.aipanel.config.DataSourceSecurityException;
import com.aipanel.web.ApiResponse;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;

/** 把校验、业务、存储与数据源异常映射为契约错误码，失败响应 data 恒为 null。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException error) {
        return build(error.errorCode(), error.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            BindException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception error) {
        return build(ApiErrorCode.B0001, ApiErrorCode.B0001.message());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception error) {
        return build(ApiErrorCode.B0002, ApiErrorCode.B0002.message());
    }

    @ExceptionHandler(DataSourceSecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataSourceSecurity(DataSourceSecurityException error) {
        log.error("数据源路径守卫拒绝访问：{}", error.getMessage());
        return build(ApiErrorCode.B0005, ApiErrorCode.B0005.message());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccess(DataAccessException error) {
        log.error("本地存储操作失败", error);
        return build(ApiErrorCode.B0005, ApiErrorCode.B0005.message());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIo(IOException error, HttpServletRequest request,
            HttpServletResponse response) {
        String contentType = response.getContentType();
        if (error instanceof AsyncRequestNotUsableException
                || (request.getDispatcherType() == DispatcherType.ASYNC && (response.isCommitted()
                        || (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE))))) {
            // 异步响应已提交或关闭，容器负责结束流。
            log.debug("异步响应传输结束");
            return null;
        }
        return handleUnexpected(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception error) {
        if (error instanceof ErrorResponse standard && standard.getStatusCode().is4xxClientError()) {
            String message = switch (standard.getStatusCode().value()) {
                case 405 -> "不支持该请求方法";
                case 406 -> "无法提供所要求的响应格式";
                case 415 -> "不支持该请求内容类型";
                default -> "请求参数无效";
            };
            return ResponseEntity.status(standard.getStatusCode()).headers(standard.getHeaders())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ApiResponse.failure(standard.getStatusCode(), message));
        }
        log.error("未预期的服务异常", error);
        return build(ApiErrorCode.B0005, ApiErrorCode.B0005.message());
    }

    private ResponseEntity<ApiResponse<Void>> build(ApiErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.status()).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(errorCode, message));
    }
}
