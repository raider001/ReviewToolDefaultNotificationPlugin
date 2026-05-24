package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IndexerSseListener} event parsing.
 *
 * <p>Each test wires a {@link FakeSseHttpClient} that returns a pre-built SSE response,
 * runs the listener in a daemon thread, waits for the expected events via a
 * {@link CountDownLatch}, and then stops + interrupts the thread.  No network is involved.
 */
class IndexerSseListenerTests {

    private static final IndexerConfig CONFIG =
            new IndexerConfig("http://localhost:8765", "", List.of());

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Runs the listener against the given SSE text, waits for {@code expectedCount} events
     * (up to 5 seconds), then shuts the listener down.
     *
     * @param rawSse       raw SSE text with {@code \n} line endings (event blocks separated by
     *                     blank lines, e.g. {@code "id: 1\nevent: review.created\ndata: {...}\n\n"})
     * @param expectedCount number of events to wait for before shutting down
     * @return the events received in delivery order
     */
    private List<IndexerSseListener.IndexerEvent> runListener(String rawSse, int expectedCount)
            throws InterruptedException {
        List<IndexerSseListener.IndexerEvent> captured = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(expectedCount);

        Stream<String> lines = Arrays.stream(rawSse.split("\n", -1));
        FakeSseHttpClient fake = new FakeSseHttpClient(200, lines);

        IndexerSseListener listener = new IndexerSseListener("*", CONFIG, e -> {
            captured.add(e);
            latch.countDown();
        }, fake);

        Thread thread = new Thread(listener, "test-sse-listener");
        thread.setDaemon(true);
        thread.start();

        // Wait for expected events (or timeout)
        latch.await(5, TimeUnit.SECONDS);

        listener.stop();
        thread.interrupt();
        thread.join(1_000);

        return captured;
    }

    /** Runs the listener and expects zero events to arrive within a short window. */
    private List<IndexerSseListener.IndexerEvent> runListenerExpectingNone(String rawSse)
            throws InterruptedException {
        List<IndexerSseListener.IndexerEvent> captured = new ArrayList<>();

        Stream<String> lines = Arrays.stream(rawSse.split("\n", -1));
        FakeSseHttpClient fake = new FakeSseHttpClient(200, lines);

        IndexerSseListener listener = new IndexerSseListener("*", CONFIG, captured::add, fake);

        Thread thread = new Thread(listener, "test-sse-listener");
        thread.setDaemon(true);
        thread.start();

        // Give the listener time to process and produce no events
        Thread.sleep(200);

        listener.stop();
        thread.interrupt();
        thread.join(1_000);

        return captured;
    }

    // -------------------------------------------------------------------------
    // review.* events
    // -------------------------------------------------------------------------

    @Test
    void reviewCreated_parsedCorrectly() throws InterruptedException {
        String sse = "id: 1\nevent: review.created\ndata: {\"review_id\":\"r1\",\"repository\":\"owner/repo\"}\n\n";

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 1);

