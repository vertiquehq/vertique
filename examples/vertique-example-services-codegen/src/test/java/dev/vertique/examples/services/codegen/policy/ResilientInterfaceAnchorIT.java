// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.examples.services.codegen.policy.service.ResilientAttempts;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Proves that a {@code @Resilient} anchor on a service contract interface method is applied once
 * per call when a concrete class in the same compilation implements the contract: the retry runs
 * the body its declared number of times, not the product of the AOP pipeline and the service
 * dispatch pipeline.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ResilientInterfaceAnchorIT {

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(
                    new PolicyTestComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("resilience", new JsonObject().put("policies", new JsonObject())));

    @Test
    @DisplayName("an interface retry runs the implementation body once per attempt, not once per wrapper")
    void interfaceRetryRunsTheBodyOncePerAttempt() throws Exception {
        PolicyTestComponent component = app.component();
        ResilientAttempts attempts = component.resilientAttempts();
        attempts.reset();

        component
                .resilientService()
                .alwaysFails("r-1")
                .toCompletionStage()
                .toCompletableFuture()
                .handle((value, failure) -> null)
                .get(15, TimeUnit.SECONDS);

        // maxRetries = 2 means three attempts in one pipeline; two pipelines would run nine
        assertEquals(3, attempts.count());
    }
}
