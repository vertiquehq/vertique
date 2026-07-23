// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RestClientContextCapturer} SPI, focused on the four-argument
 * {@code onAttemptCompleted} overload added for dispatcher-owned {@link MethodMetadata} (issue
 * #120, GH plan §3a D3).
 *
 * <p>Verifies that the four-arg default method delegates to the pre-existing three-arg method
 * unchanged, so implementations written before this overload was introduced keep compiling and
 * behaving identically.
 */
class RestClientContextCapturerTest {

    /** A tiny local interface used only to obtain a real {@link MethodMetadata} instance. */
    private interface Probe {
        void ping();
    }

    private static MethodMetadata probeMethodMetadata() {
        try {
            return new ReflectiveMethodMetadata(Probe.class.getMethod("ping"), List.of());
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A {@link RestClientContextCapturer} that implements only the pre-existing three-arg
     * {@code onAttemptCompleted} method — exactly the shape every capturer had before the
     * four-arg overload was introduced.
     */
    private static final class ThreeArgOnlyCapturer implements RestClientContextCapturer<String> {

        private final AtomicInteger threeArgCalls = new AtomicInteger();
        private String lastCapturedContext;
        private RestClientRequestContext lastRequest;
        private RestClientAttemptCompletion lastCompletion;

        @Override
        public String captureRequestContext() {
            return "captured";
        }

        @Override
        public void onAttemptCompleted(
                String capturedContext, RestClientRequestContext request, RestClientAttemptCompletion completion) {
            threeArgCalls.incrementAndGet();
            this.lastCapturedContext = capturedContext;
            this.lastRequest = request;
            this.lastCompletion = completion;
        }
    }

    private static RestClientRequestContext requestContext() {
        return new RestClientRequestContext("GET", "https://api.example.com/v1/resource", null, "svc", "op");
    }

    private static RestClientAttemptCompletion completion() {
        RestClientResponseContext response =
                new RestClientResponseContext(200, "OK", Buffer.buffer(), MultiMap.caseInsensitiveMultiMap());
        RestClientAttemptTarget target = new RestClientAttemptTarget("https", "svc", 443, "/resource");
        return new RestClientAttemptCompletion(
                response, null, "call-1", 1, 5L, Instant.parse("2026-06-07T10:00:00Z"), target);
    }

    @Test
    @DisplayName("four-arg onAttemptCompleted default delegates to the three-arg method, ignoring operation")
    void fourArgOverloadDefaultDelegatesToThreeArg() {
        ThreeArgOnlyCapturer capturer = new ThreeArgOnlyCapturer();
        String cap = capturer.captureRequestContext();
        RestClientRequestContext request = requestContext();
        RestClientAttemptCompletion completion = completion();
        MethodMetadata operation = probeMethodMetadata();

        capturer.onAttemptCompleted(cap, request, completion, operation);

        assertEquals(
                1,
                capturer.threeArgCalls.get(),
                "the default four-arg method must delegate to the three-arg method exactly once");
        assertSame(cap, capturer.lastCapturedContext, "the captured context must pass through unchanged");
        assertSame(request, capturer.lastRequest, "the request context must pass through unchanged");
        assertSame(completion, capturer.lastCompletion, "the completion must pass through unchanged");
    }
}
