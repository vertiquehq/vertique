// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class EventValidation {
    private static final String KEY = "[a-z0-9:-]{1,64}:[0-9a-f]{64}";

    private EventValidation() {}

    static String key(String value) {
        return value != null && value.matches(KEY) ? value : invalid("key");
    }

    static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    static long nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    static Set<ResilienceConcern> concerns(Set<ResilienceConcern> value) {
        return Set.copyOf(required(value, "enabledConcerns"));
    }

    static Optional<String> optionalKey(Optional<String> value) {
        return required(value, "triggeringOperationKey").map(EventValidation::key);
    }

    static OptionalLong optionalId(OptionalLong value) {
        return required(value, "triggeringExecutionId");
    }

    private static <T> T invalid(String name) {
        throw new IllegalArgumentException(name + " must be a derived resilience key");
    }
}
