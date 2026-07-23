// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

/**
 * SPI marker for domain objects that carry a localizable message descriptor.
 *
 * <p>Implementing this interface signals that the object knows how to express itself as a
 * {@link MessageResolvable}, which allows callers to resolve the human-readable representation
 * via a {@link MessageSource} without coupling the domain object to a specific locale or bundle.
 *
 * <p>Typical implementations are domain exceptions that carry machine-readable codes and
 * structured arguments alongside the Java message string:
 * <pre>{@code
 * public class OrderNotFoundException extends RuntimeException implements MessageCoded {
 *     private final String orderId;
 *
 *     public OrderNotFoundException(String orderId) {
 *         super("Order not found: " + orderId);
 *         this.orderId = orderId;
 *     }
 *
 *     @Override
 *     public MessageResolvable message() {
 *         return new MessageResolvable(
 *             List.of("order.not-found"),
 *             List.of(orderId),
 *             "Order " + orderId + " was not found");
 *     }
 * }
 * }</pre>
 */
public interface MessageCoded {

    /**
     * Returns the {@link MessageResolvable} descriptor for this object's localizable message.
     *
     * @return a non-{@code null} {@link MessageResolvable} with at least one code
     */
    MessageResolvable message();
}
