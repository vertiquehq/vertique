// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Binds;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpBeanValidation;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpInputRejectionException;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationDecision;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Payload;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

/**
 * Repair task R38 (review finding W7) — proof that Dagger-injected {@link ConstraintValidator}s
 * (an {@code @Inject}-only constructor, resolved through a Dagger-aware {@link
 * ConstraintValidatorFactory}) do not yet run at the MCP tool-input boundary.
 *
 * <p>{@code McpBeanValidation} (stage 4 of the fixed request-time input pipeline, contract §4.7)
 * bootstraps its one shared {@link Validator} through the static {@link
 * Validation#buildDefaultValidatorFactory()}, which resolves every {@link ConstraintValidator} by
 * reflection only (no-arg constructor). A {@link ConstraintValidator} whose only constructor is
 * {@code @Inject} — the shape {@code vertique-validation}'s {@code DaggerConstraintValidatorFactory}
 * exists to resolve, via its tier-1 Dagger-managed-instance / tier-2 contributed-factory / tier-3
 * reflection chain — therefore cannot be constructed there today, and any tool-input bean carrying
 * such a constraint fails closed with an internal-error fallback instead of the bounded
 * {@code INPUT_PROCESSING} rejection every other Bean Validation failure produces.
 *
 * <p>{@code vertique-mcp-server} has no compile dependency on {@code vertique-validation} (see its
 * {@code pom.xml}), so this class does not import {@code DaggerConstraintValidatorFactory} directly.
 * {@link TestDaggerAwareConstraintValidatorFactory} instead locally reproduces that class's
 * already-tested tier-1/tier-3 resolution algorithm (see {@code DaggerConstraintValidatorFactoryTest}
 * in {@code vertique-validation}) — the smallest honest reuse of the pattern, not new production API.
 *
 * <p>Two proofs:
 * <ol>
 *   <li>{@link #shouldConstructAndEvaluateInjectOnlyConstraintThroughDaggerAwareValidatorWiring()}
 *       (GREEN today) — sanity precondition: a real Dagger {@code @Component} genuinely constructs
 *       this exact {@code @Inject}-only {@link ConstraintValidator} and wires it into a working
 *       framework-style {@link Validator}.</li>
 *   <li>{@link #shouldRejectInjectOnlyConstraintViolationAtTheMcpInputBoundary()} (R38/W7, GREEN) —
 *       decisive: the same constraint, evaluated through {@link InjectOnlyToolInvoker#prepare}
 *       exactly as today's real generated invoker shape does (mirroring {@code PipelineToolInvoker}
 *       in {@code McpInputPipelineIT}, T015), settles as the bounded rejection carrying the
 *       constraint's own violation message because R38/W7 threads a bound Dagger-aware {@link
 *       Validator} into generated invokers through their constructor-injected {@code
 *       Optional<Validator>}. Before R38/W7, {@code McpBeanValidation}'s static Validator threw
 *       trying to construct the validator reflectively, settling a 500 internal-error JSON-RPC
 *       fallback instead.</li>
 * </ol>
 *
 * <p>The complementary zero-config regression — a no-arg-constructible constraint (e.g.
 * {@code @NotBlank}, always reflection-tier resolvable) still validates and rejects invalid input
 * with no Validator bound anywhere — is already pinned end-to-end by {@code McpInputPipelineIT
 * #shouldEnforceT015ContractMatrix[shouldRejectBeanValidationFailureBeforeInvocation]} and is not
 * duplicated here.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class McpDaggerAwareValidatorInjectionTest {

    private static final String TOOL_NAME = "inject.only.validation.tool";
    private static final String FORBIDDEN_VALUE = "forbidden";
    private static final String FORBIDDEN_VALUE_MESSAGE = "value must not equal the policy's forbidden token";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"string\"}}}";

    // --- Proof 1a (GREEN today): the Dagger-aware wiring itself genuinely works ---

    @Test
    @DisplayName(
            "sanity: Dagger constructs the @Inject-only ConstraintValidator and the wired " + "Validator evaluates it")
    void shouldConstructAndEvaluateInjectOnlyConstraintThroughDaggerAwareValidatorWiring() {
        // Given: a real Dagger @Component — DaggerMcpDaggerAwareValidatorInjectionTest_
        // InjectOnlyValidatorWiringComponent, generated at compile time from InjectOnlyValidatorWiringModule
        // below — constructs ForbiddenValueValidator through its own @Inject constructor (which takes the
        // trivial @Inject-constructed ForbiddenTokenPolicy dependency) and contributes it into the tier-1
        // Dagger-managed Set<ConstraintValidator<?, ?>> TestDaggerAwareConstraintValidatorFactory resolves
        // from first.
        Validator daggerValidator =
                DaggerMcpDaggerAwareValidatorInjectionTest_InjectOnlyValidatorWiringComponent.create()
                        .validator();

        // When
        Set<ConstraintViolation<InjectOnlyValidatedArgs>> violations =
                daggerValidator.validate(new InjectOnlyValidatedArgs(FORBIDDEN_VALUE));

        // Then — GIVEN precondition for the decisive proof below: the Dagger-aware wiring can build a
        // working Validator for this exact @Inject-only constraint today; only the MCP input boundary
        // itself has no path to one (that gap is what the decisive test below pins).
        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                .as("a Dagger-constructed @Inject-only ConstraintValidator must genuinely evaluate, not "
                        + "merely fail to throw")
                .containsExactly(FORBIDDEN_VALUE_MESSAGE);
    }

    // --- Proof 1b (RED today, GREEN once R38/W7 ships): the real MCP input boundary ---

    @Test
    @DisplayName("R38/W7 DECISIVE: an @Inject-only constraint must be evaluated at the "
            + "MCP input boundary, not silently fall back to a 500 internal error")
    void shouldRejectInjectOnlyConstraintViolationAtTheMcpInputBoundary() {
        // Given: a generated-style invoker whose prepare() runs Bean Validation through the exact
        // production seam (McpBeanValidation) a real generated invoker uses today — constructed with
        // the same Dagger-aware Validator proof 1a builds, exactly as a real generated invoker's
        // constructor-injected Optional<Validator> would resolve it through McpServerModule's
        // @BindsOptionalOf Validator once an application binds a Dagger-aware Validator of its own.
        Validator daggerValidator =
                DaggerMcpDaggerAwareValidatorInjectionTest_InjectOnlyValidatorWiringComponent.create()
                        .validator();
        McpRequestDispatcher dispatcher = buildDispatcher(new InjectOnlyToolInvoker(Optional.of(daggerValidator)));

        // When
        DispatchOutcome outcome =
                dispatchToolCall(dispatcher, TOOL_NAME, new JsonObject().put("value", FORBIDDEN_VALUE));

        // Then — DECISIVE, bounded (no await: dispatch() settles synchronously here, exactly as
        // McpToolInterceptorPipelineTest's identically-shaped mocked dispatch does): R38/W7's bound
        // Dagger-aware Validator constructs ForbiddenValueValidator successfully, so
        // McpBeanValidation.validate(materialized, validator) surfaces its violation as the bounded
        // INPUT_PROCESSING rejection (outcome.isToolResult() == true) rather than the internal-error
        // fallback the static, non-Dagger-aware default settled as before this repair.
        assertThat(outcome.isToolResult())
                .as("DECISIVE: an @Inject-only constraint's violation must settle as the same bounded "
                        + "CallToolResult input-rejection every other Bean Validation failure produces, "
                        + "never the 500 internal-error JSON-RPC fallback")
                .isTrue();
        assertThat(outcome.isError())
                .as("a Bean Validation failure is a tool-error result")
                .isTrue();
        assertThat(outcome.text())
                .as("DECISIVE: the @Inject-only constraint's own violation message must reach the " + "rejection text")
                .contains(FORBIDDEN_VALUE_MESSAGE);
    }

    // --- Constraint fixtures: the @Inject-only ConstraintValidator and its trivial dependency ---

    /**
     * A trivial Dagger-injected dependency — stands in for e.g. a policy/allow-list lookup service.
     * Package-private (not {@code private}): Dagger's generated {@code @Inject}-constructor factory
     * must be able to construct it.
     */
    static final class ForbiddenTokenPolicy {
        @Inject
        ForbiddenTokenPolicy() {}

        boolean isForbidden(String value) {
            return FORBIDDEN_VALUE.equals(value);
        }
    }

    /**
     * A {@link ConstraintValidator} whose only constructor is {@code @Inject}-annotated and takes a
     * dependency — the exact shape a reflection-only {@link ConstraintValidatorFactory} (today's
     * {@code McpBeanValidation}) cannot construct, mirroring {@code
     * DaggerConstraintValidatorFactoryTest.NonInstantiableValidator} but genuinely functional.
     * Package-private (not {@code private}): Dagger's generated {@code @Inject}-constructor factory
     * must be able to construct it.
     */
    static final class ForbiddenValueValidator implements ConstraintValidator<ForbiddenValue, String> {
        private final ForbiddenTokenPolicy policy;

        @Inject
        ForbiddenValueValidator(ForbiddenTokenPolicy policy) {
            this.policy = policy;
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || !policy.isForbidden(value);
        }
    }

    /** The test-only constraint annotation, mirrored on the exact same targets as {@code @PersonName}. */
    @Documented
    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = ForbiddenValueValidator.class)
    private @interface ForbiddenValue {
        String message() default FORBIDDEN_VALUE_MESSAGE;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** The tool-input carrier: one component constrained by the @Inject-only validator above. */
    private record InjectOnlyValidatedArgs(
            @JsonProperty("value") @ForbiddenValue String value) {}

    // --- Dagger-aware wiring (proof 1a) ---

    /**
     * Test-local mirror of {@code vertique-validation}'s production {@code
     * DaggerConstraintValidatorFactory} tier-1 (Dagger-managed instance) / tier-3 (reflection
     * fallback) resolution — see that class's own {@code DaggerConstraintValidatorFactoryTest}.
     * {@code vertique-mcp-server} has no dependency on {@code vertique-validation} (see its {@code
     * pom.xml}), so this reproduces the already-tested algorithm locally rather than inventing new
     * production API or adding a cross-module test dependency. Tier 2 (contributed delegate
     * factories) is intentionally omitted — this proof exercises only tier 1.
     */
    private static final class TestDaggerAwareConstraintValidatorFactory implements ConstraintValidatorFactory {
        private final Map<Class<?>, ConstraintValidator<?, ?>> daggerManaged;

        TestDaggerAwareConstraintValidatorFactory(Set<ConstraintValidator<?, ?>> daggerManaged) {
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
                throw new jakarta.validation.ValidationException(
                        "Cannot instantiate ConstraintValidator: " + key.getName(), e);
            }
        }

        @Override
        public void releaseInstance(ConstraintValidator<?, ?> instance) {
            // No-op, mirrors DaggerConstraintValidatorFactory.
        }
    }

    /**
     * Dagger wiring for proof 1a: contributes {@link ForbiddenValueValidator} (constructed by Dagger
     * through its own {@code @Inject} constructor) into the tier-1 managed-validator set, then builds
     * a real Jakarta {@link Validator} configured with {@link TestDaggerAwareConstraintValidatorFactory}
     * — the framework-style composition {@code ValidationModule}'s {@code validatorFactory}/{@code
     * validator} providers perform in production.
     */
    @Module
    abstract static class InjectOnlyValidatorWiringModule {
        private InjectOnlyValidatorWiringModule() {}

        @Binds
        @IntoSet
        abstract ConstraintValidator<?, ?> forbiddenValueValidator(ForbiddenValueValidator validator);

        @Provides
        @Singleton
        static ConstraintValidatorFactory constraintValidatorFactory(Set<ConstraintValidator<?, ?>> daggerManaged) {
            return new TestDaggerAwareConstraintValidatorFactory(daggerManaged);
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

    @Singleton
    @Component(modules = InjectOnlyValidatorWiringModule.class)
    interface InjectOnlyValidatorWiringComponent {
        Validator validator();
    }

    // --- Fixture invoker (proof 1b) ---

    /**
     * Stands in for one {@code @McpTool}-generated invoker's {@code prepare()}, mirroring the real
     * generated shape exactly — see {@code PipelineToolInvoker} in {@code McpInputPipelineIT} (T015)
     * and {@code McpToolInvokerEmitter}: its constructor accepts an {@code
     * Optional<jakarta.validation.Validator>} (bound through {@code McpServerModule}'s {@code
     * @BindsOptionalOf Validator} seam, resolved by a Dagger-aware {@code
     * ConstraintValidatorFactory} when the application binds one), and stage 4 Bean Validation runs
     * through the shared {@link McpBeanValidation#validate(Object, java.util.Optional)} seam, which
     * routes through that optional {@link Validator} when present and falls back to the static {@code
     * Validation.buildDefaultValidatorFactory()} default when absent (R38/W7). {@link
     * InjectOnlyValidatedArgs} declares no {@code @Canonicalize}/{@code @Sanitize} policy, so stage 2
     * (INP-001) is a genuine no-op here and is intentionally not modeled.
     *
     * <p>Unlike the shipped {@code McpToolInvokerEmitter}, which always throws {@code
     * McpInputRejectionException} with a fixed, non-interpolated literal (deliberately never a
     * {@code ConstraintViolation#getMessage()}, since Hibernate Validator's default interpolator
     * resolves message templates through EL and the dispatcher returns this text to the wire
     * verbatim), this hand-written fixture appends the first violation's own message. That is the
     * decisive proof's fingerprint: the only way {@code
     * ForbiddenValueValidator}'s literal, non-interpolated {@link #FORBIDDEN_VALUE_MESSAGE} can reach
     * the rejection text is for the Dagger-constructed {@code @Inject}-only validator to have actually
     * run — a validator resolution failure or a wrong validator instance could not produce this exact
     * text. Production code does not adopt this: a generated invoker's carrier may carry an
     * application-authored constraint whose message *is* attacker-influenced or EL-templated, so the
     * shipped emitter's fixed literal remains the correct, unconditional choice there.
     */
    private static final class InjectOnlyToolInvoker implements McpToolInvoker {
        private final McpToolDescriptor descriptor;
        private final ObjectMapper mapper = new ObjectMapper();
        private final Optional<Validator> validator;

        InjectOnlyToolInvoker(Optional<Validator> validator) {
            this.validator = validator;
            this.descriptor = new McpToolDescriptor(
                    TOOL_NAME,
                    null,
                    "R38/W7 fixture tool.",
                    new McpToolAnnotations(true, false, true, false),
                    INPUT_SCHEMA,
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        }

        @Override
        public McpToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            InjectOnlyValidatedArgs materialized = mapper.convertValue(arguments, InjectOnlyValidatedArgs.class);
            Set<ConstraintViolation<InjectOnlyValidatedArgs>> violations =
                    McpBeanValidation.validate(materialized, validator);
            if (!violations.isEmpty()) {
                throw new McpInputRejectionException("Invalid tool arguments: constraint validation failed: "
                        + violations.iterator().next().getMessage());
            }
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.copyOf(arguments);
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    return Future.succeededFuture(McpToolResult.text("ok"));
                }
            };
        }
    }

    // --- Direct-dispatch wiring (mirrors McpToolInterceptorPipelineTest's mocked RoutingContext drive) ---

    private static McpRequestDispatcher buildDispatcher(McpToolInvoker invoker) {
        SecurityRuntime securityRuntime = mock(SecurityRuntime.class);
        when(securityRuntime.current()).thenReturn(SecurityContexts.unauthenticated(SecurityIdentity.anonymous()));
        McpPolicyEnforcer policyEnforcer = mock(McpPolicyEnforcer.class);
        when(policyEnforcer.decide(any(), any()))
                .thenReturn(Future.succeededFuture(AuthorizationDecision.permit("PERMITTED")));
        McpToolRegistry registry = McpToolRegistry.build(Set.of(invoker));
        return new McpRequestDispatcher(
                McpServerConfig.defaults(),
                securityRuntime,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                HttpConfig.builder().build(),
                registry,
                policyEnforcer,
                NO_OP_CONTEXT_HOLDER,
                new CorrelationContextFactory(Optional.empty()));
    }

    /**
     * Drives {@link McpRequestDispatcher#dispatch} once against a mocked {@link RoutingContext}
     * carrying a valid {@code tools/call} request for {@code toolName} with {@code arguments}, and
     * decodes whichever of the two possible top-level JSON-RPC shapes the response actually used: a
     * {@code CallToolResult} ({@link DispatchOutcome#isToolResult()} {@code true}), or a JSON-RPC
     * top-level {@code error} object (the internal-error fallback shape, {@code false}). Decoding
     * both shapes leniently — rather than assuming the {@code result} shape and dereferencing it — is
     * what keeps today's RED outcome a clean assertion failure instead of a {@code
     * NullPointerException}.
     */
    private static DispatchOutcome dispatchToolCall(
            McpRequestDispatcher dispatcher, String toolName, JsonObject arguments) {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        RequestBody body = mock(RequestBody.class);

        when(context.request()).thenReturn(request);
        when(context.response()).thenReturn(response);
        when(context.body()).thenReturn(body);
        when(body.buffer()).thenReturn(Buffer.buffer(toolCallRequestBody(toolName, arguments)));
        when(request.headers()).thenReturn(negotiationHeaders(toolName));
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.end(any(Buffer.class))).thenReturn(Future.succeededFuture());

        dispatcher.dispatch(context);

        ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
        verify(response).end(bodyCaptor.capture());
        return decodeOutcome(bodyCaptor.getValue().getBytes());
    }

    private static DispatchOutcome decodeOutcome(byte[] framed) {
        JsonObject decoded = decodeSseJson(framed);
        JsonObject result = decoded.getJsonObject("result");
        if (result != null) {
            JsonArray content = result.getJsonArray("content");
            String text = (content != null && !content.isEmpty())
                    ? content.getJsonObject(0).getString("text")
                    : null;
            return new DispatchOutcome(true, Boolean.TRUE.equals(result.getBoolean("isError")), text);
        }
        JsonObject error = decoded.getJsonObject("error");
        String text = error != null ? error.getString("message") : null;
        return new DispatchOutcome(false, true, text);
    }

    /** Strips the single {@code event: message}/{@code data:} SSE frame and decodes its JSON body. */
    private static JsonObject decodeSseJson(byte[] framed) {
        String text = new String(framed, StandardCharsets.UTF_8);
        String prefix = "event: message\ndata: ";
        String suffix = "\n\n";
        return new JsonObject(text.substring(prefix.length(), text.length() - suffix.length()));
    }

    private static byte[] toolCallRequestBody(String toolName, JsonObject arguments) {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call")
                .put(
                        "params",
                        new JsonObject()
                                .put(
                                        "_meta",
                                        new JsonObject()
                                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()))
                                .put("name", toolName)
                                .put("arguments", arguments))
                .toBuffer()
                .getBytes();
    }

    private static MultiMap negotiationHeaders(String toolName) {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.set("MCP-Protocol-Version", PROTOCOL_VERSION);
        headers.set("Mcp-Method", "tools/call");
        headers.set("Mcp-Name", toolName);
        return headers;
    }

    /** One dispatch's observed outcome: which top-level shape settled, its error flag, and its text. */
    private record DispatchOutcome(boolean isToolResult, boolean isError, String text) {}

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };
}
