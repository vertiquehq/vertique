// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.stubs;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;

/**
 * Public stub implementations of {@link Canonicalizer} and {@link Sanitizer} for use as policy
 * markers in APT annotation-processing test fixtures.
 *
 * <p>These classes are declared in a separate public file so that inline source text compiled
 * through {@link dev.vertique.codegen.test.ProcessorTestHarness} (which places the current test
 * classpath on the javac classpath) can reference them by fully-qualified name.
 */
public final class PolicyTestStubs {

    private PolicyTestStubs() {}

    /**
     * First stub {@link Canonicalizer} — used as the class-level policy marker.
     */
    public static final class StubCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /**
     * Second stub {@link Canonicalizer} — used to verify method-overrides-class semantics.
     */
    public static final class StubCanonicalizer2 implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    /**
     * Stub {@link Sanitizer} — used as the class-level sanitizer policy marker.
     */
    public static final class StubSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }
}
