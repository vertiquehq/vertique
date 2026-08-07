// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.json.JsonModule;
import dev.vertique.db.DbModule;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.examples.workflow.order.di.AppModule;
import dev.vertique.examples.workflow.order.di.OrderFulfillmentDefinitionModule;
import dev.vertique.examples.workflow.order.di.StubServicesModule;
import dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule;
import dev.vertique.job.cron.dagger.CronPersistenceModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.services.DispatchModule;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.services.compose.WorkflowOutboxComposeValidator;
import dev.vertique.workflow.services.di.WorkflowServicesModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Missing-SERVICE-handler startup contract test (§9.4 test 22).
 *
 * <p>Verifies that an application composed WITHOUT {@link
 * dev.vertique.inboxoutbox.services.TransactionalMessagingServiceModule} passes Dagger component
 * construction (lazy {@code @Singleton}) but throws {@link IllegalStateException} with the
 * documented message when {@link WorkflowOutboxComposeValidator} is first requested via the
 * component accessor.
 *
 * <p>This test uses a separate test-only {@code @Component} ({@link ComponentWithoutServiceModule})
 * that mirrors the production {@link dev.vertique.examples.workflow.order.AppComponent} but omits
 * {@code TransactionalMessagingServiceModule} — exactly the misconfiguration that the startup
 * contract guards against.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class MissingServiceHandlerIT {

    // --- Test-only component without TransactionalMessagingServiceModule ---

    /**
     * Test Dagger component that omits {@code TransactionalMessagingServiceModule}.
     *
     * <p>This simulates a misconfigured application. Dagger constructs the component
     * successfully (all bindings are satisfied at graph level), but the
     * {@link WorkflowOutboxComposeValidator} accessor will throw on first call because no
     * {@code SERVICE} {@link dev.vertique.inboxoutbox.OutboxDestinationHandler} is registered.
     */
    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                ConfigParsingModule.class,
                JsonModule.class,
                DeployerModule.class,
                ManagementModule.class,
                DbModule.class,
                DbPostgresqlModule.class,
                DbFlywayModule.class,
                DispatchModule.class,
                TransactionalMessagingPostgresqlModule.class,
                // TransactionalMessagingServiceModule intentionally omitted
                CronPersistenceModule.class,
                WorkflowPostgresqlModule.class,
                WorkflowServicesModule.class,
                OrderFulfillmentDefinitionModule.class,
                StubServicesModule.class,
                AppModule.class
            })
    interface ComponentWithoutServiceModule {

        /**
         * Returns the composition validator — will throw {@link IllegalStateException} when no
         * {@code SERVICE} handler is registered.
         *
         * @return the composition validator
         * @throws IllegalStateException if no SERVICE OutboxDestinationHandler is in the graph
         */
        WorkflowOutboxComposeValidator workflowComposeValidator();
    }

    // --- Tests ---

    @Test
    @DisplayName("component builds without TransactionalMessagingServiceModule (lazy @Singleton)")
    void componentBuildsWithoutServiceModule() {
        Vertx vertx = Vertx.vertx();
        try {
            var component = buildComponentWithoutServiceModule(vertx);
            assertNotNull(component, "component construction must succeed (Dagger bindings are lazy)");
        } finally {
            vertx.close();
        }
    }

    @Test
    @DisplayName("calling workflowComposeValidator() without SERVICE handler throws documented IllegalStateException")
    void composeValidatorThrowsWithoutServiceHandler() {
        Vertx vertx = Vertx.vertx();
        try {
            var component = buildComponentWithoutServiceModule(vertx);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    component::workflowComposeValidator,
                    "validator accessor must throw when no SERVICE handler is registered");

            String msg = ex.getMessage();
            assertNotNull(msg, "exception message must not be null");
            // Verify the documented message fragments are present.
            org.junit.jupiter.api.Assertions.assertTrue(
                    msg.contains("vertique-workflow-services requires a SERVICE OutboxDestinationHandler"),
                    "message must contain documented text about SERVICE handler; got: " + msg);
            org.junit.jupiter.api.Assertions.assertTrue(
                    msg.contains("TransactionalMessagingServiceModule"),
                    "message must reference TransactionalMessagingServiceModule; got: " + msg);
        } finally {
            vertx.close();
        }
    }

    // --- Helpers ---

    private static ComponentWithoutServiceModule buildComponentWithoutServiceModule(Vertx vertx) {
        var config = new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", "localhost")
                                .put("port", 5432)
                                .put("database", "test")
                                .put("user", "test")
                                .put("password", "test")
                                .put("maxPoolSize", 1))
                .put(
                        "flyway",
                        new JsonObject()
                                .put("mode", "VALIDATE")
                                .put("jdbcUrl", "jdbc:postgresql://localhost:5432/test")
                                .put("user", "test")
                                .put("password", "test"))
                .put(
                        "inboxOutbox",
                        new JsonObject()
                                .put(
                                        "relay",
                                        new JsonObject()
                                                .put("pollingIntervalMs", 1000)
                                                .put("batchSize", 10)
                                                .put("leaseTimeoutMs", 10000)
                                                .put("maxAttempts", 5)
                                                .put("backoffBaseDelayMs", 500)
                                                .put("backoffMaxDelayMs", 30000)
                                                .put("strategy", "POLLING")
                                                .put("instances", 1))
                                .put(
                                        "cleanup",
                                        new JsonObject()
                                                .put("publishedRetentionMs", 3600000)
                                                .put("deadLetterRetentionMs", 86400000)
                                                .put("inboxRetentionMs", 3600000)
                                                .put("cleanupIntervalMs", 300000)))
                .put(
                        "services",
                        new JsonObject()
                                .put("inventory", new JsonObject().put("instances", 1))
                                .put("payment", new JsonObject().put("instances", 1))
                                .put("shipping", new JsonObject().put("instances", 1))
                                .put("workflow", new JsonObject().put("signals", new JsonObject().put("instances", 1))))
                .put("management", new JsonObject().put("port", 0).put("host", "127.0.0.1"));

        return DaggerMissingServiceHandlerIT_ComponentWithoutServiceModule.builder()
                .vertxModule(new VertxModule(vertx, config))
                .build();
    }
}
