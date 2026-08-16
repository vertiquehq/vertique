// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.webhook.webhook.DeliverWebhookJob;
import dev.vertique.job.delayed.DelayedJobClientFactory;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check that the {@code vertique-codegen-delayed-job} processor generated a static proxy
 * for {@link DeliverWebhookJob} and that {@link DelayedJobClientFactory} selects it (rather than the
 * JDK dynamic proxy), plus the {@code GeneratedDelayedJobClientsModule} that binds the contract in
 * the Dagger graph. Proves the example opted into codegen via its
 * {@code annotationProcessorPaths}.
 *
 * <p>The factory only passes the service into the proxy constructor (it never invokes it during
 * {@code create}), so a {@code null} service is sufficient for selection.
 */
class DelayedJobCodegenTest {

    @Test
    @DisplayName("factory.create returns the generated DeliverWebhookJob proxy, not a JDK proxy")
    void factorySelectsGeneratedProxy() {
        DelayedJobClientFactory factory = new DelayedJobClientFactory(null, Map.of());

        Object client = factory.create(DeliverWebhookJob.class);

        assertTrue(
                client.getClass().getName().endsWith("_DelayedJobProxy"),
                "expected a generated *_DelayedJobProxy, got "
                        + client.getClass().getName());
    }

    @Test
    @DisplayName("GeneratedDelayedJobClientsModule is present and binds DeliverWebhookJob")
    void generatedClientsModuleBindsTheContract() {
        Class<?> module = assertDoesNotThrow(
                () -> Class.forName("dev.vertique.examples.webhook.webhook.GeneratedDelayedJobClientsModule"),
                "GeneratedDelayedJobClientsModule must be on the classpath");

        assertNotNull(assertDoesNotThrow(
                () -> module.getDeclaredMethod("provideDeliverWebhookJobClient", DelayedJobClientFactory.class),
                "the module must declare a factory-delegating binding for DeliverWebhookJob"));
    }
}
