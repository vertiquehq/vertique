// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

/**
 * Delivery-time scheduling control values for outbox entries targeting the delayed-job queue.
 *
 * <p>These values capture the effective queue, priority, and attempt limit snapshotted at publish
 * time. They are persisted inside {@link OutboxDeliveryMetadata#delayedJob()} (the
 * {@code metadata.delivery.delayedJob} JSONB section) and read by {@code DelayedJobOutboxDestinationHandler}
 * when enqueueing the job at relay time.
 *
 * @param queue       name of the delayed-job queue to target (e.g., {@code "default"})
 * @param priority    job scheduling priority; higher values run sooner within the same queue
 * @param maxAttempts maximum number of execution attempts before dead-lettering
 */
public record DelayedJobControl(String queue, int priority, int maxAttempts) {}
