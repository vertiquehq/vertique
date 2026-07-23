// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link CardinalityGuard} — verifies per-key tag-value capping,
 * one-time WARN emission (without logging overflowing values), non-vertique meter exemption,
 * independent-key counting, and the global meter cap ({@code maxMeters > 0}).
 *
 * <p>Tests apply the guard filters directly to a {@link SimpleMeterRegistry} (no composite
 * needed) except for the assembly-integration test which uses a full {@link MicrometerAssembly}.
 */
class CardinalityGuardTest {

    /** Tracks assemblies created in assembly-integration tests so they can be closed. */
    private final List<MicrometerAssembly> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (MicrometerAssembly a : opened) {
            try {
                a.close();
            } catch (Exception ignored) {
                // idempotent close OK
            }
        }
        opened.clear();
    }

    // --- Helper: build a registry with the guard filters applied ---

    private static SimpleMeterRegistry registryWithGuard(MetricsConfig.CardinalityConfig config) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        for (io.micrometer.core.instrument.config.MeterFilter filter : CardinalityGuard.filters(config)) {
            registry.config().meterFilter(filter);
        }
        return registry;
    }

    private static MetricsConfig.CardinalityConfig cardinalityConfig(int maxTagValues, int maxMeters) {
        return new JsonObject()
                .put(
                        "cardinality",
                        new JsonObject().put("maxTagValuesPerKey", maxTagValues).put("maxMeters", maxMeters))
                .mapTo(MetricsConfig.class)
                .cardinality();
    }

    // --- Test 1: cap=5, v1..v5 allowed, v6 denied ---

    @Nested
    class TagValueCap {

        @Test
        @DisplayName("cap=5: first 5 tag values register and accumulate; 6th value is denied (counter is no-op)")
        void firstFiveAllowedSixthDenied() {
            MetricsConfig.CardinalityConfig config = cardinalityConfig(5, 0);
            SimpleMeterRegistry registry = registryWithGuard(config);

            // Register v1..v5 — should all succeed and accumulate
            for (int i = 1; i <= 5; i++) {
                Counter c = registry.counter("vertique.test.thing", "method", "v" + i);
                c.increment();
            }
            for (int i = 1; i <= 5; i++) {
                Counter c = registry.find("vertique.test.thing")
                        .tag("method", "v" + i)
                        .counter();
                assertNotNull(c, "counter for method=v" + i + " must be registered");
                assertEquals(1.0, c.count(), "counter for method=v" + i + " must have count=1.0");
            }

            // v6 should be denied
            registry.counter("vertique.test.thing", "method", "v6").increment();
            Counter denied =
                    registry.find("vertique.test.thing").tag("method", "v6").counter();
            // Either the counter was not registered at all, or it is a no-op (count stays 0)
            assertTrue(
                    denied == null || denied.count() == 0.0,
                    "counter for method=v6 must be absent or accumulate nothing (got: "
                            + (denied == null ? "null" : denied.count()) + ")");
        }
    }

    // --- Test 2: exactly one WARN per overflow, message contains key but NOT overflowing value ---

    @Nested
    class WarnLogging {

        @Test
        @DisplayName(
                "exactly one WARN is logged on overflow; message contains tag key, does NOT contain overflowing value")
        void oneWarnOnOverflow() {
            // Attach logback ListAppender to the CardinalityGuard logger
            Logger guardLogger = (Logger) LoggerFactory.getLogger(CardinalityGuard.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            guardLogger.addAppender(appender);
            guardLogger.setLevel(Level.WARN);

            try {
                MetricsConfig.CardinalityConfig config = cardinalityConfig(2, 0);
                SimpleMeterRegistry registry = registryWithGuard(config);

                // Register 2 allowed values
                registry.counter("vertique.x", "method", "v1").increment();
                registry.counter("vertique.x", "method", "v2").increment();

                // First overflow — should log exactly ONE warn
                String sentinelValue = "SECRET_VALUE_XYZ";
                registry.counter("vertique.x", "method", sentinelValue).increment();

                long warnCount = appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .count();
                assertEquals(1, warnCount, "must emit exactly one WARN on first overflow");

                // Verify WARN message contains the tag key
                String warnMsg = appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .findFirst()
                        .map(ILoggingEvent::getFormattedMessage)
                        .orElse("");
                assertTrue(warnMsg.contains("method"), "WARN message must contain the tag key 'method'");

                // Verify WARN message does NOT contain the overflowing value
                assertFalse(
                        warnMsg.contains(sentinelValue),
                        "WARN message must NOT contain the overflowing value (log-injection risk)");

                // Subsequent overflow attempts must NOT log additional WARNs
                registry.counter("vertique.x", "method", "v4").increment();
                registry.counter("vertique.x", "method", "v5").increment();

                long warnCountAfter = appender.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .count();
                assertEquals(1, warnCountAfter, "must emit no additional WARNs after first overflow");

            } finally {
                guardLogger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    // --- Test 3: non-vertique meters are NOT affected by the guard ---

    @Nested
    class NonVertiqueMeterExemption {

        @Test
        @DisplayName("non-vertique meters (e.g. vertx.http.*) are unaffected by the cap")
        void nonVertiqueMeterUnaffected() {
            // cap=5 for vertique.* meters; vertx.* must not be affected
            MetricsConfig.CardinalityConfig config = cardinalityConfig(5, 0);
            SimpleMeterRegistry registry = registryWithGuard(config);

            // Register 10 distinct method values on a non-vertique meter
            for (int i = 1; i <= 10; i++) {
                registry.counter("vertx.http.thing", "method", "v" + i).increment();
            }

            // All 10 must be registered and accumulate
            for (int i = 1; i <= 10; i++) {
                Counter c =
                        registry.find("vertx.http.thing").tag("method", "v" + i).counter();
                assertNotNull(c, "non-vertique counter method=v" + i + " must be registered");
                assertEquals(1.0, c.count(), "non-vertique counter method=v" + i + " must accumulate");
            }
        }
    }

    // --- Test 4: untagged vertique meters are unaffected by per-key filters ---

    @Nested
    class UntaggedVertiqueMeter {

        @Test
        @DisplayName("untagged vertique meters are unaffected by per-key tag filters")
        void untaggedMeterUnaffected() {
            MetricsConfig.CardinalityConfig config = cardinalityConfig(1, 0);
            SimpleMeterRegistry registry = registryWithGuard(config);

            // Register several untagged vertique meters — none have the guarded tag keys
            registry.counter("vertique.foo").increment(3.0);
            registry.counter("vertique.bar").increment(5.0);
            registry.counter("vertique.baz").increment(7.0);

            assertEquals(3.0, registry.find("vertique.foo").counter().count(), "vertique.foo must accumulate");
            assertEquals(5.0, registry.find("vertique.bar").counter().count(), "vertique.bar must accumulate");
            assertEquals(7.0, registry.find("vertique.baz").counter().count(), "vertique.baz must accumulate");
        }
    }

    // --- Test 5: overflow on 'method' does NOT affect 'route' cap ---

    @Nested
    class IndependentKeys {

        @Test
        @DisplayName("overflow on 'method' key does not affect 'route' key cardinality tracking")
        void overflowOnMethodDoesNotAffectRoute() {
            MetricsConfig.CardinalityConfig config = cardinalityConfig(2, 0);
            SimpleMeterRegistry registry = registryWithGuard(config);

            // Fill the 'method' cap (2 values) then overflow
            registry.counter("vertique.x", "method", "get").increment();
            registry.counter("vertique.x", "method", "post").increment();
            registry.counter("vertique.x", "method", "put").increment(); // overflow

            // Now register 2 new 'route' values — they should still be allowed
            registry.counter("vertique.y", "route", "/a").increment();
            registry.counter("vertique.y", "route", "/b").increment();

            assertNotNull(
                    registry.find("vertique.y").tag("route", "/a").counter(),
                    "route=/a must be registered (independent key)");
            assertNotNull(
                    registry.find("vertique.y").tag("route", "/b").counter(),
                    "route=/b must be registered (independent key)");
        }
    }

    // --- Test 6: maxMeters cap, and maxMeters=0 means no global cap appended ---

    @Nested
    class MaxMetersCap {

        @Test
        @DisplayName("maxMeters=3: a 4th distinct meter is denied; maxMeters=0: no global cap filter added")
        void maxMetersDenies4thMeter() {
            // Verify filter count for maxMeters=0 (only GUARDED_TAG_KEYS.size() filters)
            MetricsConfig.CardinalityConfig noGlobalCap = cardinalityConfig(200, 0);
            List<io.micrometer.core.instrument.config.MeterFilter> filtersNoGlobal =
                    CardinalityGuard.filters(noGlobalCap);
            assertEquals(
                    CardinalityGuard.GUARDED_TAG_KEYS.size(),
                    filtersNoGlobal.size(),
                    "maxMeters=0 must produce exactly GUARDED_TAG_KEYS.size() filters (no global cap appended)");

            // Verify that maxMeters=3 adds one extra filter
            MetricsConfig.CardinalityConfig withGlobalCap = cardinalityConfig(200, 3);
            List<io.micrometer.core.instrument.config.MeterFilter> filtersWithGlobal =
                    CardinalityGuard.filters(withGlobalCap);
            assertEquals(
                    CardinalityGuard.GUARDED_TAG_KEYS.size() + 1,
                    filtersWithGlobal.size(),
                    "maxMeters=3 must produce GUARDED_TAG_KEYS.size()+1 filters (global cap appended)");

            // Functional check: maxMeters=3 denies a 4th distinct vertique meter
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            for (io.micrometer.core.instrument.config.MeterFilter filter : filtersWithGlobal) {
                registry.config().meterFilter(filter);
            }

            registry.counter("vertique.a").increment();
            registry.counter("vertique.b").increment();
            registry.counter("vertique.c").increment();

            // 4th distinct meter must be denied
            Counter fourth = registry.counter("vertique.d");
            fourth.increment();
            Counter found = registry.find("vertique.d").counter();
            assertTrue(found == null || found.count() == 0.0, "4th distinct meter must be denied (absent or no-op)");
        }
    }

    // --- Test 7: Assembly integration — cardinality cap wired through MicrometerAssembly ---

    @Nested
    class AssemblyIntegration {

        @Test
        @DisplayName(
                "cardinality cap=2 wired through MicrometerAssembly: method=a,b accumulate in child; method=c does not")
        void cardinalityCapWiredThroughAssembly() {
            SimpleMeterRegistry child = new SimpleMeterRegistry();
            MicrometerAssemblyTest.FakeProvider provider =
                    MicrometerAssemblyTest.FakeProvider.wrapping("test-backend", 1, child);

            MetricsConfig config = new JsonObject()
                    .put("jvm", new JsonObject().put("enabled", false))
                    .put(
                            "cardinality",
                            new JsonObject().put("maxTagValuesPerKey", 2).put("maxMeters", 0))
                    .mapTo(MetricsConfig.class);

            MicrometerAssembly assembly = MicrometerAssembly.assemble(List.of(provider), config, new JsonObject());
            opened.add(assembly);

            // Record method=a and method=b — should propagate into child
            assembly.composite().counter("vertique.x", "method", "a").increment();
            assembly.composite().counter("vertique.x", "method", "b").increment();

            // Record method=c — should be denied (cap=2 reached)
            assembly.composite().counter("vertique.x", "method", "c").increment();

            // a and b must be present in child with count=1
            Counter counterA = child.find("vertique.x").tag("method", "a").counter();
            Counter counterB = child.find("vertique.x").tag("method", "b").counter();
            assertNotNull(counterA, "method=a must be in child registry");
            assertNotNull(counterB, "method=b must be in child registry");
            assertEquals(1.0, counterA.count(), "method=a must have count=1 in child");
            assertEquals(1.0, counterB.count(), "method=b must have count=1 in child");

            // c must be absent or no-op in child
            Counter counterC = child.find("vertique.x").tag("method", "c").counter();
            assertTrue(
                    counterC == null || counterC.count() == 0.0,
                    "method=c must be absent or no-op in child registry after cap is reached");
        }
    }
}
