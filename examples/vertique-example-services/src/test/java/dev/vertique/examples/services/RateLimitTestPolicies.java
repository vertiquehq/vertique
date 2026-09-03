// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import io.vertx.core.json.JsonObject;

/**
 * Shared {@code rateLimit.policies} fixture for {@link
 * dev.vertique.examples.services.service.RateLimitProbeService#POLICY_NAME} (T013).
 *
 * <p>{@code RateLimitProbeServiceHandler#probe} carries {@code @RateLimited}, and the aspect
 * resolves its policy handle at proxy-construction time (contracts/rate-limit-aop.md —
 * constructor-time fail-fast), so every {@code VertiqueAppExtension}-booted test in this module
 * must declare this policy, whether or not the test exercises the probe. {@link
 * dev.vertique.examples.services.RateLimitProbeIT} keeps its own local copy instead of using this
 * fixture, since its capacity is tightly coupled to its own request-count assertions.
 */
final class RateLimitTestPolicies {

    private RateLimitTestPolicies() {}

    /**
     * @return the {@code rate-limit-probe-shared} policy: a small token-bucket, greedy-refilled
     *     slowly enough to stay inert for any single test's duration
     */
    static JsonObject probeShared() {
        return new JsonObject()
                .put("enabled", true)
                .put("mode", "LOCAL")
                .put("failureMode", "OPEN")
                .put("revision", "v1")
                .put("defaultCost", 1)
                .put(
                        "algorithm",
                        new JsonObject()
                                .put("type", "TOKEN_BUCKET")
                                .put("capacity", 2)
                                .put(
                                        "refill",
                                        new JsonObject()
                                                .put("type", "GREEDY")
                                                .put("tokens", 2)
                                                .put("periodMs", 3_600_000)));
    }

    /**
     * @return the high-capacity local policy used by the composition probe
     */
    static JsonObject composition() {
        return new JsonObject()
                .put("enabled", true)
                .put("mode", "LOCAL")
                .put("failureMode", "OPEN")
                .put("revision", "v1")
                .put("defaultCost", 1)
                .put(
                        "algorithm",
                        new JsonObject()
                                .put("type", "TOKEN_BUCKET")
                                .put("capacity", 100)
                                .put(
                                        "refill",
                                        new JsonObject()
                                                .put("type", "GREEDY")
                                                .put("tokens", 100)
                                                .put("periodMs", 3_600_000)));
    }
}
