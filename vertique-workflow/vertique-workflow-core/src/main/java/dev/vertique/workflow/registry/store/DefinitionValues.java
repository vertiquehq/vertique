// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import java.util.Objects;

final class DefinitionValues {
    private DefinitionValues() {}

    static void text(String value, String name, int maximum, boolean required) {
        if (value == null && !required) return;
        Objects.requireNonNull(value, name);
        if ((required && value.isBlank()) || value.codePointCount(0, value.length()) > maximum)
            throw new IllegalArgumentException(name + " must be nonblank and at most " + maximum + " code points");
    }

    static void format(String value) {
        if (!"YAML".equals(value) && !"JSON".equals(value))
            throw new IllegalArgumentException("documentFormat must be YAML or JSON");
    }
}
