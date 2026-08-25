// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import java.util.Objects;

/**
 * Redacted, bounded cleanup outcome data suitable for optional observation adapters.
 *
 * @param profile provider profile label
 * @param namespace cache namespace label
 * @param outcome bounded cleanup outcome label
 * @param scanned number of keys inspected
 * @param deleted number of keys deleted
 * @param backlog backlog indicator/count
 * @param failed whether the cleanup sweep failed
 */
public record CacheCleanupObservation(
        String profile, String namespace, String outcome, long scanned, long deleted, long backlog, boolean failed) {
    private static final int MAX_DIMENSION_LENGTH = 64;

    public CacheCleanupObservation {
        profile = bounded(profile, "profile");
        namespace = bounded(namespace, "namespace");
        outcome = bounded(outcome, "outcome");
        requireNonNegative(scanned, "scanned");
        requireNonNegative(deleted, "deleted");
        requireNonNegative(backlog, "backlog");
    }

    private static String bounded(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.length() <= MAX_DIMENSION_LENGTH ? value : value.substring(0, MAX_DIMENSION_LENGTH);
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
