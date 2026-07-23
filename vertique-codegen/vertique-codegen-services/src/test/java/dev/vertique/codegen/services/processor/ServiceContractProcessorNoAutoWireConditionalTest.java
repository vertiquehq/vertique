// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ServiceContractProcessor} emits a compiler warning when an impl is annotated
 * with both {@code @NoAutoWire} and {@code @ConditionalOnProperty} (CG-011 W4 scanner extension).
 *
 * <p>The combination is a no-op: {@code @NoAutoWire} causes the scanner to skip the impl entirely,
 * so the condition is never evaluated. The warning surfaces the silent misconfiguration so that
 * the developer can decide whether to remove {@code @NoAutoWire} (opt into auto-wiring) or remove
 * {@code @ConditionalOnProperty} (acknowledge the manual-wiring path).
 */
class ServiceContractProcessorNoAutoWireConditionalTest {

    private static final JavaFileObject NO_AUTO_WIRE_SOURCE =
            SourceFiles.inline("dev.vertique.codegen.NoAutoWire", """
                    package dev.vertique.codegen;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE) @Retention(RetentionPolicy.SOURCE) @Documented
                    public @interface NoAutoWire {}
                    """);

    private static final JavaFileObject CONDITIONAL_ON_PROPERTY_SOURCE =
            SourceFiles.inline("dev.vertique.codegen.ConditionalOnProperty", """
                    package dev.vertique.codegen;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE) @Retention(RetentionPolicy.SOURCE)
                    @Repeatable(ConditionalOnProperties.class) @Documented
                    public @interface ConditionalOnProperty {
                        String name();
                        String havingValue() default "true";
                        boolean matchIfMissing() default false;
                    }
                    """);

    private static final JavaFileObject CONDITIONAL_ON_PROPERTIES_SOURCE =
            SourceFiles.inline("dev.vertique.codegen.ConditionalOnProperties", """
                    package dev.vertique.codegen;
                    import java.lang.annotation.*;
                    @Target(ElementType.TYPE) @Retention(RetentionPolicy.SOURCE) @Documented
                    public @interface ConditionalOnProperties {
                        ConditionalOnProperty[] value();
                    }
                    """);

    private static final JavaFileObject USER_SERVICE_CONTRACT = SourceFiles.inline("com.example.UserService", """
                    package com.example;
                    import dev.vertique.services.ServiceContract;
                    import dev.vertique.services.ServiceOperation;
                    import io.vertx.core.Future;
                    @ServiceContract(value = "user-service")
                    public interface UserService {
                        @ServiceOperation("get-user")
                        Future<String> getUser(String userId);
                    }
                    """);

    /** A service impl with both @NoAutoWire and @ConditionalOnProperty — warns but compiles. */
    private static final JavaFileObject USER_SERVICE_MANUAL = SourceFiles.inline("com.example.UserServiceManual", """
                    package com.example;
                    import dev.vertique.codegen.NoAutoWire;
                    import dev.vertique.codegen.ConditionalOnProperty;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    @NoAutoWire
                    @ConditionalOnProperty(name = "manualEnabled")
                    public class UserServiceManual implements UserService {
                        @Inject public UserServiceManual() {}
                        @Override public Future<String> getUser(String userId) {
                            return Future.succeededFuture("manual-" + userId);
                        }
                    }
                    """);

    @Test
    @DisplayName("@NoAutoWire + @ConditionalOnProperty emits warning but compilation succeeds")
    void noAutoWireWithConditional_emitsWarning() {
        JavaFileObject[] sources = concat(
                FRAMEWORK_SOURCES,
                NO_AUTO_WIRE_SOURCE,
                CONDITIONAL_ON_PROPERTY_SOURCE,
                CONDITIONAL_ON_PROPERTIES_SOURCE,
                USER_SERVICE_CONTRACT,
                USER_SERVICE_MANUAL);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        // Compilation must succeed — @NoAutoWire prevents contributor emission, but the warning is
        // not an error
        result.assertSuccess();

        // At least one WARNING or MANDATORY_WARNING diagnostic must mention @ConditionalOnProperty
        boolean hasWarning = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
                .map(d -> d.getMessage(null))
                .anyMatch(msg -> msg != null && msg.contains("@ConditionalOnProperty"));

        assertTrue(
                hasWarning,
                "@NoAutoWire + @ConditionalOnProperty combination must produce a WARNING diagnostic"
                        + " containing '@ConditionalOnProperty'");
    }

    // --- Helper ---

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
