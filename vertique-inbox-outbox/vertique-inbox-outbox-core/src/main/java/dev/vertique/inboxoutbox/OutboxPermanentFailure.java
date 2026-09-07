// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

/**
 * INTERNAL framework seam — consumed by the inbox-outbox adapters and sibling framework modules; not
 * an application contract and outside the maturity promise. Applications use {@code OutboxService},
 * {@code InboxService}, and the extension points the module document lists.
 *
 * <p>Marker interface for exceptions that should cause immediate dead-lettering when thrown
 * by a service handler during outbox relay publishing.
 *
 * <p>When a service handler throws an exception implementing this interface, the SERVICE
 * destination adapter classifies the failure as {@link OutboxPublishResult.PermanentFailure}
 * instead of the default {@link OutboxPublishResult.RetryableFailure}. This prevents futile
 * retry attempts for deterministic failures such as validation errors or business rule violations.
 *
 * <p>Example usage:
 * <pre>{@code
 * public class OrderNotFoundException extends RuntimeException implements OutboxPermanentFailure {
 *     public OrderNotFoundException(String orderId) {
 *         super("Order not found: " + orderId);
 *     }
 * }
 * }</pre>
 */
public interface OutboxPermanentFailure {}
