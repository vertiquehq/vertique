// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import java.time.Duration;
import java.util.Objects;

/** Redacted cache operation data suitable for optional metrics and tracing adapters. */
public record CacheObservation(String operation, String provider, String cacheName, String outcome, Duration duration) {
    public CacheObservation {
        operation = require(operation, "operation");
        provider = require(provider, "provider");
        cacheName = require(cacheName, "cacheName");
        outcome = require(outcome, "outcome");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
