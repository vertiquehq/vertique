// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.internal.codegen.ComponentProcessor;
import dev.vertique.codegen.mcp.McpToolProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpInputRejectionException;
import dev.vertique.mcp.tool.McpToolInvoker;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R48 proof-gap closure — a composition-level test proving a REAL {@code @McpTool}-generated,
 * parameterized invoker's constructor-injected {@code Optional<jakarta.validation.Validator>}
 * (R38/W7) is resolved through the PRODUCTION Dagger graph ({@link McpServerModule}'s own {@code
 * @BindsOptionalOf Validator} seam), not merely constructed by hand as {@code
 * McpDaggerAwareValidatorInjectionTest}'s {@code InjectOnlyToolInvoker} fixture does.
 *
 * <p>Compiles one real, parameterized {@code com.example.validated.ValidatedTools} application
 * source — declaring a real {@code @Inject}-only Bean Validation constraint directly on the tool
 * parameter, the same shape {@code McpDaggerAwareValidatorInjectionTest} already proves a
 * reflection-only {@code ConstraintValidatorFactory} cannot construct — together with a real Dagger
 * {@code @Component} that installs the real production {@link McpServerModule}, the real generated
 * {@code GeneratedMcpToolsModule} ({@code McpToolsModuleEmitter}'s output), and a small test module
 * binding a Dagger-aware {@code Validator} exactly like {@code McpDaggerAwareValidatorInjectionTest}'s
 * {@code InjectOnlyValidatorWiringModule}. Both the {@code @McpTool} processor ({@link
 * McpToolProcessor}) and the real Dagger {@link ComponentProcessor} run in the same compilation, so
 * the hand-written {@code @Component} source can reference the generated module by simple name
 * exactly as a real application does — mirroring {@code
 * McpInputProcessingCompositionCompileTestFixture}'s synthetic-component technique, extended with a
 * genuinely generated tools module instead of only {@link McpServerModule} in isolation.
 *
 * <p><strong>Reflection boundary.</strong> The compiled component and its generated {@code
 * Dagger*Component} implementation do not exist as compile-time-known types (their FQNs are only
 * known once this compile-testing run produces them), so {@code create()} and the entry-point
 * accessor are invoked reflectively — the same boundary {@link
 * McpGeneratedHelloToolITFixture#loadInvoker()} and {@code
 * McpGeneratedParameterizedToolITFixture} already cross for a generated invoker's constructor. Every
 * operation after that reflective boundary — {@link McpToolInvoker#prepare} — is a plain interface
 * call on the loaded reference, exactly as production {@link McpRequestDispatcher} does.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpGeneratedInvokerDaggerValidatorCompositionTest {

    private static final String PACKAGE = "com.example.validated";
    private static final String TOOL_NAME = "validated.echo";
    private static final String RESTRICTED_VALUE = "forbidden";

    @Test
    @DisplayName("R48 proof-gap: a real generated parameterized invoker, constructed through the "
            + "production Dagger graph, receives the bound Dagger-aware Validator and its constraint fires")
    void shouldThreadTheProductionDaggerBoundValidatorIntoAGeneratedInvokerAndFireItsConstraint() throws Exception {
        // Given: one real @McpTool source with an @Inject-only Bean Validation constraint on its
        // parameter, and one real Dagger @Component installing the real McpServerModule, the real
        // generated GeneratedMcpToolsModule, and a test module binding a Dagger-aware Validator.
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                List.of(new McpToolProcessor(), new ComponentProcessor()),
                toolSource(),
                constraintSource(),
                policySource(),
                validatorSource(),
                validatorFactorySource(),
                validatorModuleSource(),
                externalsModuleSource(),
                componentSource());

        // Then: the whole graph — application tool, generated invoker/module, and Dagger's own
        // component processor — compiles as one production-shaped composition.
        result.assertSuccess();

        // When: the real Dagger-generated component is constructed and its generated invoker is
        // resolved through it — never hand-constructed via a reflective invoker constructor call.
        Class<?> componentClass = result.loadGeneratedClass(PACKAGE + ".DaggerValidatedToolsComponent");
        Method create = componentClass.getDeclaredMethod("create");
        create.setAccessible(true);
        Object component = create.invoke(null);
        Method toolInvokers = component.getClass().getMethod("toolInvokers");
        toolInvokers.setAccessible(true);

        @SuppressWarnings("unchecked")
        Set<McpToolInvoker> invokers = (Set<McpToolInvoker>) toolInvokers.invoke(component);

        assertThat(invokers)
                .as("the production graph must resolve exactly the one real generated invoker")
                .hasSize(1);
        McpToolInvoker invoker = invokers.iterator().next();
        assertThat(invoker.descriptor().name())
                .as("sanity: this is genuinely the generated invoker for the compiled tool, not a stray")
                .isEqualTo(TOOL_NAME);

        // Then — DECISIVE: the constraint whose only constructor is @Inject-only and takes a
        // Dagger-injected dependency actually evaluated. A validator-resolution failure (the
        // pre-R38/W7 defect) would instead settle as the internal-error fallback the dispatcher
        // produces for a RuntimeException/LinkageError escaping prepare(), not this bounded rejection.
        //
        // Message-content is deliberately NOT asserted here: McpToolInvokerEmitter's shipped Bean
        // Validation rejection is always the fixed, non-interpolated literal "Invalid tool arguments:
        // constraint validation failed" — never a ConstraintViolation#getMessage() — a message-safety
        // policy recorded by the R38/W7 validator-injection task (Hibernate Validator's default
        // interpolator resolves message templates through EL, and a generated invoker's carrier may
        // carry an application-authored constraint whose message is attacker-influenced or
        // EL-templated). Production code is not changed to accommodate this proof. Occurrence of the
        // bounded McpInputRejectionException alone is decisive: the only way prepare() reaches this
        // rejection at all is for RestrictedValueValidator#isValid to have run and returned false — the
        // static McpBeanValidation fallback cannot construct an @Inject-only ConstraintValidator and
        // would instead let a ValidationException escape prepare() as an uncaught RuntimeException,
        // which settles as the internal-error/SSE-fallback path, not this bounded rejection (see
        // McpDaggerAwareValidatorInjectionTest for the identical reasoning against the hand-built
        // fixture invoker this test replaces with a genuinely generated one).
        assertThatThrownBy(() -> invoker.prepare(Map.of("value", RESTRICTED_VALUE), NeverCancelledSignal.INSTANCE))
                .as("DECISIVE: the @Inject-only constraint, evaluated through the Validator the "
                        + "production Dagger graph bound via McpServerModule's Optional<Validator> seam, "
                        + "must reject the restricted value through the bounded input-rejection outcome")
                .isInstanceOf(McpInputRejectionException.class);
    }

    // --- Inline compiled sources ---

    private static JavaFileObject toolSource() {
        return SourceFiles.inline(PACKAGE + ".ValidatedTools", """
                package com.example.validated;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class ValidatedTools {

                    @Inject
                    public ValidatedTools() {}

                    @McpTool(name = "validated.echo", description = "Echoes a value after Bean Validation.")
                    public Future<String> echo(
                            @McpToolParam(name = "value", description = "The value to validate.")
                            @RestrictedValueConstraint
                            String value) {
                        return Future.succeededFuture(value);
                    }
                }
                """);
    }

    private static JavaFileObject constraintSource() {
        return SourceFiles.inline(PACKAGE + ".RestrictedValueConstraint", """
                package com.example.validated;

                import jakarta.validation.Constraint;
                import jakarta.validation.Payload;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.PARAMETER, ElementType.FIELD})
                @Retention(RetentionPolicy.RUNTIME)
                @Constraint(validatedBy = RestrictedValueValidator.class)
                public @interface RestrictedValueConstraint {
                    String message() default "value must not equal the policy's restricted token";

                    Class<?>[] groups() default {};

                    Class<? extends Payload>[] payload() default {};
                }
                """);
    }

    private static JavaFileObject policySource() {
        return SourceFiles.inline(PACKAGE + ".RestrictedTokenPolicy", """
                package com.example.validated;

                import jakarta.inject.Inject;

                /** Stands in for a real Dagger-injected dependency (e.g. a policy/allow-list lookup service). */
                public final class RestrictedTokenPolicy {

                    @Inject
                    public RestrictedTokenPolicy() {}

                    boolean isRestricted(String value) {
                        return "forbidden".equals(value);
                    }
                }
                """);
    }

    private static JavaFileObject validatorSource() {
        return SourceFiles.inline(PACKAGE + ".RestrictedValueValidator", """
                package com.example.validated;

                import jakarta.inject.Inject;
                import jakarta.validation.ConstraintValidator;
                import jakarta.validation.ConstraintValidatorContext;

                /**
                 * A ConstraintValidator whose only constructor is @Inject-annotated and takes a
                 * Dagger-injected dependency — the exact shape a reflection-only ConstraintValidatorFactory
                 * cannot construct.
                 */
                public final class RestrictedValueValidator
                        implements ConstraintValidator<RestrictedValueConstraint, String> {

                    private final RestrictedTokenPolicy policy;

                    @Inject
                    public RestrictedValueValidator(RestrictedTokenPolicy policy) {
                        this.policy = policy;
                    }

                    @Override
                    public boolean isValid(String value, ConstraintValidatorContext context) {
                        return value == null || !policy.isRestricted(value);
                    }
                }
                """);
    }

    private static JavaFileObject validatorFactorySource() {
        return SourceFiles.inline(PACKAGE + ".DaggerAwareConstraintValidatorFactory", """
                package com.example.validated;

                import java.util.Map;
                import java.util.Set;
                import java.util.function.Function;
                import java.util.stream.Collectors;
                import jakarta.validation.ConstraintValidator;
                import jakarta.validation.ConstraintValidatorFactory;
                import jakarta.validation.ValidationException;

                /**
                 * Test-local mirror of {@code vertique-validation}'s production
                 * DaggerConstraintValidatorFactory tier-1 (Dagger-managed instance) / tier-3 (reflection
                 * fallback) resolution — mirrors McpDaggerAwareValidatorInjectionTest's identically-named
                 * fixture. vertique-mcp-server has no dependency on vertique-validation, so this reproduces
                 * the already-tested algorithm locally rather than inventing new production API.
                 */
                public final class DaggerAwareConstraintValidatorFactory implements ConstraintValidatorFactory {

                    private final Map<Class<?>, ConstraintValidator<?, ?>> daggerManaged;

                    public DaggerAwareConstraintValidatorFactory(Set<ConstraintValidator<?, ?>> daggerManaged) {
                        this.daggerManaged =
                                daggerManaged.stream().collect(Collectors.toMap(Object::getClass, Function.identity()));
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                        ConstraintValidator<?, ?> managed = daggerManaged.get(key);
                        if (managed != null) {
                            return (T) managed;
                        }
                        try {
                            return key.getDeclaredConstructor().newInstance();
                        } catch (ReflectiveOperationException e) {
                            throw new ValidationException("Cannot instantiate ConstraintValidator: " + key.getName(), e);
                        }
                    }

                    @Override
                    public void releaseInstance(ConstraintValidator<?, ?> instance) {
                        // No-op, mirrors DaggerConstraintValidatorFactory.
                    }
                }
                """);
    }

    private static JavaFileObject validatorModuleSource() {
        return SourceFiles.inline(PACKAGE + ".RestrictedValueValidatorModule", """
                package com.example.validated;

                import dagger.Binds;
                import dagger.Module;
                import dagger.Provides;
                import dagger.multibindings.IntoSet;
                import jakarta.inject.Singleton;
                import jakarta.validation.ConstraintValidator;
                import jakarta.validation.ConstraintValidatorFactory;
                import jakarta.validation.Validation;
                import jakarta.validation.Validator;
                import jakarta.validation.ValidatorFactory;
                import java.util.Set;

                /**
                 * Stands in for an application-authored Dagger validation module: contributes the
                 * @Inject-only RestrictedValueValidator into the tier-1 managed-validator set, then builds a
                 * real Jakarta Validator configured with the Dagger-aware factory — mirrors
                 * McpDaggerAwareValidatorInjectionTest's InjectOnlyValidatorWiringModule.
                 */
                @Module
                public abstract class RestrictedValueValidatorModule {

                    private RestrictedValueValidatorModule() {}

                    @Binds
                    @IntoSet
                    abstract ConstraintValidator<?, ?> restrictedValueValidator(RestrictedValueValidator validator);

                    @Provides
                    @Singleton
                    static ConstraintValidatorFactory constraintValidatorFactory(
                            Set<ConstraintValidator<?, ?>> daggerManaged) {
                        return new DaggerAwareConstraintValidatorFactory(daggerManaged);
                    }

                    @Provides
                    @Singleton
                    static Validator validator(ConstraintValidatorFactory factory) {
                        ValidatorFactory validatorFactory = Validation.byDefaultProvider()
                                .configure()
                                .constraintValidatorFactory(factory)
                                .buildValidatorFactory();
                        return validatorFactory.getValidator();
                    }
                }
                """);
    }

    private static JavaFileObject externalsModuleSource() {
        return SourceFiles.inline(PACKAGE + ".ValidatedToolsExternalsModule", """
                package com.example.validated;

                import dagger.Module;
                import dagger.Provides;
                import dev.vertique.core.json.JsonMapperProfileRegistry;
                import dev.vertique.input.processing.InputObjectProcessor;
                import dev.vertique.json.DefaultJsonMapperProfileRegistry;
                import dev.vertique.json.JsonConfig;
                import dev.vertique.mcp.server.McpServerConfig;
                import jakarta.inject.Singleton;
                import java.util.Set;

                /**
                 * Supplies the externals a generated invoker's McpToolRuntimeFactory and InputObjectProcessor
                 * dependencies need, real and working (not throwing stubs), mirroring
                 * McpToolRuntimeFactoryTestSupport's own construction.
                 */
                @Module
                public final class ValidatedToolsExternalsModule {

                    private ValidatedToolsExternalsModule() {}

                    @Provides
                    @Singleton
                    static McpServerConfig mcpServerConfig() {
                        return McpServerConfig.defaults();
                    }

                    @Provides
                    @Singleton
                    static JsonConfig jsonConfig() {
                        return JsonConfig.defaults();
                    }

                    @Provides
                    @Singleton
                    static JsonMapperProfileRegistry jsonMapperProfileRegistry() {
                        return new DefaultJsonMapperProfileRegistry(Set.of());
                    }

                    @Provides
                    @Singleton
                    static InputObjectProcessor inputObjectProcessor() {
                        return InputObjectProcessor.createDefault(
                                canonicalizerType -> {
                                    throw new IllegalArgumentException("unresolvable canonicalizer " + canonicalizerType);
                                },
                                sanitizerType -> {
                                    throw new IllegalArgumentException("unresolvable sanitizer " + sanitizerType);
                                });
                    }
                }
                """);
    }

    private static JavaFileObject componentSource() {
        return SourceFiles.inline(PACKAGE + ".ValidatedToolsComponent", """
                package com.example.validated;

                import dagger.Component;
                import dev.vertique.mcp.server.McpServerModule;
                import dev.vertique.mcp.tool.McpToolInvoker;
                import jakarta.inject.Singleton;
                import java.util.Set;

                /**
                 * The production-shaped composition under proof: the real McpServerModule (whose {@code
                 * @BindsOptionalOf Validator} seam is exercised here), the real generated
                 * GeneratedMcpToolsModule (McpToolsModuleEmitter's own output for ValidatedTools, same
                 * package), and this file's test module binding a Dagger-aware Validator.
                 */
                @Singleton
                @Component(modules = {
                        McpServerModule.class,
                        GeneratedMcpToolsModule.class,
                        RestrictedValueValidatorModule.class,
                        ValidatedToolsExternalsModule.class
                })
                interface ValidatedToolsComponent {
                    Set<McpToolInvoker> toolInvokers();
                }
                """);
    }

    /** A cancellation signal that never fires — {@code ValidatedTools#echo} never checks it. */
    private enum NeverCancelledSignal implements McpCancellationSignal {
        INSTANCE;

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Future<Void> cancelled() {
            return Future.future(promise -> {});
        }
    }
}
