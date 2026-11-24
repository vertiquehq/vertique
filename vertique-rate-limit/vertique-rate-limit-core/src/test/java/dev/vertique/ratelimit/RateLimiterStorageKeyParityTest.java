// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The storage key the limiter hands the backend is the canonical storage input, byte for byte. */
class RateLimiterStorageKeyParityTest {

    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    @Test
    @DisplayName("the backend request's storage key equals RateLimitStorageIdentity.canonicalInput")
    void backendRequestStorageKeyEqualsCanonicalInput() {
        RateLimitPolicy policy = new RateLimitPolicy(
                "parity-quota",
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r7",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofSeconds(60))));
        List<RateLimitBackendRequest> requests = new ArrayList<>();
        RateLimitBackend backend = request -> {
            requests.add(request);
            return Future.failedFuture(new RuntimeException("capture-only"));
        };
        RateLimiters rateLimiters = RateLimitersUnitFixtures.withBackend(VERTX, backend, Set.of(), policy);
        RateLimitKey key = RateLimitKey.of("tenant:42", "user/7");

        rateLimiters.limiter(policy.name()).acquire(key);

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).storageKey())
                .isEqualTo(RateLimitStorageIdentity.canonicalInput(policy.name(), policy.revision(), key));
    }
}
