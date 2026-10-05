// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.rest.client.interceptor.RestClientAttemptCompletion;
import dev.vertique.rest.client.interceptor.RestClientContextCapturer;
import dev.vertique.rest.client.interceptor.RestClientOperation;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests that {@link RestClientContextCapturer} receives the registered client interface, not only the
 * declaring interface of the invoked operation: at build time through
 * {@link RestClientContextCapturer#validateOperation} and per attempt through the operation-aware
 * {@code onAttemptCompleted}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RestClientCapturerOperationTest {

    /** Base contract whose operations a client interface inherits. */
    interface BaseClient {

        /** An operation declared on the base interface. */
        @GET
        @Path("/ping")
        Future<String> ping();
    }

    /** Client interface whose only operation is inherited and which declares one of its own. */
    @RestClient(name = "derived-client")
    interface DerivedClient extends BaseClient {

        /** An operation declared on the client interface itself. */
        @GET
        @Path("/own")
        Future<String> own();
    }

    /** Capturer that records what the dispatcher and builder hand it. */
    static final class RecordingCapturer implements RestClientContextCapturer<Object> {

        final List<RestClientOperation> validated = new CopyOnWriteArrayList<>();
        final List<RestClientOperation> completed = new CopyOnWriteArrayList<>();
        RuntimeException validationFailure;

        @Override
        public Object captureRequestContext() {
            return null;
        }

        @Override
        public void onAttemptCompleted(
                Object capturedContext, RestClientRequestContext request, RestClientAttemptCompletion completion) {}

        @Override
        public void onAttemptCompleted(
                Object capturedContext,
                RestClientRequestContext request,
                RestClientAttemptCompletion completion,
                RestClientOperation operation) {
            completed.add(operation);
        }

        @Override
        public void validateOperation(RestClientOperation operation) {
            validated.add(operation);
            if (validationFailure != null) {
                throw validationFailure;
            }
        }
    }

    private static Vertx vertx;
    private static HttpServer server;

    @BeforeAll
    static void start() throws Exception {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer()
                .requestHandler(request -> request.response().end("\"ok\""))
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private RestClientBuilder builder(RecordingCapturer capturer) {
        return new RestClientBuilder(vertx)
                .baseUrl("http://127.0.0.1:" + server.actualPort())
                .registerCapturer(capturer);
    }

    @Test
    @DisplayName("build validates every operation, inherited included, against the registered client interface")
    void buildValidatesEveryOperationAgainstTheClientInterface() {
        RecordingCapturer capturer = new RecordingCapturer();

        builder(capturer).build(DerivedClient.class);

        assertThat(capturer.validated).extracting(o -> o.method().name()).containsExactlyInAnyOrder("ping", "own");
        assertThat(capturer.validated).allSatisfy(o -> {
            assertThat(o.clientType()).isSameAs(DerivedClient.class);
            assertThat(o.clientName()).isEqualTo("derived-client");
        });
        assertThat(capturer.validated)
                .filteredOn(o -> o.method().name().equals("ping"))
                .singleElement()
                .satisfies(o -> assertThat(o.method().declaringType()).isSameAs(BaseClient.class));
    }

    @Test
    @DisplayName("a capturer rejecting an operation fails build, before a client exists")
    void validationFailurePropagatesOutOfBuild() {
        RecordingCapturer capturer = new RecordingCapturer();
        capturer.validationFailure = new IllegalStateException("policy 'nope' is not defined");

        assertThatThrownBy(() -> builder(capturer).build(DerivedClient.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("an attempt of an inherited operation reaches the capturer with the client interface as its type")
    void inheritedOperationAttemptCarriesTheClientInterface() throws Exception {
        RecordingCapturer capturer = new RecordingCapturer();
        DerivedClient client = builder(capturer).build(DerivedClient.class);

        client.ping().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(capturer.completed).singleElement().satisfies(o -> {
            assertThat(o.clientType()).isSameAs(DerivedClient.class);
            assertThat(o.clientName()).isEqualTo("derived-client");
            assertThat(o.method().name()).isEqualTo("ping");
            assertThat(o.method().declaringType()).isSameAs(BaseClient.class);
        });
    }
}
