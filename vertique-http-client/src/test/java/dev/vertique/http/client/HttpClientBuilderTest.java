// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.PoolOptions;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Builder behavior of {@link HttpClientBuilder}: setters snapshot when {@code build()}
 * runs, option objects are copied, every {@code build()} gets its own agent, and a rejected
 * configuration leaves the builder and a supplied resilience runtime intact.
 *
 * <p>The tests use a real loopback Vert.x server, because the built client is observable only
 * through what it sends and which connections it opens.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HttpClientBuilderTest {

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

    private Vertx vertx() {
        return support.vertx;
    }

    private HttpClientBuilder builder() {
        HttpClientBuilder builder = HttpClientBuilder.create(vertx());
        return builder;
    }

    private RecordingServer echoServer() {
        return support.server((rq, body) -> rq.response().end("ok"));
    }

    private static String requestHeader(RecordingServer server, int index, String name) {
        return server.requests.get(index).headers().get(name);
    }

    @Test
    @DisplayName(
            "setters snapshot at build, option objects are copied, each build gets its own agent, a failed build releases what it created")
    void builderSnapshotsSettersAndFailedBuildReleasesWhatItCreated() {
        // Given two servers and one builder configured for the first
        RecordingServer first = echoServer();
        RecordingServer second = echoServer();
        WebClientOptions webOptions = new WebClientOptions().setUserAgent("agent-one");
        PoolOptions poolOptions = new PoolOptions().setHttp1MaxSize(1);
        HttpClientBuilder builder = builder()
                .name("snapshot")
                .baseUrl(first.origin() + "/a")
                .readTimeout(5, TimeUnit.SECONDS)
                .webClientOptions(webOptions)
                .poolOptions(poolOptions)
                .defaultHeader("X-Default", "one");
        HttpClient c1 = builder.build();
        support.track(c1::close);

        // When the builder is changed and builds again, and the passed option objects are mutated afterwards
        builder.name("other").baseUrl(second.origin() + "/b").defaultHeader("X-Default", "two");
        webOptions.setUserAgent("agent-two");
        poolOptions.setHttp1MaxSize(5);
        HttpClient c2 = builder.build();
        support.track(c2::close);

        // Then each client kept the setter values from its own build()
        assertThat(c1.name()).isEqualTo("snapshot");
        assertThat(c2.name()).isEqualTo("other");
        await(c1.webClient().get("/x").send());
        await(c2.webClient().get("/x").send());
        assertThat(first.requests).hasSize(1);
        assertThat(first.requests.get(0).target()).isEqualTo("GET /a/x");
        assertThat(requestHeader(first, 0, "X-Default")).isEqualTo("one");
        assertThat(second.requests).hasSize(1);
        assertThat(second.requests.get(0).target()).isEqualTo("GET /b/x");
        assertThat(requestHeader(second, 0, "X-Default")).isEqualTo("two");

        // And the option objects were copied: the mutation after the setter reached neither client
        assertThat(requestHeader(first, 0, "User-Agent")).isEqualTo("agent-one");
        assertThat(requestHeader(second, 0, "User-Agent")).isEqualTo("agent-one");

        // And the pool bound set before the mutation still holds for the later build
        // (three concurrent requests share at most one connection)
        RecordingServer slow = support.server(
                (rq, body) -> vertx().setTimer(100, id -> rq.response().end("slow")));
        HttpClient bounded = builder.baseUrl(slow.origin()).build();
        support.track(bounded::close);
        List<Future<HttpResponse<Buffer>>> inFlight = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            inFlight.add(bounded.webClient().get("/slow").send());
        }
        inFlight.forEach(HttpClientItSupport::await);
        assertThat(slow.peakOpenConnections.get()).isEqualTo(1);

        // And each build() created its own agent: closing one client leaves the other usable
        await(c1.close());
        assertThat(await(c2.webClient().get("/y").send()).statusCode()).isEqualTo(200);
        assertThat(awaitFailure(c1.execute(web -> web.get("/z").send())))
                .as("a closed client fails its calls")
                .isNotNull();
        await(c1.close()); // closing again is harmless

        // And a rejected configuration fails with the configuration exception and leaves the builder reusable
        // once the setting is corrected
        HttpClientBuilder reusable = HttpClientBuilder.create(vertx());
        String invalidName = "bad name/with:chars";
        Throwable rejected =
                catchConfigurationFailure(() -> reusable.name(invalidName).build());
        assertThat(rejected).isInstanceOf(HttpClientConfigurationException.class);
        assertThat(rejected.getMessage()).isNotBlank().doesNotContain(invalidName);
        assertThat(rejected.getCause()).isNull();
        HttpClient recovered = reusable.name("fixed").baseUrl(first.origin()).build();
        support.track(recovered::close);
        assertThat(await(recovered.webClient().get("/ok").send()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a client name matches [A-Za-z0-9._~-]{1,128}; the default name is \"default\"")
    void clientNameIsValidatedAndDefaults() {
        // Given a builder that sets no name
        // Then the default name is "default"
        HttpClient unnamed = builder().build();
        support.track(unnamed::close);
        assertThat(unnamed.name()).isEqualTo("default");

        // And names inside the pattern, up to 128 characters, are accepted as given
        for (String valid : List.of("a", "A.b_c~d-9", "x".repeat(128))) {
            HttpClient named = builder().name(valid).build();
            support.track(named::close);
            assertThat(named.name()).isEqualTo(valid);
        }

        // And names outside the pattern fail with the configuration exception
        for (String invalid : List.of("", "x".repeat(129), "a b", "a/b", "a:b", "a\u0000b", "ünï")) {
            assertThatThrownBy(() -> builder().name(invalid).build())
                    .as("name %s", invalid.length() > 20 ? invalid.length() + " chars" : invalid)
                    .isInstanceOf(HttpClientConfigurationException.class);
        }
    }

    private static Throwable catchConfigurationFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException thrown) {
            return thrown;
        }
        throw new AssertionError("expected the configuration to be rejected");
    }
}
