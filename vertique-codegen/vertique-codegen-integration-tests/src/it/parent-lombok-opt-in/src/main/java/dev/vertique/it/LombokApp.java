// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it;

import lombok.Getter;

/** Consumer proving that Lombok remains available through explicit dependency and path opt-in. */
public final class LombokApp {

    @Getter
    private final String value;

    public LombokApp(String value) {
        this.value = value;
    }

    public static String generatedAccessorResult() {
        return new LombokApp("generated").getValue();
    }
}
