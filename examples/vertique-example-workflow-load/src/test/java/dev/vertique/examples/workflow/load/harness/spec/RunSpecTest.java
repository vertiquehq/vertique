// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.examples.workflow.load.config.LoadArm;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a run specification is read strictly: an unknown input fails the run before anything starts, whatever
 * its value, and every value the run uses, defaults included, appears in the resolved tree.
 */
class RunSpecTest {

    private static JsonObject minimal() {
        return new JsonObject()
                .put("name", "r1")
                .put("arm", "ON")
                .put("nodes", 2)
                .put("load", new JsonObject().put("kind", "fixed").put("ratePerSec", 100));
    }

    @Nested
    @DisplayName("strict reading")
    class Strict {

        @Test
        @DisplayName("a minimal specification takes the frozen defaults and records them")
        void defaultsAreResolved() {
            RunSpec spec = RunSpec.parse(minimal());

            assertEquals(LoadArm.ON, spec.arm());
            assertEquals(256, spec.node().heapMinMb());
            assertEquals(512, spec.node().heapMaxMb());
            assertEquals(20, spec.node().poolSize());
            assertEquals(30 + 150, spec.load().profile().endSec());
            assertEquals(180.0, spec.load().profile().windowEndSec());
            assertEquals(30.0, spec.load().profile().windowStartSec());
            assertEquals(256, spec.resolved().getJsonObject("node").getInteger("heapMinMb"));
            assertEquals(0.99, spec.resolved().getJsonObject("thresholds").getDouble("completionRateBar"));
            assertEquals(
                    "DROP_CREATE", spec.resolved().getJsonObject("database").getString("reset"));
            assertFalse(spec.collector().lockWatch(), "the lock watch is off unless a run asks for it");
        }

        @Test
        @DisplayName("the resolved tree parses again to the same specification")
        void resolvedRoundTrips() {
            RunSpec spec = RunSpec.parse(minimal());

            RunSpec again = RunSpec.parse(spec.resolved());

            assertEquals(spec.node(), again.node());
            assertEquals(spec.thresholds(), again.thresholds());
            assertEquals(spec.timeline(), again.timeline());
            assertEquals(
                    spec.load().profile().segments(), again.load().profile().segments());
        }

        @Test
        @DisplayName("an unknown top-level input is rejected")
        void unknownTopLevel() {
            JsonObject json = minimal().put("nodez", 3);

            ConfigurationException e = assertThrows(ConfigurationException.class, () -> RunSpec.parse(json));

            assertTrue(e.getMessage().contains("nodez"), e.getMessage());
        }

        @Test
        @DisplayName("an unknown nested input is rejected even when its value equals the default")
        void unknownNestedWithDefaultValue() {
            JsonObject json = minimal().put("node", new JsonObject().put("poolSizee", 20));

            ConfigurationException e = assertThrows(ConfigurationException.class, () -> RunSpec.parse(json));

            assertTrue(e.getMessage().contains("node.poolSizee"), e.getMessage());
        }

        @Test
        @DisplayName("an input that belongs to another kind of load is rejected")
        void inputOfAnotherKind() {
            JsonObject json = minimal()
                    .put(
                            "load",
                            new JsonObject()
                                    .put("kind", "fixed")
                                    .put("ratePerSec", 10)
                                    .put("steps", 3));

            assertThrows(ConfigurationException.class, () -> RunSpec.parse(json));
        }

        @Test
        @DisplayName("a wrong type is rejected with the path of the input")
        void wrongType() {
            JsonObject json = minimal().put("nodes", "two");

            ConfigurationException e = assertThrows(ConfigurationException.class, () -> RunSpec.parse(json));

            assertTrue(e.getMessage().contains("nodes"), e.getMessage());
        }

