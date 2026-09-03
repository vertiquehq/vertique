// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.examples.services.service.ResilienceProbeServiceHandler;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Proves named resilience policy precedence through the generated service-handler proxy. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ResilienceProbeIT {

    private static final String CONFIGURED_KEY = "configured";
    private static final String INLINE_KEY = "inline";

    @RegisterExtension
    static final VertiqueAppExtension configuredApp = VertiqueAppExtension.forFactory(
                    new AppComponentVertiqueComponentFactory())
            .withConfig(probeConfig(true));

    @RegisterExtension
    static final VertiqueAppExtension timeoutOnlyApp = VertiqueAppExtension.forFactory(
                    new AppComponentVertiqueComponentFactory())
            .withConfig(probeConfig(false));

    @Test
    @DisplayName("generated proxy honors the named tier over the inline retry declaration")
    void retriesThroughTheGeneratedProxyAndHonorsTheNamedTier() {
        ResilienceProbeServiceHandler configuredHandler =
                configuredApp.<AppComponent>component().resilienceProbeServiceHandler();
        assertEquals(
                "ok:configured",
                configuredHandler
                        .probe(CONFIGURED_KEY)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());
        assertEquals(5, configuredHandler.attemptsFor(CONFIGURED_KEY));

        ResilienceProbeServiceHandler timeoutOnlyHandler =
                timeoutOnlyApp.<AppComponent>component().resilienceProbeServiceHandler();
        CompletionException failure = assertThrows(CompletionException.class, () -> timeoutOnlyHandler
                .probe(INLINE_KEY)
                .toCompletionStage()
                .toCompletableFuture()
                .join());
        assertEquals("transient failure", failure.getCause().getMessage());
        assertEquals(3, timeoutOnlyHandler.attemptsFor(INLINE_KEY));
    }

    private static JsonObject probeConfig(boolean namedRetry) {
        JsonObject policy = namedRetry
                ? new JsonObject()
                        .put(
                                "retry",
                                new JsonObject()
                                        .put("maxRetries", 5)
                                        .put("delayMs", 1)
                                        .put("maxDelayMs", 1))
                : new JsonObject().put("timeout", new JsonObject().put("valueMs", 5000));
        return new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("management", new JsonObject().put("enabled", false))
                .put(
                        "rateLimit",
                        new JsonObject()
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put("rate-limit-probe-shared", RateLimitTestPolicies.probeShared())
                                                .put("composition", RateLimitTestPolicies.composition())))
                .put("resilience", new JsonObject().put("policies", new JsonObject().put("probe", policy)))
                .put(
                        "services",
                        new JsonObject()
                                .put(
                                        "contracts",
                                        new JsonObject()
                                                .put(
                                                        "resilience",
                                                        new JsonObject()
                                                                .put("probe", new JsonObject().put("instances", 1)))));
    }
}
