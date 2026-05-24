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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IndexerRestClient}.
 *
 * <p>A lightweight {@link FakeHttpClient} stands in for the real {@link HttpClient} so tests
 * run without a network and without any third-party mocking library.
 */
class IndexerRestClientTests {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static IndexerConfig config(String url) {
        return new IndexerConfig(url, "", List.of());
    }

    // -------------------------------------------------------------------------
    // Happy path — single review
    // -------------------------------------------------------------------------

    @Test
    void fetchReviews_singleReview_allFieldsParsed() {
        String json = """
                {
                  "items": [
                    {
                      "review_id": "r1",
                      "status": "OPEN",
                      "review_branch": "feature/foo",
                      "base_branch": "main",
                      "repositories": [
                        {
                          "repository": "owner/repo",
                          "repository_url": "https://github.com/owner/repo.git"
                        }
                      ]
                    }
                  ]
                }
                """;

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertEquals(1, reviews.size());
        IndexerRestClient.ReviewSummary r = reviews.get(0);
        assertEquals("r1", r.reviewId());
        assertEquals("OPEN", r.status());
        assertEquals("feature/foo", r.reviewBranch());
        assertEquals("main", r.baseBranch());
        assertEquals(1, r.repositories().size());
        assertEquals("owner/repo", r.repositories().get(0).repository());
        assertEquals("https://github.com/owner/repo.git", r.repositories().get(0).repositoryUrl());
    }

    // -------------------------------------------------------------------------
    // Happy path — multiple reviews
    // -------------------------------------------------------------------------

    @Test
    void fetchReviews_multipleReviews_allReturned() {
        String json = """
                {"items": [
                  {"review_id": "r1", "status": "OPEN",      "repositories": []},
                  {"review_id": "r2", "status": "MERGED",    "repositories": []},
                  {"review_id": "r3", "status": "CANCELLED", "repositories": []}
                ]}
                """;

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertEquals(3, reviews.size());
        assertEquals("r1", reviews.get(0).reviewId());
        assertEquals("r2", reviews.get(1).reviewId());
        assertEquals("r3", reviews.get(2).reviewId());
    }

    @Test
    void fetchReviews_emptyItemsArray_returnsEmptyList() {
        String json = "{\"items\": []}";

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertNotNull(reviews);
        assertTrue(reviews.isEmpty());
    }

    @Test
    void fetchReviews_noItemsField_returnsEmptyList() {
        String json = "{\"meta\": {\"total\": 0}}";

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertNotNull(reviews);
        assertTrue(reviews.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Optional / missing fields
    // -------------------------------------------------------------------------

    @Test
    void fetchReviews_missingOptionalFields_nullsForAbsentFields() {
        String json = "{\"items\": [{\"review_id\": \"r1\"}]}";

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertEquals(1, reviews.size());
        IndexerRestClient.ReviewSummary r = reviews.get(0);
        assertEquals("r1", r.reviewId());
        assertNull(r.status());
        assertNull(r.reviewBranch());
        assertNull(r.baseBranch());
        assertTrue(r.repositories().isEmpty());
    }

    @Test
    void fetchReviews_repositoryMissingUrl_repositoryUrlIsNull() {
        String json = """
                {"items": [{"review_id": "r1", "repositories": [{"repository": "owner/repo"}]}]}
                """;

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertEquals(1, reviews.size());
        assertEquals(1, reviews.get(0).repositories().size());
        assertEquals("owner/repo", reviews.get(0).repositories().get(0).repository());
        assertNull(reviews.get(0).repositories().get(0).repositoryUrl(),
                "repository_url should be null when absent");
    }

    @Test
    void fetchReviews_multipleRepositoriesPerReview_allParsed() {
        String json = """
                {"items": [{
                  "review_id": "r1",
                  "repositories": [
                    {"repository": "org/backend",  "repository_url": "https://github.com/org/backend.git"},
                    {"repository": "org/frontend", "repository_url": "https://github.com/org/frontend.git"}
                  ]
                }]}
                """;

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertEquals(1, reviews.size());
        assertEquals(2, reviews.get(0).repositories().size());
        assertEquals("org/backend",  reviews.get(0).repositories().get(0).repository());
        assertEquals("org/frontend", reviews.get(0).repositories().get(1).repository());
    }

    // -------------------------------------------------------------------------
    // Skipped / filtered items
    // -------------------------------------------------------------------------

    @Test
    void fetchReviews_reviewWithoutReviewId_skipped() {
        String json = """
                {"items": [
                  {"status": "OPEN", "repositories": []},
                  {"review_id": "r2", "status": "OPEN", "repositories": []}
                ]}
                """;

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertEquals(1, reviews.size());
        assertEquals("r2", reviews.get(0).reviewId());
    }

    @Test
    void fetchReviews_repositoryEntryWithNullRepositoryField_skipped() {
        String json = """
                {"items": [{
                  "review_id": "r1",
                  "repositories": [
                    {"repository_url": "https://github.com/owner/repo.git"}
                  ]
                }]}
                """;

        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertEquals(1, reviews.size());
        assertTrue(reviews.get(0).repositories().isEmpty(),
                "Repository entry without a 'repository' field should be skipped");
    }

    // -------------------------------------------------------------------------
    // HTTP error responses
    // -------------------------------------------------------------------------

    @Test
    void fetchReviews_500Response_returnsEmptyList() {
        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(500, "Internal Server Error"));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertNotNull(reviews);
        assertTrue(reviews.isEmpty());
    }

    @Test
    void fetchReviews_404Response_returnsEmptyList() {
        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(404, "Not Found"));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertTrue(reviews.isEmpty());
    }

