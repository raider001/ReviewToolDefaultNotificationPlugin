package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.sse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfig;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfigManager;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.http.HttpClientW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * SSE transport layer. Sole responsibilities:
 * <ul>
 *   <li>Maintain a persistent HTTP connection to {@code GET /events/stream}</li>
 *   <li>Parse raw SSE frames into {@link SseEvent} records</li>
 *   <li>Pass each complete event to the supplied consumer</li>
 *   <li>Reconnect automatically on failure, tracking the cursor via {@code id:} fields</li>
 * </ul>
 *
 * <p>No routing, payload construction, or plugin method dispatch happens here.
 */
public class IndexerSseListener implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerSseListener.class);
    private static final long RECONNECT_DELAY_MS = 5_000L;
    public static final String WILDCARD_REPO = "*";

    private final String repository;
    private final IndexerConfigManager config;
    private final Consumer<SseEvent> onEvent;
    private final HttpClientW http;

    private volatile boolean running = true;
    private long cursor = 0;

    public IndexerSseListener(String repository, IndexerConfigManager config,
                               Consumer<SseEvent> onEvent, HttpClientW http) {
        this.repository = repository;
        this.config = config;
        this.onEvent = onEvent;
        this.http = http;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.net.ConnectException e) {
                LOGGER.debug("Indexer unreachable for '{}', retrying in {}s", repository, RECONNECT_DELAY_MS / 1000);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                LOGGER.warn("SSE connection lost for '{}': {}", repository, msg);
                LOGGER.debug("SSE connection lost detail", e);
            }
            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        LOGGER.info("SSE listener stopped for '{}'", repository);
    }

    private void connect() throws Exception {
        String encodedRepo = URLEncoder.encode(repository, StandardCharsets.UTF_8);
        String url = config.get().indexerUrl() + "/events/stream?repository=" + encodedRepo + "&since=" + cursor;

        HttpRequest request = buildRequest(url);
        HttpResponse<Stream<String>> response = http.get().send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() == 410) {
            LOGGER.warn("Cursor for '{}' is outside the retention window; resetting to 0", repository);
            cursor = 0;
            return;
        }
        if (response.statusCode() != 200) {
            LOGGER.warn("Central Indexer returned {} for repository '{}'", response.statusCode(), repository);
            return;
        }

        LOGGER.info("SSE stream connected for '{}'", repository);
        parseSseStream(response.body());
    }

    private HttpRequest buildRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "text/event-stream")
                .GET();
        if (!config.get().bearerToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.get().bearerToken());
        }
        if (cursor > 0 && !WILDCARD_REPO.equals(repository)) {
            builder.header("Last-Event-ID", String.valueOf(cursor));
        }
        return builder.build();
    }

    private void parseSseStream(Stream<String> lines) {
        Frame frame = new Frame();
        lines.forEach(line -> {
            if (line.isEmpty()) {
                if (frame.hasData()) dispatchFrame(frame);
                frame.reset();
            } else if (line.startsWith("id: ")) {
                frame.id = line.substring(4).trim();
            } else if (line.startsWith("event: ")) {
                frame.eventType = line.substring(7).trim();
            } else if (line.startsWith("data: ")) {
                frame.data = line.substring(6).trim();
            }
        });
    }

    private void dispatchFrame(Frame frame) {
        if (frame.id != null) {
            try { cursor = Long.parseLong(frame.id); } catch (NumberFormatException ignored) {}
        }
        SseEvent event = toSseEvent(frame);
        if (event != null) {
            onEvent.accept(event);
        } else {
            LOGGER.debug("Frame dropped — no event type or malformed data");
        }
    }

    private SseEvent toSseEvent(Frame frame) {
        if (frame.data == null) return null;
        try {
            JsonObject obj = JsonParser.parseString(frame.data).getAsJsonObject();
            String eventType = frame.eventType != null ? frame.eventType : str(obj, "type");
            if (eventType == null) return null;

            // Comment events are serialised as a flat snake_case object.
            // All other events are serialised as a ReviewEvent record (camelCase, nested payload).
            if (obj.has("comment_id") || !obj.has("payload")) {
                return new SseEvent(eventType,
                        str(obj, "review_id"),
                        str(obj, "repository"),
                        str(obj, "repository_url"),
                        str(obj, "branch_name"),
                        str(obj, "comment_id"));
            }

            JsonObject payload = obj.get("payload").isJsonObject()
                    ? obj.getAsJsonObject("payload") : new JsonObject();
            return new SseEvent(eventType,
                    str(obj, "reviewId"),
                    str(obj, "repository"),
                    str(payload, "repository_url"),
                    str(payload, "branch_name"),
                    null);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse SSE frame data: {}", e.getMessage());
            return null;
        }
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static class Frame {
        String id;
        String eventType;
        String data;

        boolean hasData() { return data != null; }

        void reset() {
            id = null;
            eventType = null;
            data = null;
        }
    }
}
