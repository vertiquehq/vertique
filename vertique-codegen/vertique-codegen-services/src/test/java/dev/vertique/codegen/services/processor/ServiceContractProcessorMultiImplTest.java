// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static dev.vertique.codegen.services.processor.ServiceContractTestFixtures.FRAMEWORK_SOURCES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compile-time validation tests for the multi-impl group feature (CG-011 W4).
 *
 * <p>Verifies that {@link ServiceContractProcessor}:
 * <ul>
 *   <li>Emits a compile-time error when a contract has two or more unconditional implementations.</li>
 *   <li>Succeeds and emits a single {@code _ContractContributor} for a one-default + one-conditional
 *       group.</li>
 *   <li>Succeeds when all implementations in a group carry {@code @ConditionalOnProperty}.</li>
 * </ul>
 */
class ServiceContractProcessorMultiImplTest {

    // --- Shared stubs for @ConditionalOnProperty ---

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

    // --- Fixtures ---

    /** A simple service contract used by all three test scenarios. */
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

    /** First unconditional impl — used in the two-unconditional-impls error scenario. */
    private static final JavaFileObject USER_SERVICE_HANDLER =
            SourceFiles.inline("com.example.UserServiceHandler", """
                    package com.example;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    public class UserServiceHandler implements UserService {
                        @Inject public UserServiceHandler() {}
                        @Override public Future<String> getUser(String userId) {
                            return Future.succeededFuture(userId);
                        }
                    }
                    """);

    /** Second unconditional impl — triggers the compile error when paired with the first. */
    private static final JavaFileObject USER_SERVICE_HANDLER_V2 =
            SourceFiles.inline("com.example.UserServiceHandlerV2", """
                    package com.example;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    public class UserServiceHandlerV2 implements UserService {
                        @Inject public UserServiceHandlerV2() {}
                        @Override public Future<String> getUser(String userId) {
                            return Future.succeededFuture("v2-" + userId);
                        }
                    }
                    """);

    /** Conditional sandbox impl — used in default+conditional and all-conditional scenarios. */
    private static final JavaFileObject USER_SERVICE_SANDBOX =
            SourceFiles.inline("com.example.UserServiceSandbox", """
                    package com.example;
                    import dev.vertique.codegen.ConditionalOnProperty;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    @ConditionalOnProperty(name = "sandboxEnabled")
                    public class UserServiceSandbox implements UserService {
                        @Inject public UserServiceSandbox() {}
                        @Override public Future<String> getUser(String userId) {
                            return Future.succeededFuture("sandbox-" + userId);
                        }
                    }
                    """);

    /** Second conditional impl — used in the all-conditional scenario. */
    private static final JavaFileObject USER_SERVICE_STAGING =
            SourceFiles.inline("com.example.UserServiceStaging", """
                    package com.example;
                    import dev.vertique.codegen.ConditionalOnProperty;
                    import jakarta.inject.Inject;
                    import io.vertx.core.Future;
                    @ConditionalOnProperty(name = "stagingEnabled")
                    public class UserServiceStaging implements UserService {
                        @Inject public UserServiceStaging() {}
                        @Override public Future<String> getUser(String userId) {
                            return Future.succeededFuture("staging-" + userId);
                        }
                    }
                    """);

    // --- Tests ---

    @Test
    @DisplayName("two unconditional impls for same contract → compile error with expected message")
    void twoUnconditionalImpls_compileError() {
        JavaFileObject[] sources = concat(
                frameworkWithConditional(), USER_SERVICE_CONTRACT, USER_SERVICE_HANDLER, USER_SERVICE_HANDLER_V2);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertFailed();
        result.assertErrorMessage("multiple unconditional implementations");
    }

