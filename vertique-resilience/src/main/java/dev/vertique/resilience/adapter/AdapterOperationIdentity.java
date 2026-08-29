// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.adapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Structured identity supplied by a framework adapter when constructing resilience state.
 *
 * <p>The identity components are retained only by this immutable value. Adapter factories derive
 * their opaque operation or state key immediately and must not retain the identity afterward.
 *
 * @param kind the lower-case adapter identity kind
 * @param components the ordered identity components
 */
public record AdapterOperationIdentity(String kind, List<String> components) {

    private static final int MAX_UTF8_BYTES = 4_096;
    private static final Pattern KIND_PATTERN = Pattern.compile("[a-z0-9.-]{1,32}");

    public AdapterOperationIdentity {
        kind = Objects.requireNonNull(kind, "kind");
        components = List.copyOf(Objects.requireNonNull(components, "components"));

        validateUtf8(kind, "kind");
        if (!KIND_PATTERN.matcher(kind).matches()) {
            throw new IllegalArgumentException("kind must match [a-z0-9.-]{1,32}");
        }
        for (String component : components) {
            validateUtf8(component, "component");
        }
    }

    private static void validateUtf8(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(name + " contains an unpaired UTF-16 surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(name + " contains an unpaired UTF-16 surrogate");
            }
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException(name + " exceeds the 4,096-byte UTF-8 limit");
        }
    }
}