        @Test
        @DisplayName("an unknown arm is rejected")
        void unknownArm() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RunSpec.parse(minimal().put("arm", "SIDEWAYS")));
        }
    }

    @Nested
    @DisplayName("product settings passed through")
    class Override {

        private JsonObject withOverride(JsonObject override) {
            return minimal().put("node", new JsonObject().put("confOverride", override));
        }

        @Test
        @DisplayName("a known setting is accepted")
        void knownSettingAccepted() {
            RunSpec spec = RunSpec.parse(withOverride(new JsonObject()
                    .put("workflow", new JsonObject().put("executor", new JsonObject().put("leaseSeconds", 60)))));

            assertEquals(
                    60,
                    spec.node()
                            .confOverride()
                            .getJsonObject("workflow")
                            .getJsonObject("executor")
                            .getInteger("leaseSeconds"));
        }

        @Test
        @DisplayName("a misspelled setting is rejected although its value is the product default")
        void misspelledWithDefaultValue() {
            JsonObject typo = new JsonObject()
                    .put("workflow", new JsonObject().put("executor", new JsonObject().put("leaseSecond", 30)));

            ConfigurationException e =
                    assertThrows(ConfigurationException.class, () -> RunSpec.parse(withOverride(typo)));

            assertTrue(e.getMessage().contains("workflow.executor.leaseSecond"), e.getMessage());
        }

        @Test
        @DisplayName("a setting the run has its own input for is rejected here")
        void settingWithItsOwnInput() {
            JsonObject own = new JsonObject()
                    .put("workflow", new JsonObject().put("executor", new JsonObject().put("concurrency", 8)));

            assertThrows(ConfigurationException.class, () -> RunSpec.parse(withOverride(own)));
        }
    }

    @Nested
    @DisplayName("cross checks")
    class Cross {

        @Test
        @DisplayName("injected failures need a diagnostic run")
        void injectionsNeedDiagnostic() {
            JsonObject json = minimal()
                    .put(
                            "injections",
                            new io.vertx.core.json.JsonArray()
                                    .add(new JsonObject()
                                            .put("kind", "DB_TABLE_LOCK")
                                            .put("atSec", 10)));

            assertThrows(ConfigurationException.class, () -> RunSpec.parse(json));
            assertEquals(
                    1, RunSpec.parse(json.put("diagnostic", true)).injections().size());
        }

        @Test
        @DisplayName("the database container must be named wf017")
        void containerName() {
            JsonObject json = minimal().put("database", new JsonObject().put("container", "other-pg"));

            assertThrows(ConfigurationException.class, () -> RunSpec.parse(json));
        }

        @Test
        @DisplayName("the lock watch is refused in an idle set")
        void lockWatchOffInIdleRuns() {
            JsonObject json = minimal().put("set", "idle").put("collector", new JsonObject().put("lockWatch", true));

            assertThrows(ConfigurationException.class, () -> RunSpec.parse(json));
        }

        @Test
        @DisplayName("a run without nodes cannot offer load")
        void noNodesNoLoad() {
            assertThrows(
                    ConfigurationException.class, () -> RunSpec.parse(minimal().put("nodes", 0)));
        }

        @Test
        @DisplayName("a listener kill names a node that exists")
        void listenerKillNode() {
            JsonObject json = minimal()
                    .put("diagnostic", true)
                    .put(
                            "injections",
                            new io.vertx.core.json.JsonArray()
                                    .add(new JsonObject()
                                            .put("kind", "LISTENER_KILL")
                                            .put("atSec", 5)
                                            .put("node", 3)));

            assertThrows(ConfigurationException.class, () -> RunSpec.parse(json));
        }

        @Test
        @DisplayName("a staircase builds equal steps with growing rates")
        void staircase() {
            JsonObject load = new JsonObject()
                    .put("kind", "staircase")
                    .put("startRatePerSec", 100)
                    .put("stepRatePerSec", 50)
                    .put("steps", 3)
                    .put("stepSec", 60);

            RunSpec spec = RunSpec.parse(minimal().put("load", load));

            List<dev.vertique.examples.workflow.load.harness.driver.LoadProfile.Segment> steps =
                    spec.load().profile().segments();
            assertEquals(3, steps.size());
            assertEquals(100.0, steps.get(0).ratePerSec());
            assertEquals(200.0, steps.get(2).ratePerSec());
            assertEquals(180.0, spec.load().profile().endSec());
            assertEquals(10.0, spec.load().stepMeasureSkipSec());
        }
    }
}
