// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.http.client.HttpClientItSupport.RecordedRequest;
import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.client.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Worked example of an OAuth 2.0 client-credentials flow on a {@link CredentialSource} (FR-009,
 * supporting): a separate client with no credential source of its own fetches the token, the source
 * caches it and shares one in-flight fetch, and the API client sends it as a bearer token.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class OAuthClientCredentialsExampleTest {

    private static HttpClientItSupport support;

    @BeforeAll
    static void startVertx() {
        support = HttpClientItSupport.start();
    }

    @AfterEach
    void closeResources() {
        support.closeResources();
        support.assertNoBlockedThreadWarning();
    }

    @AfterAll
    static void stopVertx() {
        support.stop();
    }

    /** The example: fetches a token with the client-credentials grant and caches it until shortly before expiry. */
    static final class ClientCredentialsSource implements CredentialSource {
        private static final Duration SKEW = Duration.ofSeconds(30);

        private final HttpClient tokenClient;
        private final String clientId;
        private final String clientSecret;
        private String token;
        private long expiresAtNanos;
        private Future<String> inFlight;

        ClientCredentialsSource(HttpClient tokenClient, String clientId, String clientSecret) {
            this.tokenClient = tokenClient;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        @Override
        public synchronized Future<Map<String, String>> resolve(URI target) {
            if (token != null && System.nanoTime() - expiresAtNanos < 0) {
                return Future.succeededFuture(Map.of("Authorization", "Bearer " + token));
            }
            if (inFlight == null) {
                Future<String> fetching = fetch();
                inFlight = fetching;
                fetching.onComplete(done -> clear(fetching));
            }
            return inFlight.map(t -> Map.of("Authorization", "Bearer " + t));
        }

        private synchronized void clear(Future<String> done) {
            if (inFlight == done) {
                inFlight = null;
            }
        }

        private Future<String> fetch() {
            MultiMap form = MultiMap.caseInsensitiveMultiMap()
                    .add("grant_type", "client_credentials")
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret);
            return tokenClient
                    .execute(web -> web.post("/oauth/token")
                            .sendForm(form)
                            .expecting(HttpExpectations.success())
                            .map(HttpResponse::bodyAsJsonObject))
                    .map(json -> {
                        synchronized (this) {
                            token = json.getString("access_token");
                            long lifetime = Duration.ofSeconds(json.getLong("expires_in"))
                                    .minus(SKEW)
                                    .toNanos();
                            expiresAtNanos = System.nanoTime() + lifetime;
                            return token;
                        }
                    });
        }
    }

    @Test
    void apiCallsCarryTheCachedBearerToken() {
        // Given a token server that answers slowly and an API server that records what it receives
        RecordingServer tokenServer = support.server((rq, body) -> support.vertx.setTimer(100, id -> rq.response()
                .putHeader("Content-Type", "application/json")
                .end("{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        RecordingServer apiServer = support.server((rq, body) -> rq.response().end("ok"));
        HttpClient authClient = HttpClientBuilder.create(support.vertx)
                .name("auth")
                .baseUrl(tokenServer.origin())
                .build();
        support.track(authClient::close);
        HttpClient apiClient = HttpClientBuilder.create(support.vertx)
                .name("api")
                .baseUrl(apiServer.origin())
                .credentials(new ClientCredentialsSource(authClient, "my-client", "my-secret"))
                .build();
        support.track(apiClient::close);

        // When the application makes two concurrent calls and then one more
        List<Future<Integer>> concurrent = List.of(call(apiClient, "/a"), call(apiClient, "/b"));
        concurrent.forEach(f -> assertThat(await(f)).isEqualTo(200));
        assertThat(await(call(apiClient, "/c"))).isEqualTo(200);

        // Then every API call carries the bearer token
        assertThat(apiServer.requests).hasSize(3);
        assertThat(apiServer.requests)
                .allSatisfy(r -> assertThat(r.headers().get("Authorization")).isEqualTo("Bearer tok-1"));

        // And the token endpoint was called exactly once, with the client-credentials form
        assertThat(tokenServer.requests).hasSize(1);
        RecordedRequest tokenRequest = tokenServer.requests.get(0);
        assertThat(tokenRequest.target()).isEqualTo("POST /oauth/token");
        assertThat(tokenRequest.headers().get("Authorization")).isNull();
        assertThat(tokenRequest.body().toString())
                .contains("grant_type=client_credentials", "client_id=my-client", "client_secret=my-secret");
    }

    private static Future<Integer> call(HttpClient client, String path) {
        return client.execute(web ->
                web.get(path).send().expecting(HttpExpectations.success()).map(HttpResponse::statusCode));
    }
}
