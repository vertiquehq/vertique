// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import io.vertx.core.Future;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The repository a source class receives by constructor injection: it answers the order number that a carrier's
 * tracking reference belongs to.
 */
interface ShipmentLookup {

    /**
     * Finds the order number of a shipment.
     *
     * @param trackingRef the reference the carrier delivered under
     * @return the order number, or empty when the reference belongs to no order
     */
    Future<Optional<String>> orderNumberFor(String trackingRef);

    /** A lookup that answers from a table and keeps each reference it was asked about. */
    final class Recording implements ShipmentLookup {

        private final Map<String, String> orders = new ConcurrentHashMap<>();
        private final List<String> asked = new CopyOnWriteArrayList<>();

        /**
         * Makes the lookup answer the order number for the reference.
         *
         * @param trackingRef the reference
         * @param orderNumber the order number it belongs to
         * @return this lookup
         */
        Recording knows(String trackingRef, String orderNumber) {
            orders.put(trackingRef, orderNumber);
            return this;
        }

        /**
         * Returns the references the lookup was asked about, in order.
         *
         * @return the references
         */
        List<String> asked() {
            return List.copyOf(asked);
        }

        @Override
        public Future<Optional<String>> orderNumberFor(String trackingRef) {
            asked.add(trackingRef);
            return Future.succeededFuture(Optional.ofNullable(orders.get(trackingRef)));
        }
    }
}
