package com.shoppinglive.live.stream.application;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 실제 HTTP 로 SSE 에 연결해 응답 줄을 별도 스레드에서 모은다. 스트림이 끝나면 END_OF_STREAM 을 넣는다. */
final class SseTestClient implements AutoCloseable {
    static final String END_OF_STREAM = "<closed>";

    final HttpResponse<InputStream> response;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    SseTestClient(final int port, final long broadcastId) throws Exception {
        response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
            "http://127.0.0.1:" + port + "/v1/broadcasts/" + broadcastId + "/events"))
            .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    lines.add(line);
                    line = reader.readLine();
                }
            } catch (Exception ignored) {
                // 테스트가 연결을 닫았다.
            } finally {
                lines.add(END_OF_STREAM);
            }
        });
    }

    /** 빈 줄(이벤트 구분)을 건너뛴 다음 줄. 5초 안에 없으면 null. */
    String nextNonEmpty() throws InterruptedException {
        String line = lines.poll(5, TimeUnit.SECONDS);
        while (line != null && line.isEmpty()) {
            line = lines.poll(5, TimeUnit.SECONDS);
        }
        return line;
    }

    @Override
    public void close() throws Exception {
        response.body().close();
    }
}
