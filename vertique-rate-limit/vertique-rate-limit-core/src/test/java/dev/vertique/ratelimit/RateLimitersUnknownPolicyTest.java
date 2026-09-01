// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

/** TP-003: an unknown policy name must fail {@code limiter(...)} synchronously, before any {@code Future} exists. */
class RateLimitersUnknownPolicyTest {

    @Test
    void shouldFailSynchronouslyForUnknownPolicyName() {
        Vertx vertx = Vertx.vertx();
        try {
            RateLimitPolicy known = new RateLimitPolicy("known", true, RateLimitMode.LOCAL, "r1", 10L, 10L, 1_000L);
            RateLimiters rateLimiters = RateLimitersUnitFixtures.withPolicies(vertx, known);

            assertThatThrownBy(() -> rateLimiters.limiter("does-not-exist"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(rateLimiters.limiter("known").policyName()).isEqualTo("known");
        } finally {
            vertx.close();
        }
    }
}
