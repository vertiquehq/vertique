// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the view a run compares with its intended configuration. */
class EffectiveConfigTest {

    @Test
    @DisplayName("the view reports the typed values, among them the application name and the migration mode")
    void reportsTypedValues() {
        JsonObject view = TestConfigs.effective().toJson();

        assertEquals("load-node-1", view.getJsonObject("db").getString("applicationName"));
        assertEquals(20, view.getJsonObject("db").getInteger("maxPoolSize"));
        assertEquals("DISABLED", view.getJsonObject("flyway").getString("mode"));
        JsonObject executor = view.getJsonObject("executor");
        assertEquals(5_000L, executor.getLong("pollIntervalMs"));
        assertEquals(8, executor.getInteger("concurrency"));
        assertEquals(true, executor.getBoolean("enabled"));
        JsonObject intake = view.getJsonObject("recording").getJsonObject("intake");
        assertEquals(true, intake.getBoolean("enabled"));
        assertEquals(10_000, intake.getInteger("highMark"));
        JsonObject relay = view.getJsonObject("relay");
        assertEquals("LISTEN_NOTIFY", relay.getString("strategy"));
        assertEquals(1_000L, relay.getLong("pollingIntervalMs"));
        assertEquals(20L, view.getJsonObject("load").getLong("handlerDelayMs"));
    }

    @Test
    @DisplayName("a baseline graph reports no observer")
    void baselineBindsNoObserver() {
        JsonObject observers = TestConfigs.effective().toJson().getJsonObject("observers");

        assertEquals(0, observers.getInteger("executor"));
        assertEquals(0, observers.getInteger("outboxPublish"));
    }

    @Test
    @DisplayName("a bound observer is counted")
    void countsBoundObservers() {
        EffectiveConfig config = TestConfigs.effective(
                Set.of(mock(WorkflowExecutorObserver.class)), Set.of(mock(OutboxPublishObserver.class)));

        JsonObject observers = config.toJson().getJsonObject("observers");

        assertEquals(1, observers.getInteger("executor"));
        assertEquals(1, observers.getInteger("outboxPublish"));
    }

    @Test
    @DisplayName("the view never carries a secret")
    void carriesNoSecret() {
        String rendered = TestConfigs.effective().toJson().encode();

        assertFalse(rendered.contains("never-printed"), rendered);
        assertFalse(rendered.contains("password"), rendered);
    }
}
