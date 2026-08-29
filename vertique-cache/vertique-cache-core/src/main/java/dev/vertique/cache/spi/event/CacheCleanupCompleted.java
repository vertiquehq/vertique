// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi.event;

/**
 * One bounded physical cleanup sweep outcome emitted by a provider maintenance job.
 *
 * <p>The outcome label is provider-defined and bounded; counters are non-negative.
 *
 * @param profile provider profile label
 * @param namespace cache namespace label
 * @param outcome bounded provider-defined cleanup outcome label
 * @param scanned number of keys inspected
 * @param deleted number of keys deleted
 * @param backlog backlog indicator/count
 * @param failed whether the cleanup sweep failed
 */
public record CacheCleanupCompleted(
        String profile, String namespace, String outcome, long scanned, long deleted, long backlog, boolean failed)
        implements CacheEvent {
    public CacheCleanupCompleted {
        profile = EventValidation.boundedLabel(profile, "profile");
        namespace = EventValidation.boundedLabel(namespace, "namespace");
        outcome = EventValidation.boundedLabel(outcome, "outcome");
        EventValidation.requireNonNegative(scanned, "scanned");
        EventValidation.requireNonNegative(deleted, "deleted");
        EventValidation.requireNonNegative(backlog, "backlog");
    }
}
