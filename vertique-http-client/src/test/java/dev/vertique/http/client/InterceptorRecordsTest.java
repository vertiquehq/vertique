// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.payload.PayloadKind;
import dev.vertique.core.payload.PayloadSources;
import dev.vertique.http.client.interceptor.HttpClientAttemptCompletion;
import dev.vertique.http.client.interceptor.HttpClientAttemptTarget;
import dev.vertique.http.client.interceptor.HttpClientRequestContext;
import dev.vertique.http.client.interceptor.HttpClientResponseHead;
import io.vertx.core.MultiMap;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the interceptor records: their compact-constructor invariants with constant messages,
 * copy-on-write {@code with...} methods and a {@code toString} that never prints a header value, path,
 * query or body.
 */
class InterceptorRecordsTest {

    private static final String SECRET = "s3cret-value";
    private static final String URI_WITH_SECRETS = "http://api.example.test/secret/path?token=" + SECRET;

    private static HttpClientAttemptTarget target() {
        return new HttpClientAttemptTarget("http", "api.example.test", 80);
    }

    private static HttpClientRequestContext context(MultiMap headers) {
        return new HttpClientRequestContext("GET", URI_WITH_SECRETS, headers, "client", "call-1", Map.of());
    }

    private static HttpClientAttemptCompletion completion(
            Integer status, Throwable error, String callId, int ordinal, long durationMs, Instant at) {
        return new HttpClientAttemptCompletion(status, error, callId, ordinal, durationMs, at, target());
    }

