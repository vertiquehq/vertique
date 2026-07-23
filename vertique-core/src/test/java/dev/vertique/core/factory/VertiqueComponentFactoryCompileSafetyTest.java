// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-12 negative proof (NFR-APP-002): a missing host-bean adapter module is a <strong>build-time</strong>
 * Dagger missing-binding error, not a runtime failure.
 *
 * <p>The test compiles, in-memory via {@code compile-testing} running the real Dagger
 * {@link dagger.internal.codegen.ComponentProcessor}, a {@code @Component} that depends on a
 * host-native bean ({@code FakeDataSource}) <em>without</em> the typed adapter module that would
 * provide it. It asserts the compilation fails with a missing-binding diagnostic — proving that the
 * typed-adapter pattern (FR-APP-004) fails closed at build time when an adapter is forgotten.
 *
 * <p>The two assertions are paired deliberately: {@code assertFailed} alone would also be satisfied
 * by an unrelated compile error (e.g. a typo in the fixture), so the diagnostic-substring check
 * confirms the failure is specifically Dagger's missing-binding error for {@code FakeDataSource}.
 */
class VertiqueComponentFactoryCompileSafetyTest {

    private static final String DATA_SOURCE = """
            package test.app;

            public interface FakeDataSource {
                String label();
            }
            """;

    private static final String CONSUMER = """
            package test.app;

            import jakarta.inject.Inject;
            import jakarta.inject.Singleton;

            @Singleton
            public final class FakeDataSourceConsumer {
                private final FakeDataSource dataSource;

                @Inject
                FakeDataSourceConsumer(FakeDataSource dataSource) {
                    this.dataSource = dataSource;
                }

                public FakeDataSource dataSource() {
                    return dataSource;
                }
            }
            """;

    // The component requires FakeDataSourceConsumer (hence FakeDataSource) but declares NO module
    // that provides FakeDataSource — the missing host-bean adapter. Dagger must reject this.
    private static final String COMPONENT_MISSING_ADAPTER = """
            package test.app;

            import dagger.Component;
            import jakarta.inject.Singleton;

            @Singleton
            @Component
            public interface FakeHostComponent {
                FakeDataSourceConsumer consumer();
            }
            """;

    @Test
    @DisplayName("component missing the host-bean adapter module fails to compile with a missing-binding error")
    void missingAdapterModule_failsToCompile() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new dagger.internal.codegen.ComponentProcessor())
                .withOptions("--release", "21")
                .compile(
                        JavaFileObjects.forSourceString("test.app.FakeDataSource", DATA_SOURCE),
                        JavaFileObjects.forSourceString("test.app.FakeDataSourceConsumer", CONSUMER),
                        JavaFileObjects.forSourceString("test.app.FakeHostComponent", COMPONENT_MISSING_ADAPTER));

        assertEquals(
                Compilation.Status.FAILURE,
                compilation.status(),
                () -> "Expected the adapter-less component to fail compilation but it succeeded."
                        + diagnostics(compilation));

        boolean missingBinding = compilation.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .anyMatch(msg -> msg != null
                        && msg.contains("FakeDataSource")
                        && (msg.contains("cannot be provided without an @Provides-annotated method")
                                || msg.contains("cannot be provided")
                                || msg.contains("missing binding")));

        assertTrue(
                missingBinding,
                () -> "Expected a Dagger missing-binding error for FakeDataSource." + diagnostics(compilation));
    }

    /**
     * Renders all compilation diagnostics for inclusion in an assertion failure message.
     *
     * @param compilation the compilation whose diagnostics to render
     * @return a human-readable diagnostic summary
     */
    private static String diagnostics(Compilation compilation) {
        StringBuilder sb = new StringBuilder("\nCompilation diagnostics:\n");
        compilation.diagnostics().forEach(d -> sb.append("  [")
                .append(d.getKind())
                .append("] ")
                .append(d.getMessage(null))
                .append('\n'));
        return sb.toString();
    }
}
