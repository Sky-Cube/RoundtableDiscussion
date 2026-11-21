package com.aipanel.web;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.exception.ApiException;
import com.aipanel.model.Discussion;
import com.aipanel.repository.DiscussionEventRepository;
import com.aipanel.service.DiscussionService;
import com.aipanel.util.Uuids;

/**
 * 讨论事件流：先按游标回放已提交事件，再持续推送新事件。
 *
 * <p>轮询间隔 200 毫秒，约每 15 秒发一次心跳；房间进入终态且游标追平后关闭连接。
 */
@RestController
@RequestMapping("/api/v1/discussions")
public class DiscussionEventController {

    private static final long POLL_MILLIS = 200;
    private static final long HEARTBEAT_MILLIS = 15_000;
    private static final long EMITTER_TIMEOUT_MILLIS = 3_600_000;

    private final DiscussionService discussionService;
    private final DiscussionEventRepository events;
    private final SseConnections connections;
    private final SimpleAsyncTaskExecutor streams = new SimpleAsyncTaskExecutor("panel-sse-");

    public DiscussionEventController(DiscussionService discussionService, DiscussionEventRepository events,
            SseConnections connections) {
        this.discussionService = discussionService;
        this.events = events;
        this.connections = connections;
        streams.setVirtualThreads(true);
        streams.setTaskTerminationTimeout(POLL_MILLIS);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String id, HttpServletRequest request) {
        Uuids.requireId(id);
        Discussion room = discussionService.requireRoom(id);
        int cursor = resolveCursor(request, room.lastEventSeq());

        if (isTerminal(room.status()) && cursor >= room.lastEventSeq()) {
            return ResponseEntity.<SseEmitter>noContent().build();
        }
        SseConnections.Lease lease = connections.tryAcquire(id);
        if (lease == null) {
            throw new ApiException(ApiErrorCode.B0004);
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitter.onCompletion(lease::close);
        emitter.onTimeout(() -> {
            lease.close();
            emitter.complete();
        });
        emitter.onError(error -> lease.close());

        try {
            streams.execute(() -> pump(id, emitter, cursor, lease));
        } catch (RuntimeException error) {
            lease.close();
            throw new ApiException(ApiErrorCode.B0004);
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "event-stream", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache, no-transform").body(emitter);
    }

    @PreDestroy
    void shutdown() {
        streams.close();
    }

    private void pump(String discussionId, SseEmitter emitter, int startCursor, SseConnections.Lease lease) {
        int cursor = startCursor;
        long lastHeartbeat = System.nanoTime();
        try (lease) {
            while (!lease.isClosed() && !Thread.currentThread().isInterrupted()) {
                List<DiscussionEventRepository.EventRow> rows = events.findAfter(discussionId, cursor);
                for (DiscussionEventRepository.EventRow row : rows) {
                    emitter.send(SseEmitter.event().id(String.valueOf(row.seq())).name(row.type())
                            .data(row.payloadJson(), MediaType.APPLICATION_JSON));
                    cursor = row.seq();
                }
                Discussion room = discussionService.requireRoom(discussionId);
                if (isTerminal(room.status()) && cursor >= room.lastEventSeq()) {
                    emitter.complete();
                    return;
                }
                long now = System.nanoTime();
                if (TimeUnit.NANOSECONDS.toMillis(now - lastHeartbeat) >= HEARTBEAT_MILLIS) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_MILLIS);
            }
            emitter.complete();
        } catch (IOException error) {
            // 传输中断由 Servlet 容器处理，连接配额由租约归还。
        } catch (ApiException error) {
            if (error.errorCode() == ApiErrorCode.B0002) {
                emitter.complete();
            } else {
                emitter.completeWithError(error);
            }
        } catch (RuntimeException error) {
            emitter.completeWithError(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            emitter.complete();
        }
    }

    /** Last-Event-ID 优先于 after；两者缺省为 0。游标必须是规范十进制且不超过最新序号。 */
    private int resolveCursor(HttpServletRequest request, int maxSeq) {
        List<String> headers = Collections.list(request.getHeaders("Last-Event-ID"));
        String[] values = headers.isEmpty() ? request.getParameterValues("after") : headers.toArray(String[]::new);
        if (values == null) {
            return 0;
        }
        if (values.length != 1 || !values[0].matches("0|[1-9][0-9]*")) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        long parsed;
        try {
            parsed = Long.parseLong(values[0]);
        } catch (NumberFormatException error) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        if (parsed > maxSeq) {
            throw new ApiException(ApiErrorCode.B0001);
        }
        return (int) parsed;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }
}
