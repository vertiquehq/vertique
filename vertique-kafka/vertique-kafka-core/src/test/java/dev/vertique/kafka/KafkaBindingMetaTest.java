// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link KafkaBindingMeta} record invariants: SOURCE / HANDLER / ROUTER shape constraints
 * and defensive copying of the route list.
 */
class KafkaBindingMetaTest {

    private static KafkaBindingMeta.RouteMeta route() {
        return new KafkaBindingMeta.RouteMeta("event-type", "", "created", false, String.class, null, null);
    }

    @Test
    @DisplayName("SOURCE accepts a null valueType, a targetOperation, and empty routes")
    void sourceShapeIsValid() {
        KafkaBindingMeta meta = new KafkaBindingMeta(
                "orders",
                "order.events",
                "order-grp",
                KafkaBindingMeta.Kind.SOURCE,
                null, // valueType is null for SOURCE (resolved at runtime from ServiceMethodMeta)
                ErrorStrategy.SKIP,
                CommitStrategy.AUTO,
                "",
                null, // jsonProfile null for SOURCE
                "process", // targetOperation = impl method name
                List.of());
        assertEquals(KafkaBindingMeta.Kind.SOURCE, meta.kind());
        assertEquals("process", meta.targetOperation());
        assertTrue(meta.routes().isEmpty());
    }

    @Test
    @DisplayName("HANDLER accepts a valueType and empty routes")
    void handlerShapeIsValid() {
        KafkaBindingMeta meta = new KafkaBindingMeta(
                "orders",
                "order.events",
                "order-grp",
                KafkaBindingMeta.Kind.HANDLER,
                String.class,
                ErrorStrategy.SKIP,
                CommitStrategy.AUTO,
                "",
                null,
                null,
                List.of());
        assertEquals(KafkaBindingMeta.Kind.HANDLER, meta.kind());
        assertTrue(meta.routes().isEmpty());
    }

    @Test
    @DisplayName("ROUTER accepts null valueType and non-empty routes")
    void routerShapeIsValid() {
        KafkaBindingMeta meta = new KafkaBindingMeta(
                "orders",
                "order.events",
                "order-grp",
                KafkaBindingMeta.Kind.ROUTER,
                null,
                ErrorStrategy.SKIP,
                CommitStrategy.AUTO,
                "",
                null,
                null,
                List.of(route()));
        assertEquals(1, meta.routes().size());
    }

    @Test
    @DisplayName("SOURCE with non-empty routes is rejected")
    void sourceWithRoutesRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KafkaBindingMeta(
                        "x",
                        "t",
                        "g",
                        KafkaBindingMeta.Kind.SOURCE,
                        String.class,
                        ErrorStrategy.SKIP,
                        CommitStrategy.AUTO,
                        "",
                        null,
                        null,
                        List.of(route())));
    }

    @Test
    @DisplayName("HANDLER with non-empty routes is rejected")
    void handlerWithRoutesRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KafkaBindingMeta(
                        "x",
                        "t",
                        "g",
                        KafkaBindingMeta.Kind.HANDLER,
                        String.class,
                        ErrorStrategy.SKIP,
                        CommitStrategy.AUTO,
                        "",
                        null,
                        null,
                        List.of(route())));
    }

    @Test
    @DisplayName("ROUTER with empty routes is rejected")
    void routerWithoutRoutesRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KafkaBindingMeta(
                        "x",
                        "t",
                        "g",
                        KafkaBindingMeta.Kind.ROUTER,
                        null,
                        ErrorStrategy.SKIP,
                        CommitStrategy.AUTO,
                        "",
                        null,
                        null,
                        List.of()));
    }

    @Test
    @DisplayName("ROUTER with a non-null valueType is rejected")
    void routerWithValueTypeRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KafkaBindingMeta(
                        "x",
                        "t",
                        "g",
                        KafkaBindingMeta.Kind.ROUTER,
                        String.class,
                        ErrorStrategy.SKIP,
                        CommitStrategy.AUTO,
                        "",
                        null,
                        null,
                        List.of(route())));
    }

    @Test
    @DisplayName("routes() is an unmodifiable copy")
    void routesAreUnmodifiable() {
        KafkaBindingMeta meta = new KafkaBindingMeta(
                "x",
                "t",
                "g",
                KafkaBindingMeta.Kind.ROUTER,
                null,
                ErrorStrategy.SKIP,
                CommitStrategy.AUTO,
                "",
                null,
                null,
                List.of(route()));
        assertThrows(UnsupportedOperationException.class, () -> meta.routes().add(route()));
    }

    @Test
    @DisplayName("jsonProfile() accessor returns the value supplied at construction")
    void kafkaBindingMeta_jsonProfileAccessorReturnsCorrectValue() {
        KafkaBindingMeta meta = new KafkaBindingMeta(
                "orders",
                "order.events",
                "order-grp",
                KafkaBindingMeta.Kind.HANDLER,
                String.class,
                ErrorStrategy.SKIP,
                CommitStrategy.AUTO,
                "",
                "events-v2", // jsonProfile
                null,
                List.of());
        assertEquals("events-v2", meta.jsonProfile());
    }

    @Test
    @DisplayName("jsonProfile() returns null when constructed with a null profile")
    void kafkaBindingMeta_nullJsonProfileIsNullable() {
        KafkaBindingMeta meta = new KafkaBindingMeta(
                "orders",
                "order.events",
                "order-grp",
                KafkaBindingMeta.Kind.HANDLER,
                String.class,
                ErrorStrategy.SKIP,
                CommitStrategy.AUTO,
                "",
                null, // jsonProfile
                null,
                List.of());
        assertNull(meta.jsonProfile());
    }

    @Test
    @DisplayName("RouteMeta rejects a null valueType")
    void routeMetaRequiresValueType() {
        assertThrows(
                NullPointerException.class,
                () -> new KafkaBindingMeta.RouteMeta("h", "", "v", false, null, null, null));
    }
}