    @Test
    @DisplayName("record invariants reject nulls and invalid values with constant messages")
    void recordInvariantsRejectNullsAndInvalidValuesWithConstantMessages() {
        // Given valid components and a sentinel value that must never be echoed
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        Instant now = Instant.now();

        // When each required request-context component is null
        // Then the constructor throws NullPointerException
        assertThatThrownBy(() -> new HttpClientRequestContext(null, "/x", headers, "c", "id", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpClientRequestContext("GET", null, headers, "c", "id", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpClientRequestContext("GET", "/x", null, "c", "id", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpClientRequestContext("GET", "/x", headers, null, "id", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpClientRequestContext("GET", "/x", headers, "c", null, Map.of()))
                .isInstanceOf(NullPointerException.class);

        // And each required completion component is null
        assertThatThrownBy(() -> completion(200, null, null, 1, 0, now)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> completion(200, null, "id", 1, 0, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpClientAttemptCompletion(200, null, "id", 1, 0, now, null))
                .isInstanceOf(NullPointerException.class);

        // And the ordinal, the duration and the status are out of range, or neither status nor error is given
        Throwable ordinalZero = catchInvalid(() -> completion(200, null, "id", 0, 0, now));
        Throwable ordinalNegative = catchInvalid(() -> completion(200, null, "id", -7, 0, now));
        Throwable durationNegative = catchInvalid(() -> completion(200, null, "id", 1, -1, now));
        Throwable durationVeryNegative = catchInvalid(() -> completion(200, null, "id", 1, -987_654, now));
        Throwable statusLow = catchInvalid(() -> completion(99, null, "id", 1, 0, now));
        Throwable statusHigh = catchInvalid(() -> completion(600, null, "id", 1, 0, now));
        Throwable statusZero = catchInvalid(() -> completion(0, null, "id", 1, 0, now));
        Throwable neither = catchInvalid(() -> completion(null, null, "id", 1, 0, now));

        // Then each is an IllegalArgumentException whose message is constant and carries no offending value
        assertThat(ordinalNegative.getMessage()).isEqualTo(ordinalZero.getMessage());
        assertThat(durationVeryNegative.getMessage()).isEqualTo(durationNegative.getMessage());
        assertThat(statusHigh.getMessage()).isEqualTo(statusLow.getMessage());
        assertThat(statusZero.getMessage()).isEqualTo(statusLow.getMessage());
        assertThat(neither.getMessage()).isNotNull();
        for (Throwable failure : new Throwable[] {ordinalZero, ordinalNegative, durationVeryNegative, statusHigh}) {
            assertThat(failure.getMessage()).doesNotContain("-7", "987654", "600");
        }

        // And the boundary values are accepted
        assertThat(completion(100, null, "id", 1, 0, now).statusCode()).isEqualTo(100);
        assertThat(completion(599, null, "id", 1, 0, now).statusCode()).isEqualTo(599);
        assertThat(completion(null, new IllegalStateException("x"), "id", 1, 0, now)
                        .error())
                .isInstanceOf(IllegalStateException.class);
    }

    private static Throwable catchInvalid(Runnable construction) {
        Throwable[] thrown = new Throwable[1];
        assertThatThrownBy(construction::run).isInstanceOf(IllegalArgumentException.class);
        try {
            construction.run();
        } catch (RuntimeException e) {
            thrown[0] = e;
        }
        return thrown[0];
    }

    @Test
    @DisplayName("the request context copies on write and prints no header value, path or query")
    void requestContextCopiesOnWriteAndToStringPrintsNoHeaderValuePathOrQuery() {
        // Given a context with one header and one attribute
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().add("X-Key", SECRET);
        HttpClientRequestContext original = new HttpClientRequestContext(
                "GET", URI_WITH_SECRETS, headers, "client", "call-1", Map.of("attr", "one"));

        // When it is changed through withHeader, withHeaders and withAttribute
        HttpClientRequestContext withHeader =
                original.withHeader("X-Key", "replaced").withHeader("X-New", "n");
        HttpClientRequestContext withHeaders =
                original.withHeaders(MultiMap.caseInsensitiveMultiMap().add("X-Other", "o"));
        HttpClientRequestContext withAttribute =
                original.withAttribute("attr", "two").withAttribute("more", "m");

        // Then each result carries the change and the original is untouched
        assertThat(withHeader.headers().get("X-Key")).isEqualTo("replaced");
        assertThat(withHeader.headers().get("x-new")).isEqualTo("n");
        assertThat(withHeaders.headers().names()).containsExactlyInAnyOrder("X-Other");
        assertThat(withAttribute.attributes()).containsEntry("attr", "two").containsEntry("more", "m");
        assertThat(original.headers().getAll("X-Key")).containsExactly(SECRET);
        assertThat(original.headers().contains("X-New")).isFalse();
        assertThat(original.attributes()).containsOnly(Map.entry("attr", "one"));
        assertThat(original.uri()).isEqualTo(URI_WITH_SECRETS);

        // And the copy keeps identity and the other components
        assertThat(withHeader.method()).isEqualTo("GET");
        assertThat(withHeader.uri()).isEqualTo(URI_WITH_SECRETS);
        assertThat(withHeader.clientName()).isEqualTo("client");
        assertThat(withHeader.callId()).isEqualTo("call-1");
        assertThat(withAttribute.headers().get("X-Key")).isEqualTo(SECRET);

        // And no toString of any record prints a header value, a path, a query or an attribute value
        HttpClientResponseHead head = new HttpClientResponseHead(
                200, "OK", MultiMap.caseInsensitiveMultiMap().add("X-Reply", SECRET));
        HttpClientAttemptCompletion done = completion(200, null, "call-1", 1, 5, Instant.now());
        for (String text : new String[] {
            original.toString(),
            withHeader.toString(),
            withAttribute.toString(),
            head.toString(),
            done.toString(),
            target().toString()
        }) {
            assertThat(text)
                    .doesNotContain(SECRET)
                    .doesNotContain("/secret/path")
                    .doesNotContain("token=");
        }
        assertThat(original.toString()).doesNotContain("one");
    }

    @Test
    @DisplayName("a null body becomes absent and the seven-argument constructor keeps both bodies absent")
    void aNullBodyBecomesAbsentAndTheSevenArgumentConstructorKeepsBothBodiesAbsent() {
        // Given a completion built with null bodies and one built through the seven-argument constructor
        HttpClientAttemptTarget target = new HttpClientAttemptTarget("http", "h", 80);
        HttpClientAttemptCompletion withNulls =
                new HttpClientAttemptCompletion(200, null, "call-1", 1, 5, Instant.now(), target, null, null);
        HttpClientAttemptCompletion withoutBodies =
                new HttpClientAttemptCompletion(200, null, "call-1", 1, 5, Instant.now(), target);

        // Then every body is an absent payload, never null
        assertThat(withNulls.requestBody().kind()).isEqualTo(PayloadKind.ABSENT);
        assertThat(withNulls.responseBody().kind()).isEqualTo(PayloadKind.ABSENT);
        assertThat(withoutBodies.requestBody().kind()).isEqualTo(PayloadKind.ABSENT);
        assertThat(withoutBodies.responseBody().kind()).isEqualTo(PayloadKind.ABSENT);
    }

    @Test
    @DisplayName("toString of a completion carrying bodies prints neither")
    void toStringOfACompletionCarryingBodiesPrintsNeither() {
        // Given a completion carrying two buffered bodies
        HttpClientAttemptCompletion completion = new HttpClientAttemptCompletion(
                200,
                null,
                "call-1",
                1,
                5,
                Instant.now(),
                new HttpClientAttemptTarget("http", "h", 80),
                PayloadSources.buffered("zz-request-body".getBytes(StandardCharsets.UTF_8), "text/plain"),
                PayloadSources.buffered("zz-response-body".getBytes(StandardCharsets.UTF_8), "text/plain"));

        // Then the printed form holds neither body
        assertThat(completion.toString()).doesNotContain("zz-request-body").doesNotContain("zz-response-body");
        assertThat(completion.requestBody().kind()).isEqualTo(PayloadKind.BUFFERED);
    }
}
