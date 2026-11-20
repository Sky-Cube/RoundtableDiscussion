package com.aipanel.gateway;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class BoundedHttp {

    private BoundedHttp() {
    }

    public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, Duration timeout, int maxBytes)
            throws IOException, InterruptedException, TimeoutException {
        var pending = client.sendAsync(request, info -> new LimitedBody(maxBytes));
        try {
            return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            throw new IOException("HTTP 请求失败", error.getCause());
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private long length;

        private LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription upstream) {
            subscription = upstream;
            body.onSubscribe(upstream);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            length += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (length > limit) {
                subscription.cancel();
                body.onError(new IOException("HTTP 响应超过上限"));
                return;
            }
            body.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            body.onError(error);
        }

        @Override
        public void onComplete() {
            body.onComplete();
        }
    }
}
