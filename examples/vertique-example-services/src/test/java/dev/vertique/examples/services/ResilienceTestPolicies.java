// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import io.vertx.core.json.JsonObject;

/** Shared named resilience-policy fixture required by every booted example application graph. */
final class ResilienceTestPolicies {

    private ResilienceTestPolicies() {}

    /**
     * @return the inline-equivalent retry policy used by the resilience probe handler
     */
    static JsonObject probeConfig() {
        return new JsonObject()
                .put(
                        "policies",
                        new JsonObject()
                                .put(
                                        "probe",
                                        new JsonObject()
                                                .put(
                                                        "retry",
                                                        new JsonObject()
                                                                .put("maxRetries", 2)
                                                                .put("delayMs", 1)
                                                                .put("maxDelayMs", 1))));
    }
}