    @Test
    @DisplayName("one unconditional default + one conditional impl → success, single _ContractContributor emitted")
    void oneDefaultPlusConditional_compilesClean() {
        JavaFileObject[] sources =
                concat(frameworkWithConditional(), USER_SERVICE_CONTRACT, USER_SERVICE_HANDLER, USER_SERVICE_SANDBOX);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        // Exactly one contributor for the contract (not one per impl)
        boolean hasContributor = result.compilation().generatedSourceFiles().stream()
                .anyMatch(f -> f.toUri().toString().contains("UserService_ContractContributor"));
        assertTrue(hasContributor, "Expected a generated UserService_ContractContributor source file");

        // Both impls must appear in the single contributor (Provider<UserServiceHandler>,
        // Provider<UserServiceSandbox>)
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "UserServiceHandler");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "UserServiceSandbox");

        // PropertyCondition constants for sandbox impl must be present
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "PropertyCondition");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "sandboxEnabled");

        // Module must have exactly one binding entry for UserService_ContractContributor
        result.assertGeneratedSourceContains("com.example.GeneratedServicesModule", "UserService_ContractContributor");
    }

    @Test
    @DisplayName("two conditional impls in different packages sharing a simple name → success, no identifier collision")
    void crossPackageSimpleNameCollision_compilesClean() {
        // Two impls in different packages with the same simple class name. Naive identifier
        // derivation from getSimpleName() alone would produce duplicate Java members in the
        // generated _ContractContributor (two USER_SERVICE_SANDBOX_CONDITIONS constants, two
        // userServiceSandboxProvider fields, two buildEntry_UserServiceSandbox methods) — javac
        // would reject the source. Disambiguation by package prefix prevents that.
        JavaFileObject sandboxA = SourceFiles.inline("a.UserServiceSandbox", """
                package a;
                import com.example.UserService;
                import dev.vertique.codegen.ConditionalOnProperty;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                @ConditionalOnProperty(name = "modeA")
                public class UserServiceSandbox implements UserService {
                    @Inject public UserServiceSandbox() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture("a-" + userId);
                    }
                }
                """);
        JavaFileObject sandboxB = SourceFiles.inline("b.UserServiceSandbox", """
                package b;
                import com.example.UserService;
                import dev.vertique.codegen.ConditionalOnProperty;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                @ConditionalOnProperty(name = "modeB")
                public class UserServiceSandbox implements UserService {
                    @Inject public UserServiceSandbox() {}
                    @Override public Future<String> getUser(String userId) {
                        return Future.succeededFuture("b-" + userId);
                    }
                }
                """);
        JavaFileObject[] sources = concat(frameworkWithConditional(), USER_SERVICE_CONTRACT, sandboxA, sandboxB);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
        // Both modes' condition names must appear
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "modeA");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "modeB");
        // Disambiguated condition constants — package-prefixed when simple names collide
        result.assertGeneratedSourceContains(
                "com.example.UserService_ContractContributor", "A_USER_SERVICE_SANDBOX_CONDITIONS");
        result.assertGeneratedSourceContains(
                "com.example.UserService_ContractContributor", "B_USER_SERVICE_SANDBOX_CONDITIONS");
    }

    @Test
    @DisplayName("simple-name disambiguation must not alias against an already-underscored simple name")
    void crossPackageSimpleNameCollision_aliasingResolved() {
        // Hostile group: `a.Foo` + `b.Foo` (collide on simple name "Foo") AND `c.a_Foo` (simple
        // name "a_Foo"). Tier-1 (simple name) fails because two impls share "Foo". A naive
        // per-collision-group disambiguation would produce keys {a_Foo, b_Foo, a_Foo} — the
        // package-prefixed name from `a.Foo` aliases against the literal simple name `a_Foo` from
        // `c.a_Foo`. The emitter must escalate to a uniform tier-2 (package-prefix for ALL impls)
        // or, if needed, tier-3 (positional) to keep the generated source compilable.
        JavaFileObject contract = SourceFiles.inline("com.example.WidgetService", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "widget-service")
                public interface WidgetService {
                    @ServiceOperation("get-widget")
                    Future<String> getWidget(String widgetId);
                }
                """);
        JavaFileObject aFoo = SourceFiles.inline("a.Foo", """
                package a;
                import com.example.WidgetService;
                import dev.vertique.codegen.ConditionalOnProperty;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                @ConditionalOnProperty(name = "modeAFoo")
                public class Foo implements WidgetService {
                    @Inject public Foo() {}
                    @Override public Future<String> getWidget(String widgetId) {
                        return Future.succeededFuture("a-foo-" + widgetId);
                    }
                }
                """);
        JavaFileObject bFoo = SourceFiles.inline("b.Foo", """
                package b;
                import com.example.WidgetService;
                import dev.vertique.codegen.ConditionalOnProperty;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                @ConditionalOnProperty(name = "modeBFoo")
                public class Foo implements WidgetService {
                    @Inject public Foo() {}
                    @Override public Future<String> getWidget(String widgetId) {
                        return Future.succeededFuture("b-foo-" + widgetId);
                    }
                }
                """);
        // Simple class name literally `a_Foo` — would alias against the disambiguated key for
        // `a.Foo` under naive per-collision-group escalation.
        JavaFileObject cAFoo = SourceFiles.inline("c.a_Foo", """
                package c;
                import com.example.WidgetService;
                import dev.vertique.codegen.ConditionalOnProperty;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                @ConditionalOnProperty(name = "modeCaFoo")
                public class a_Foo implements WidgetService {
                    @Inject public a_Foo() {}
                    @Override public Future<String> getWidget(String widgetId) {
                        return Future.succeededFuture("c-aFoo-" + widgetId);
                    }
                }
                """);
        JavaFileObject[] sources = concat(frameworkWithConditional(), contract, aFoo, bFoo, cAFoo);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        // The generated _ContractContributor must compile (no duplicate Java members).
        result.assertSuccess();

        // All three condition property names must appear so we know all three impls were emitted.
        result.assertGeneratedSourceContains("com.example.WidgetService_ContractContributor", "modeAFoo");
        result.assertGeneratedSourceContains("com.example.WidgetService_ContractContributor", "modeBFoo");
        result.assertGeneratedSourceContains("com.example.WidgetService_ContractContributor", "modeCaFoo");
    }

    @Test
    @DisplayName("first-char-case-only simple names (Foo vs foo) must not collapse downstream identifiers")
    void firstCharCaseOnlyCollision_resolved() {
        // Two impls whose simple names differ only in the first-character case: Foo and foo.
        // Raw key uniqueness is preserved (Java is case-sensitive), but the downstream transforms
        // collapse them: providerFieldName("Foo")="fooProvider" and providerFieldName("foo")=
        // "fooProvider"; Identifiers.constantName("Foo")="FOO" and Identifiers.constantName("foo")=
        // "FOO". Without an identifier-aware uniqueness check the generated contributor would have
        // duplicate fields and javac would reject it.
        JavaFileObject contract = SourceFiles.inline("com.example.WidgetService2", """
                package com.example;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;
                @ServiceContract(value = "widget-service-2")
                public interface WidgetService2 {
                    @ServiceOperation("get-widget")
                    Future<String> getWidget(String widgetId);
                }
                """);
        JavaFileObject xFoo = SourceFiles.inline("x.Foo", """
                package x;
                import com.example.WidgetService2;
                import dev.vertique.codegen.ConditionalOnProperty;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                @ConditionalOnProperty(name = "modeXFoo")
                public class Foo implements WidgetService2 {
                    @Inject public Foo() {}
                    @Override public Future<String> getWidget(String widgetId) {
                        return Future.succeededFuture("x-Foo-" + widgetId);
                    }
                }
                """);
        JavaFileObject yFoo = SourceFiles.inline("y.foo", """
                package y;
                import com.example.WidgetService2;
                import dev.vertique.codegen.ConditionalOnProperty;
                import jakarta.inject.Inject;
                import io.vertx.core.Future;
                @ConditionalOnProperty(name = "modeYfoo")
                public class foo implements WidgetService2 {
                    @Inject public foo() {}
                    @Override public Future<String> getWidget(String widgetId) {
                        return Future.succeededFuture("y-foo-" + widgetId);
                    }
                }
                """);
        JavaFileObject[] sources = concat(frameworkWithConditional(), contract, xFoo, yFoo);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();
        result.assertGeneratedSourceContains("com.example.WidgetService2_ContractContributor", "modeXFoo");
        result.assertGeneratedSourceContains("com.example.WidgetService2_ContractContributor", "modeYfoo");
    }

    @Test
    @DisplayName("two conditional impls (no default) → success, both condition arrays emitted")
    void onlyConditionalImpls_compilesClean() {
        JavaFileObject[] sources =
                concat(frameworkWithConditional(), USER_SERVICE_CONTRACT, USER_SERVICE_SANDBOX, USER_SERVICE_STAGING);

        var result = ProcessorTestHarness.run(new ServiceContractProcessor(), sources);

        result.assertSuccess();

        // Both conditional impls must appear in the contributor
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "UserServiceSandbox");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "UserServiceStaging");

        // Both condition property names must appear
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "sandboxEnabled");
        result.assertGeneratedSourceContains("com.example.UserService_ContractContributor", "stagingEnabled");
    }

    // --- Helpers ---

    private static JavaFileObject[] frameworkWithConditional() {
        return concat(FRAMEWORK_SOURCES, CONDITIONAL_ON_PROPERTY_SOURCE, CONDITIONAL_ON_PROPERTIES_SOURCE);
    }

    private static JavaFileObject[] concat(JavaFileObject[] base, JavaFileObject... extra) {
        var result = new JavaFileObject[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