    @Test
    void fetchReviews_503Response_returnsEmptyList() {
        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(503, "Service Unavailable"));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertTrue(reviews.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Input validation — bad config
    // -------------------------------------------------------------------------

    @Test
    void fetchReviews_blankIndexerUrl_returnsEmptyListWithoutHttp() {
        FakeHttpClient fake = new FakeHttpClient(200, "{\"items\":[]}");
        IndexerRestClient client = new IndexerRestClient(fake);
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config(""));

        assertTrue(reviews.isEmpty());
        assertEquals(0, fake.callCount, "HTTP must not be called when URL is blank");
    }

    @Test
    void fetchReviews_nullIndexerUrl_returnsEmptyListWithoutHttp() {
        FakeHttpClient fake = new FakeHttpClient(200, "{\"items\":[]}");
        IndexerRestClient client = new IndexerRestClient(fake);
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(new IndexerConfig(null, "", List.of()));

        assertTrue(reviews.isEmpty());
        assertEquals(0, fake.callCount, "HTTP must not be called when URL is null");
    }

    // -------------------------------------------------------------------------
    // Resilience
    // -------------------------------------------------------------------------

    @Test
    void fetchReviews_malformedJson_returnsEmptyList() {
        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, "{{not valid json{{"));
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertNotNull(reviews);
        assertTrue(reviews.isEmpty(), "Malformed JSON should produce an empty list, not throw");
    }

    @Test
    void fetchReviews_httpClientThrows_returnsEmptyList() {
        IndexerRestClient client = new IndexerRestClient(new ThrowingHttpClient());
        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(config("http://localhost:8765"));

        assertNotNull(reviews);
        assertTrue(reviews.isEmpty(), "IOException from HTTP client should be swallowed");
    }

    // -------------------------------------------------------------------------
    // Bearer token
    // -------------------------------------------------------------------------

    @Test
    void fetchReviews_bearerTokenPresent_callSucceeds() {
        // We can't easily inspect headers without a full HTTP stack,
        // but we verify the call completes (non-blank token path) without error.
        String json = "{\"items\": [{\"review_id\": \"r1\", \"repositories\": []}]}";
        IndexerRestClient client = new IndexerRestClient(new FakeHttpClient(200, json));
        IndexerConfig configWithToken = new IndexerConfig("http://localhost:8765", "my-token", List.of());

        List<IndexerRestClient.ReviewSummary> reviews = client.fetchReviews(configWithToken);
        assertEquals(1, reviews.size());
    }

    // -------------------------------------------------------------------------
    // Fake HTTP infrastructure
    // -------------------------------------------------------------------------

    /** Minimal {@link HttpClient} stub that returns a canned status + body for every request. */
    static class FakeHttpClient extends HttpClient {
        private final int status;
        private final String body;
        int callCount = 0;

        FakeHttpClient(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> handler) {
            callCount++;
            return new FakeHttpResponse<>(status, (T) body, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                  HttpResponse.BodyHandler<T> handler,
                                                                  HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
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

    /** {@link HttpClient} stub whose {@code send()} always throws {@link IOException}. */
    static class ThrowingHttpClient extends HttpClient {
        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> handler) throws IOException {
            throw new IOException("connection refused");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.failedFuture(new IOException("connection refused"));
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

    /** Minimal {@link HttpResponse} carrying a fixed status code and body. */
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