        assertEquals(1, events.size());
        IndexerSseListener.IndexerEvent e = events.get(0);
        assertEquals("review.created", e.eventType());
        assertEquals("r1", e.reviewId());
        assertEquals("owner/repo", e.repository());
        assertNull(e.repositoryUrl(), "review.created should not carry repositoryUrl");
        assertNull(e.branchName(),    "review.created should not carry branchName");
    }

    @Test
    void reviewUpdated_parsedCorrectly() throws InterruptedException {
        String sse = "id: 2\nevent: review.updated\ndata: {\"review_id\":\"r2\",\"repository\":\"owner/repo\"}\n\n";

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 1);

        assertEquals(1, events.size());
        assertEquals("review.updated", events.get(0).eventType());
        assertEquals("r2", events.get(0).reviewId());
    }

    // -------------------------------------------------------------------------
    // branch.* events
    // -------------------------------------------------------------------------

    @Test
    void branchUpdated_repositoryUrlAndBranchNameParsed() throws InterruptedException {
        String sse = """
                id: 3
                event: branch.updated
                data: {"review_id":"r1","repository":"owner/repo","repository_url":"https://github.com/owner/repo.git","branch_name":"feature/foo"}

                """;

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 1);

        assertEquals(1, events.size());
        IndexerSseListener.IndexerEvent e = events.get(0);
        assertEquals("branch.updated", e.eventType());
        assertEquals("r1", e.reviewId());
        assertEquals("owner/repo", e.repository());
        assertEquals("https://github.com/owner/repo.git", e.repositoryUrl());
        assertEquals("feature/foo", e.branchName());
    }

    @Test
    void branchDeleted_parsedCorrectly() throws InterruptedException {
        String sse = """
                id: 4
                event: branch.deleted
                data: {"review_id":"r1","repository":"owner/repo","branch_name":"feature/old"}

                """;

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 1);

        assertEquals(1, events.size());
        assertEquals("branch.deleted", events.get(0).eventType());
        assertEquals("feature/old", events.get(0).branchName());
        assertNull(events.get(0).repositoryUrl());
    }

    // -------------------------------------------------------------------------
    // Multiple events in one stream
    // -------------------------------------------------------------------------

    @Test
    void multipleEvents_allDispatchedInOrder() throws InterruptedException {
        String sse = """
                id: 1
                event: review.created
                data: {"review_id":"r1","repository":"owner/repo"}

                id: 2
                event: review.updated
                data: {"review_id":"r2","repository":"owner/repo"}

                id: 3
                event: branch.updated
                data: {"review_id":"r3","repository":"owner/repo","repository_url":"https://github.com/owner/repo.git","branch_name":"feature/x"}

                """;

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 3);

        assertEquals(3, events.size());
        assertEquals("review.created", events.get(0).eventType());
        assertEquals("r1",             events.get(0).reviewId());
        assertEquals("review.updated", events.get(1).eventType());
        assertEquals("r2",             events.get(1).reviewId());
        assertEquals("branch.updated", events.get(2).eventType());
        assertEquals("r3",             events.get(2).reviewId());
    }

    // -------------------------------------------------------------------------
    // Cursor tracking
    // -------------------------------------------------------------------------

    @Test
    void cursor_updatedFromIdField() throws InterruptedException {
        // We cannot inspect the cursor directly (it's package-private state), but we can
        // verify the listener does not throw when id fields are present and numeric.
        String sse = "id: 42\nevent: review.created\ndata: {\"review_id\":\"r1\"}\n\n";

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 1);
        assertEquals(1, events.size());
    }

    @Test
    void cursor_nonNumericId_ignoredGracefully() throws InterruptedException {
        String sse = "id: not-a-number\nevent: review.created\ndata: {\"review_id\":\"r1\"}\n\n";

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 1);
        // Event should still be dispatched even though the id is non-numeric
        assertEquals(1, events.size());
        assertEquals("r1", events.get(0).reviewId());
    }

    // -------------------------------------------------------------------------
    // Event type in payload data (no "event:" line)
    // -------------------------------------------------------------------------

    @Test
    void eventType_inDataPayload_usedWhenNoEventLine() throws InterruptedException {
        // When the SSE frame has no "event:" line the listener falls back to the
        // "type" field inside the JSON data.
        String sse = "id: 5\ndata: {\"type\":\"review.created\",\"review_id\":\"r1\"}\n\n";

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 1);

        assertEquals(1, events.size());
        assertEquals("review.created", events.get(0).eventType());
        assertEquals("r1",             events.get(0).reviewId());
    }

    // -------------------------------------------------------------------------
    // Resilience
    // -------------------------------------------------------------------------

    @Test
    void malformedJsonData_frameSkipped_noException() throws InterruptedException {
        // A malformed data line followed by a valid one: only the valid event should arrive.
        String sse = "id: 1\nevent: review.created\ndata: {{{INVALID_JSON\n\n" +
                     "id: 2\nevent: review.updated\ndata: {\"review_id\":\"r2\"}\n\n";

        List<IndexerSseListener.IndexerEvent> events = runListener(sse, 1);

        assertEquals(1, events.size());
        assertEquals("review.updated", events.get(0).eventType());
        assertEquals("r2",             events.get(0).reviewId());
    }

    @Test
    void frameWithNoData_noEventDispatched() throws InterruptedException {
        // A frame with only id/event but no data line should not dispatch anything.
        String sse = "id: 1\nevent: review.created\n\n";

        List<IndexerSseListener.IndexerEvent> events = runListenerExpectingNone(sse);

        assertTrue(events.isEmpty(), "Frame without data should produce no events");
    }

    @Test
    void frameWithNullEventTypeAndNoTypeInData_frameSkipped() throws InterruptedException {
        // If neither the SSE "event:" line nor the JSON "type" field is present, the frame
        // is dropped.
        String sse = "id: 1\ndata: {\"review_id\":\"r1\"}\n\n";

        List<IndexerSseListener.IndexerEvent> events = runListenerExpectingNone(sse);

        assertTrue(events.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Non-200 HTTP response
    // -------------------------------------------------------------------------

    @Test
    void non200Response_noEventsDispatched_listenerDoesNotCrash() throws InterruptedException {
        List<IndexerSseListener.IndexerEvent> captured = new ArrayList<>();

        FakeSseHttpClient fake = new FakeSseHttpClient(503, Stream.of());
        IndexerSseListener listener = new IndexerSseListener("*", CONFIG, captured::add, fake);

        Thread thread = new Thread(listener, "test-sse-non200");
        thread.setDaemon(true);
        thread.start();

        Thread.sleep(200);
        listener.stop();
        thread.interrupt();
        thread.join(1_000);

        assertTrue(captured.isEmpty(), "Non-200 response should yield no events");
    }

    @Test
    void response410_cursorReset_noEventsDispatched() throws InterruptedException {
        List<IndexerSseListener.IndexerEvent> captured = new ArrayList<>();

        FakeSseHttpClient fake = new FakeSseHttpClient(410, Stream.of());
        IndexerSseListener listener = new IndexerSseListener("*", CONFIG, captured::add, fake);

        Thread thread = new Thread(listener, "test-sse-410");
        thread.setDaemon(true);
        thread.start();

        Thread.sleep(200);
        listener.stop();
        thread.interrupt();
        thread.join(1_000);

        assertTrue(captured.isEmpty(), "410 response should yield no events");
    }

    // -------------------------------------------------------------------------
    // Fake HTTP infrastructure
    // -------------------------------------------------------------------------

    /**
     * {@link HttpClient} stub that returns a pre-built SSE response as a
     * {@code Stream<String>} body.
     */
    static class FakeSseHttpClient extends HttpClient {
        private final int status;
        private final Stream<String> lines;

        FakeSseHttpClient(int status, Stream<String> lines) {
            this.status = status;
            this.lines = lines;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> handler) {
            return new FakeHttpResponse<>(status, (T) lines, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                  HttpResponse.BodyHandler<T> handler,
                                                                  HttpResponse.PushPromiseHandler<T> ppHandler) {
            return sendAsync(request, handler);
        }

        @Override public Optional<CookieHandler> cookieHandler()  { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout()       { return Optional.empty(); }
        @Override public Redirect followRedirects()                { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy()           { return Optional.empty(); }
        @Override public SSLParameters sslParameters()             { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator()   { return Optional.empty(); }
        @Override public Version version()                         { return Version.HTTP_2; }
        @Override public Optional<Executor> executor()             { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    /** Minimal {@link HttpResponse} carrying a fixed status and body. */
    static class FakeHttpResponse<T> implements HttpResponse<T> {
        private final int statusCode;
        private final T body;
        private final HttpRequest request;

        FakeHttpResponse(int statusCode, T body, HttpRequest request) {
            this.statusCode = statusCode;
            this.body = body;
            this.request = request;
        }

        @Override public int statusCode()                             { return statusCode; }
        @Override public T body()                                     { return body; }
        @Override public HttpRequest request()                        { return request; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers()                        { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession()           { return Optional.empty(); }
        @Override public URI uri()                                    { return request.uri(); }
        @Override public HttpClient.Version version()                 { return HttpClient.Version.HTTP_2; }
    }
}
