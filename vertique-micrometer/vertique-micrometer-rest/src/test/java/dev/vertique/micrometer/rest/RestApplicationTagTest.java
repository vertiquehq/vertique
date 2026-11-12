// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code rest.application} tag of {@link RestServerRequestMetricsListener}: the
 * tag carries the owning application's name read from {@code event.operation().applicationName()},
 * is {@code none} for an operation of no application, and is always present, so the same resource,
 * operationId and route mounted by two applications records two series.
 *
 * <p>The tag key is written as a literal here, never read from the listener, so the expectation
 * cannot drift along with production code: the key must stay the one the cardinality guard of
 * {@code vertique-micrometer-core} lists.
 */
class RestApplicationTagTest {

    private static final String TAG_KEY = "rest.application";

    private static final Instant START = Instant.parse("2026-06-12T12:00:00Z");

    /** {@code GET /items}, operation {@code listItems}, mounted by application {@code public}. */
    private static final RestOperationDescriptor PUBLIC_LIST =
            new TestOperation("listItems", "GET", "/items", "public");

    /** The same resource method mounted by application {@code partner}. */
    private static final RestOperationDescriptor PARTNER_LIST =
            new TestOperation("listItems", "GET", "/items", "partner");

    /** {@code GET /legacy}, operation {@code legacy}, mounted outside any application. */
    private static final RestOperationDescriptor LEGACY = new TestOperation("legacy", "GET", "/legacy", null);

    private SimpleMeterRegistry registry;
    private RestServerRequestMetricsListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new RestServerRequestMetricsListener(registry, Optional.empty());
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    @DisplayName("an application operation's series is tagged with the application's name")
    void applicationOperationCarriesItsName() {
        listener.onCompleted(event(PUBLIC_LIST, 200));

        Timer timer = timerTagged("public");
        assertAll(
                () -> assertNotNull(timer, "a series tagged " + TAG_KEY + "=public must exist; series: " + series()),
                () -> assertEquals(1, timer == null ? -1 : timer.count(), "count of the public series"));
    }

    @Test
    @DisplayName("an operation of no application is tagged none")
    void operationOutsideAnApplicationIsTaggedNone() {
        listener.onCompleted(event(LEGACY, 200));

        Timer timer = timerTagged("none");
        assertAll(
                () -> assertNotNull(timer, "a series tagged " + TAG_KEY + "=none must exist; series: " + series()),
                () -> assertEquals(1, timer == null ? -1 : timer.count(), "count of the none series"),
                () -> assertEquals(
                        1,
                        registry.find(RestServerRequestMetricsListener.METER_NAME)
                                .timers()
                                .size(),
                        "exactly one series; series: " + series()));
    }

    @Test
    @DisplayName("a rejected application request keeps the application's name")
    void rejectedApplicationRequestKeepsTheName() {
        listener.onCompleted(event(PUBLIC_LIST, 403));

        Timer timer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                .tag(TAG_KEY, "public")
                .tag(RestServerRequestMetricsListener.TAG_STATUS, "403")
                .timer();
        assertNotNull(timer, "the 403 series must be tagged " + TAG_KEY + "=public; series: " + series());
        assertEquals(1, timer.count(), "count of the rejected series");
    }

    @Test
    @DisplayName("one resource mounted by two applications records one series per application")
    void sharedResourceSplitsByApplication() {
        listener.onCompleted(event(PUBLIC_LIST, 200));
        listener.onCompleted(event(PARTNER_LIST, 200));

        Timer publicTimer = timerTagged("public");
        Timer partnerTimer = timerTagged("partner");
        assertAll(
                () -> assertNotNull(publicTimer, "public series; series: " + series()),
                () -> assertNotNull(partnerTimer, "partner series; series: " + series()),
                () -> assertNotSame(publicTimer, partnerTimer, "the two applications must not share a series"),
                () -> assertEquals(1, publicTimer == null ? -1 : publicTimer.count(), "public count"),
                () -> assertEquals(1, partnerTimer == null ? -1 : partnerTimer.count(), "partner count"),
                () -> assertEquals(
                        2,
                        registry.find(RestServerRequestMetricsListener.METER_NAME)
                                .timers()
                                .size(),
                        "exactly two series; series: " + series()),
                () -> assertEquals(
                        withoutApplication(publicTimer),
                        withoutApplication(partnerTimer),
                        "the two series differ only in " + TAG_KEY));
    }

    // --- Helpers ---

    private Timer timerTagged(String application) {
        return registry.find(RestServerRequestMetricsListener.METER_NAME)
                .tag(TAG_KEY, application)
                .timer();
    }

    private String series() {
        return registry.find(RestServerRequestMetricsListener.METER_NAME).timers().stream()
                .map(t -> t.getId().getTags().toString() + " count=" + t.count())
                .toList()
                .toString();
    }

    private static List<Tag> withoutApplication(@Nullable Meter meter) {
        if (meter == null) {
            return List.of();
        }
        return meter.getId().getTags().stream()
                .filter(tag -> !TAG_KEY.equals(tag.getKey()))
                .toList();
    }

    private static RestRequestCompletedEvent event(RestOperationDescriptor operation, int statusCode) {
        return new RestRequestCompletedEvent(
                START,
                START.plusMillis(10),
                operation.httpMethod(),
                operation.routeTemplate(),
                operation,
                statusCode,
                null,
                null,
                null,
                null,
                null,
                Optional.empty(),
                Map.of());
    }

    /**
     * Test-local {@link RestOperationDescriptor} carrying the identity fields and the owning
     * application's name, {@code null} outside applications.
     *
     * @param operationId     the operation identifier
     * @param httpMethod      the HTTP method
     * @param routeTemplate   the route template
     * @param applicationName the owning application's name, or {@code null}
     */
    private record TestOperation(
            String operationId,
            String httpMethod,
            String routeTemplate,
            @Nullable String applicationName) implements RestOperationDescriptor {

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return new SecurityPolicy.None();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }
}
