// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it;

import dev.vertique.codegen.NoAutoWire;
import dev.vertique.core.codegen.MethodMetadata;

/** Compile-time annotation consumer proving that unrelated feature processors remain inert. */
@NoAutoWire
public final class CoreApp {

    private final MethodMetadata methodMetadata;

    public CoreApp(MethodMetadata methodMetadata) {
        this.methodMetadata = methodMetadata;
    }

    public MethodMetadata methodMetadata() {
        return methodMetadata;
    }
}
