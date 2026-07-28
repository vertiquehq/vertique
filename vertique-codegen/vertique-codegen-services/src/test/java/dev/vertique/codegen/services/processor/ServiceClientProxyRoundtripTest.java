// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.context.ContextValues;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.ServiceDispatchContextCapturer;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * CG-015 §6 S1 roundtrip proof for the (not-yet-emitted) {@code {Contract}_ServiceClientProxy}
 * companion.
 *
 * <p>Compiles fixture {@code @ServiceContract} interfaces (no impl classes — the fixtures are
 * contract-only) against the real framework classpath via {@link ServiceContractProcessor}, loads
 * the generated proxy class through {@link ProcessorTestHarness.Result#loadGeneratedClass(String)},
 * and instantiates it directly via its public 3-arg constructor
 * {@code (ServiceRequestSender, DispatchEnvelopeBuilder, ContractEntry<?>)} — the
 * {@code ServiceClientFactory} companion-selection seam does not exist until CG-015 §6 S3, so every
 * test here bypasses the factory entirely and drives the constructor and generated methods by
 * reflection.
 *
 * <p><strong>Why every test here is currently RED:</strong> {@code ClientProxyEmitter} does not
 * exist yet, so the contract-only fixtures compile cleanly (a lone {@code @ServiceContract}
 * interface with no impl is simply skipped by {@link
 * dev.vertique.codegen.services.processor.scan.ImplCandidateScanner}, which only scans concrete
 * impl classes) but produce no {@code _ServiceClientProxy} class. Every test therefore fails at
 * {@code loadGeneratedClass("...{Contract}_ServiceClientProxy")}.
 *
 * <p><strong>D1 falsifiability tests</strong> ({@link #runtimeOneWayContradictionWins()} and
 * {@link #runtimeParamIndexContradictionWins()}) hand-build a {@link ContractEntry} whose runtime
 * {@link ServiceMethodMeta} deliberately contradicts the fixture's source declaration. Per CG-015
 * D1, {@code oneWay} and payload/SecurityContext parameter indices are runtime-owned (read from
 * {@code entry.operations()} at construction), not baked from the source signature at APT time — an
 * emitter that bakes either at compile time fails these tests once the emitter exists.
 *
 * <p>The mismatch-message protocol (CG-015 §4.2) is pinned by {@link
 * #ctorFailsFastOnMissingOperation()}: the generated constructor's message for a baked operation id
 * absent from {@code entry.operations()} must start with the literal
 * {@code "Service client contract mismatch: "} and name both the contract and the operation.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("Service client proxy — construction and dispatch roundtrip (CG-015 §6 S1)")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceClientProxyRoundtripTest {

    // --- Fixture sources (contract-only — no impl classes) ---

    private static final JavaFileObject GREETER = SourceFiles.inline("com.example.Greeter", """
            package com.example;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("greeter")
            public interface Greeter {
                @ServiceOperation("greet")
                Future<String> greet(String name);
            }
            """);

    private static final JavaFileObject SC_GREETER = SourceFiles.inline("com.example.ScGreeter", """
            package com.example;
            import dev.vertique.security.SecurityContext;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("sc-greeter")
            public interface ScGreeter {
                @ServiceOperation("greetSc")
                Future<String> greetSc(SecurityContext sc, String name);
            }
            """);

    private static final JavaFileObject TWO_SC_GREETER = SourceFiles.inline("com.example.TwoScGreeter", """
            package com.example;
            import dev.vertique.security.SecurityContext;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("two-sc-greeter")
            public interface TwoScGreeter {
                @ServiceOperation("twoSc")
                Future<String> twoSc(SecurityContext first, SecurityContext second, String name);
            }
            """);

    private static final JavaFileObject ONE_WAY_GREETER = SourceFiles.inline("com.example.OneWayGreeter", """
            package com.example;
            import dev.vertique.services.OneWay;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("oneway-greeter")
            public interface OneWayGreeter {
                @OneWay
                @ServiceOperation("notify")
                Future<Void> notify(String name);
            }
            """);

    /** Default method with a sentinel body: a broken proxy would execute it locally instead of dispatching. */
    private static final JavaFileObject DEFAULT_GREETER = SourceFiles.inline("com.example.DefaultGreeter", """
            package com.example;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("default-greeter")
            public interface DefaultGreeter {
                @ServiceOperation("viaDefault")
                default Future<String> viaDefault(String x) {
                    throw new IllegalStateException("local-body-executed");
                }
            }
            """);

    /** Two operations, used to build an entry that deliberately omits one of them. */
    private static final JavaFileObject PARTIAL_GREETER = SourceFiles.inline("com.example.PartialGreeter", """
            package com.example;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("partial-greeter")
            public interface PartialGreeter {
                @ServiceOperation("opA")
                Future<String> opA(String x);

                @ServiceOperation("opB")
                Future<String> opB(String x);
            }
            """);

    /**
     * Security context first, payload second — a legal contract shape whose source classification
     * (index 0 {@code DISPATCH_CONTEXT}, index 1 {@code PAYLOAD}) is the mirror image of the runtime
     * metadata the D1 index-contradiction test hand-builds for it.
     */
    private static final JavaFileObject TWO_PARAM_GREETER = SourceFiles.inline("com.example.TwoParamGreeter", """
            package com.example;
            import dev.vertique.security.SecurityContext;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("two-param-greeter")
            public interface TwoParamGreeter {
                @ServiceOperation("op")
                Future<String> op(SecurityContext sc, String name);
            }
            """);

    /** One shared compilation for all fixtures above — cheaper than one {@code javac} run per test. */
    private static final ProcessorTestHarness.Result RESULT = ProcessorTestHarness.run(
            new ServiceContractProcessor(),
            GREETER,
            SC_GREETER,
            TWO_SC_GREETER,
            ONE_WAY_GREETER,
            DEFAULT_GREETER,
            PARTIAL_GREETER,
            TWO_PARAM_GREETER);

    // --- Tests ---

    @Test
    @DisplayName("greet(\"x\") dispatches to the entry's op address with the payload and unwraps the stubbed Result")
    void dispatchesPayloadToEntryAddress() throws Exception {
        Class<?> greeterClass = RESULT.loadGeneratedClass("com.example.Greeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.Greeter_ServiceClientProxy");

        ContractEntry<?> entry = greeterEntry(greeterClass);
        ServiceMethodMeta meta = entry.operations().get("greet");

        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("hello, x")));

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method greetMethod = proxyClass.getMethod("greet", String.class);

        Future<?> result = (Future<?>) greetMethod.invoke(proxy, "x");

        ArgumentCaptor<ResolvedServiceTarget> targetCaptor = forClass(ResolvedServiceTarget.class);
        ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
        verify(sender).send(targetCaptor.capture(), envelopeCaptor.capture());

        assertEquals(
                meta.address(), targetCaptor.getValue().address(), "target address must equal the entry's op address");
        assertEquals("x", envelopeCaptor.getValue().payload(), "envelope payload must equal the caller's argument");
        assertTrue(result.succeeded(), "returned future must complete successfully");
        assertEquals("hello, x", result.result(), "returned future must unwrap the stubbed Result value");
    }

    @Test
    @DisplayName("ambient SecurityContext suppresses the caller-supplied SC override (FR-CTX-063)")
    void ambientSecurityContextSuppressesCallerOverride(Vertx vertx, VertxTestContext ctx) throws Throwable {
        Class<?> scGreeterClass = RESULT.loadGeneratedClass("com.example.ScGreeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.ScGreeter_ServiceClientProxy");

        ContractEntry<?> entry = scGreeterEntry(scGreeterClass);
        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method greetScMethod = proxyClass.getMethod("greetSc", SecurityContext.class, String.class);

        SecurityContext ambientSc = testSecurityContext("ambient-user");
        SecurityContext explicitSc = testSecurityContext("explicit-user");

        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try (ContextHolder.Scope scope = ContextValues.bind(SecurityContext.class, ambientSc)) {
                greetScMethod.invoke(proxy, explicitSc, "y");

                ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
                verify(sender).send(any(), envelopeCaptor.capture());
                Map<String, Object> dispatchContext =
                        envelopeCaptor.getValue().metadata().dispatchContext();
                assertFalse(
                        dispatchContext.containsKey(SecurityContext.class.getName()),
                        "no SC caller override must be present when an ambient SC is bound");
                ctx.completeNow();
            } catch (Throwable t) {
                ctx.failNow(t);
            }
        });

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) {
            throw ctx.causeOfFailure();
        }
    }

    @Test
    @DisplayName("explicit SecurityContext argument is forwarded as the caller override when no ambient SC is bound")
    void explicitSecurityContextUsedWhenNoAmbient() throws Exception {
        Class<?> scGreeterClass = RESULT.loadGeneratedClass("com.example.ScGreeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.ScGreeter_ServiceClientProxy");

        ContractEntry<?> entry = scGreeterEntry(scGreeterClass);
        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method greetScMethod = proxyClass.getMethod("greetSc", SecurityContext.class, String.class);

        SecurityContext explicitSc = testSecurityContext("explicit-user");
        greetScMethod.invoke(proxy, explicitSc, "y");

        ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
        verify(sender).send(any(), envelopeCaptor.capture());
        Object override = envelopeCaptor.getValue().metadata().dispatchContext().get(SecurityContext.class.getName());
        assertSame(
                explicitSc, override, "explicit SC must be forwarded via callerOverrides when no ambient SC is bound");
    }

    @Test
    @DisplayName("first-declared SecurityContext parameter wins when two SC params are present")
    void firstSecurityContextParamWinsInDeclarationOrder() throws Exception {
        Class<?> twoScGreeterClass = RESULT.loadGeneratedClass("com.example.TwoScGreeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.TwoScGreeter_ServiceClientProxy");

        Method twoScMethod =
                twoScGreeterClass.getMethod("twoSc", SecurityContext.class, SecurityContext.class, String.class);
        ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(twoScGreeterClass)
                .serviceInstance(new Object())
                .name("two-sc-greeter")
                .operation("twoSc")
                .method(twoScMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("first", ParamSource.DISPATCH_CONTEXT, SecurityContext.class)
                .param("second", ParamSource.DISPATCH_CONTEXT, SecurityContext.class)
                .param("name", ParamSource.PAYLOAD, String.class)
                .done()
                .build();

        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method twoScProxyMethod =
                proxyClass.getMethod("twoSc", SecurityContext.class, SecurityContext.class, String.class);

        SecurityContext first = testSecurityContext("first-user");
        SecurityContext second = testSecurityContext("second-user");
        twoScProxyMethod.invoke(proxy, first, second, "z");

        ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
        verify(sender).send(any(), envelopeCaptor.capture());
        Object override = envelopeCaptor.getValue().metadata().dispatchContext().get(SecurityContext.class.getName());
        assertSame(first, override, "the first-declared SecurityContext parameter must win over the second");
    }

    @Test
    @DisplayName("greet(null) builds an envelope with a null payload and does not throw")
    void nullPayloadAllowed() throws Exception {
        Class<?> greeterClass = RESULT.loadGeneratedClass("com.example.Greeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.Greeter_ServiceClientProxy");

        ContractEntry<?> entry = greeterEntry(greeterClass);
        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method greetMethod = proxyClass.getMethod("greet", String.class);

        Object result = greetMethod.invoke(proxy, new Object[] {null});

        ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
        verify(sender).send(any(), envelopeCaptor.capture());
        assertNull(envelopeCaptor.getValue().payload(), "payload must be null when the caller passes null");
        assertNotNull(result, "invocation with a null payload argument must not throw");
    }

    @Test
    @DisplayName("@OneWay operation dispatches via sendOneWay, never send, and completes a succeeded Future<Void>")
    void oneWayUsesSendOneWay() throws Exception {
        Class<?> oneWayGreeterClass = RESULT.loadGeneratedClass("com.example.OneWayGreeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.OneWayGreeter_ServiceClientProxy");

        Method notifyMethod = oneWayGreeterClass.getMethod("notify", String.class);
        ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(oneWayGreeterClass)
                .serviceInstance(new Object())
                .name("oneway-greeter")
                .operation("notify")
                .method(notifyMethod)
                .payloadType(String.class)
                .returnType(Void.class)
                .param("name", ParamSource.PAYLOAD, String.class)
                .oneWay()
                .done()
                .build();

        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.sendOneWay(any(), any())).thenReturn(Future.succeededFuture());

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method notifyProxyMethod = proxyClass.getMethod("notify", String.class);

        Future<?> result = (Future<?>) notifyProxyMethod.invoke(proxy, "ping");

        verify(sender).sendOneWay(any(), any());
        verify(sender, never()).send(any(), any());
        assertTrue(result.succeeded(), "one-way dispatch must return a succeeded Future<Void>");
    }

    @Test
    @DisplayName("a default contract method dispatches remotely instead of executing its local body")
    void defaultMethodDispatchesRemotely() throws Exception {
        Class<?> defaultGreeterClass = RESULT.loadGeneratedClass("com.example.DefaultGreeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.DefaultGreeter_ServiceClientProxy");

        Method viaDefaultMethod = defaultGreeterClass.getMethod("viaDefault", String.class);
        ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(defaultGreeterClass)
                .serviceInstance(new Object())
                .name("default-greeter")
                .operation("viaDefault")
                .method(viaDefaultMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("x", ParamSource.PAYLOAD, String.class)
                .done()
                .build();

        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("remote-ok")));

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method viaDefaultProxyMethod = proxyClass.getMethod("viaDefault", String.class);

        Object result;
        try {
            result = viaDefaultProxyMethod.invoke(proxy, "x");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalStateException ise && "local-body-executed".equals(ise.getMessage())) {
                fail("the default method's local body executed instead of dispatching remotely: " + ise);
                return;
            }
            throw e;
        }

        verify(sender).send(any(), any());
        assertNotNull(result, "the generated override must handle the call, not the default body's sentinel throw");
    }

    @Test
    @DisplayName("runtime oneWay=true wins over the source method lacking @OneWay (D1 falsifiability)")
    void runtimeOneWayContradictionWins() throws Exception {
        Class<?> greeterClass = RESULT.loadGeneratedClass("com.example.Greeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.Greeter_ServiceClientProxy");

        Method greetMethod = greeterClass.getMethod("greet", String.class);
        // "greet" carries no @OneWay in the source — the entry's runtime meta contradicts it.
        ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(greeterClass)
                .serviceInstance(new Object())
                .name("greeter")
                .operation("greet")
                .method(greetMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("name", ParamSource.PAYLOAD, String.class)
                .oneWay()
                .done()
                .build();

        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.sendOneWay(any(), any())).thenReturn(Future.succeededFuture());

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method greetProxyMethod = proxyClass.getMethod("greet", String.class);

        greetProxyMethod.invoke(proxy, "x");

        verify(sender).sendOneWay(any(), any());
        verify(sender, never()).send(any(), any());
    }

    /**
     * The entry built here <strong>deliberately contradicts</strong> the fixture's source
     * declaration — the runtime metadata marks the {@code SecurityContext} parameter (source index 0)
     * as the {@code PAYLOAD} and the {@code String} parameter (source index 1) as the
     * {@code SecurityContext}-keyed dispatch-context param, i.e. the exact mirror of what the source
     * signature says — so an emitter that baked parameter roles at annotation-processing time would
     * dispatch the {@code String} and fail this test; do not "correct" the entry to match the source.
     *
     * @throws Exception if the generated proxy cannot be loaded, constructed, or invoked
     */
    @Test
    @DisplayName("runtime PAYLOAD param index wins over the source declaration order (D1 falsifiability)")
    void runtimeParamIndexContradictionWins() throws Exception {
        Class<?> twoParamGreeterClass = RESULT.loadGeneratedClass("com.example.TwoParamGreeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.TwoParamGreeter_ServiceClientProxy");

        Method opMethod = twoParamGreeterClass.getMethod("op", SecurityContext.class, String.class);
        // Source classification is: index 0 = DISPATCH_CONTEXT (SecurityContext), index 1 = PAYLOAD.
        // The entry deliberately contradicts that, swapping the two roles.
        ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(twoParamGreeterClass)
                .serviceInstance(new Object())
                .name("two-param-greeter")
                .operation("op")
                .method(opMethod)
                .payloadType(SecurityContext.class)
                .returnType(String.class)
                .param("sc", ParamSource.PAYLOAD, SecurityContext.class)
                .param("name", ParamSource.DISPATCH_CONTEXT, SecurityContext.class)
                .done()
                .build();

        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));

        Object proxy = newProxy(proxyClass, sender, newEnvelopeBuilder(), entry);
        Method opProxyMethod = proxyClass.getMethod("op", SecurityContext.class, String.class);

        SecurityContext scArg = testSecurityContext("payload-by-runtime-index");
        opProxyMethod.invoke(proxy, scArg, "secondArg");

        ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
        verify(sender).send(any(), envelopeCaptor.capture());
        assertSame(
                scArg,
                envelopeCaptor.getValue().payload(),
                "payload must be extracted from the runtime meta's PAYLOAD index (0) — the source's"
                        + " SecurityContext param — not from the source's declared payload param");
    }

    @Test
    @DisplayName("constructor fails fast when the entry is missing an operation the contract declares")
    void ctorFailsFastOnMissingOperation() throws Exception {
        Class<?> partialGreeterClass = RESULT.loadGeneratedClass("com.example.PartialGreeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.PartialGreeter_ServiceClientProxy");

        Method opAMethod = partialGreeterClass.getMethod("opA", String.class);
        // Entry carries only "opA" — "opB" is missing, so the ctor must fail fast for opB.
        ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(partialGreeterClass)
                .serviceInstance(new Object())
                .name("partial-greeter")
                .operation("opA")
                .method(opAMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("x", ParamSource.PAYLOAD, String.class)
                .done()
                .build();

        InvocationTargetException thrown = assertThrows(
                InvocationTargetException.class,
                () -> newProxy(proxyClass, mock(ServiceRequestSender.class), newEnvelopeBuilder(), entry));

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        String message = thrown.getCause().getMessage();
        assertTrue(
                message.startsWith("Service client contract mismatch: "),
                "message must start with the pinned mismatch prefix, was: " + message);
        assertTrue(message.contains("PartialGreeter"), "message must name the contract");
        assertTrue(message.contains("opB"), "message must name the missing operation");
    }

    @Test
    @DisplayName("toString() equals ServiceProxy[Greeter]")
    void toStringEqualsServiceProxyName() throws Exception {
        Class<?> greeterClass = RESULT.loadGeneratedClass("com.example.Greeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.Greeter_ServiceClientProxy");

        ContractEntry<?> entry = greeterEntry(greeterClass);
        Object proxy = newProxy(proxyClass, mock(ServiceRequestSender.class), newEnvelopeBuilder(), entry);

        assertEquals("ServiceProxy[Greeter]", proxy.toString());
    }

    @Test
    @DisplayName("equals/hashCode use Object identity semantics — no overrides")
    void equalsAndHashCodeAreIdentity() throws Exception {
        Class<?> greeterClass = RESULT.loadGeneratedClass("com.example.Greeter");
        Class<?> proxyClass = RESULT.loadGeneratedClass("com.example.Greeter_ServiceClientProxy");

        ContractEntry<?> entry = greeterEntry(greeterClass);
        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        DispatchEnvelopeBuilder envelopeBuilder = newEnvelopeBuilder();

        Object proxyA = newProxy(proxyClass, sender, envelopeBuilder, entry);
        Object proxyB = newProxy(proxyClass, sender, envelopeBuilder, entry);

        assertTrue(proxyA.equals(proxyA), "a proxy must equal itself");
        assertFalse(proxyA.equals(proxyB), "two distinct instances built from the same entry must not be equal");
        assertEquals(
                System.identityHashCode(proxyA),
                proxyA.hashCode(),
                "hashCode must be identity-consistent — no override");
    }

    // --- Construction helpers ---

    /**
     * Looks up and invokes the generated proxy's public 3-arg constructor reflectively.
     *
     * @param proxyClass      the loaded {@code {Contract}_ServiceClientProxy} class
     * @param sender          the (mock) sender to inject
     * @param envelopeBuilder the envelope builder to inject
     * @param entry           the registry-resolved contract entry to inject
     * @return the constructed proxy instance
     * @throws ReflectiveOperationException if the constructor cannot be found or invoked
     */
    private static Object newProxy(
            Class<?> proxyClass,
            ServiceRequestSender sender,
            DispatchEnvelopeBuilder envelopeBuilder,
            ContractEntry<?> entry)
            throws ReflectiveOperationException {
        Constructor<?> ctor = proxyClass.getDeclaredConstructor(
                ServiceRequestSender.class, DispatchEnvelopeBuilder.class, ContractEntry.class);
        ctor.setAccessible(true);
        return ctor.newInstance(sender, envelopeBuilder, entry);
    }

    /**
     * Builds a {@link DispatchEnvelopeBuilder} backed by empty SPI registries — no encoders run, so
     * the built envelope's dispatch context is exactly the caller-supplied overrides. Matches the
     * package-private construction {@link dev.vertique.services.ServiceClientFactory} uses in its
     * own test-only constructor.
     *
     * @return a fresh envelope builder with no registered encoders/decoders
     */
    private static DispatchEnvelopeBuilder newEnvelopeBuilder() {
        return new DispatchEnvelopeBuilder(new ServiceDispatchContextCapturer(
                new ServiceDispatchContextRegistry(Set.of(), Set.of()), new DefaultContextHolder()));
    }

    /**
     * Hand-builds the {@link ContractEntry} for the {@code Greeter} fixture, matching its source
     * declaration exactly (one PAYLOAD param).
     *
     * @param greeterClass the loaded {@code com.example.Greeter} interface
     * @return the built contract entry
     * @throws ReflectiveOperationException if the {@code greet} method cannot be found
     */
    private static ContractEntry<?> greeterEntry(Class<?> greeterClass) throws ReflectiveOperationException {
        Method greetMethod = greeterClass.getMethod("greet", String.class);
        return ServiceContractEntries.deployable()
                .contract(greeterClass)
                .serviceInstance(new Object())
                .name("greeter")
                .operation("greet")
                .method(greetMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("name", ParamSource.PAYLOAD, String.class)
                .done()
                .build();
    }

    /**
     * Hand-builds the {@link ContractEntry} for the {@code ScGreeter} fixture, matching its source
     * declaration exactly (SC param first, payload param second).
     *
     * @param scGreeterClass the loaded {@code com.example.ScGreeter} interface
     * @return the built contract entry
     * @throws ReflectiveOperationException if the {@code greetSc} method cannot be found
     */
    private static ContractEntry<?> scGreeterEntry(Class<?> scGreeterClass) throws ReflectiveOperationException {
        Method greetScMethod = scGreeterClass.getMethod("greetSc", SecurityContext.class, String.class);
        return ServiceContractEntries.deployable()
                .contract(scGreeterClass)
                .serviceInstance(new Object())
                .name("sc-greeter")
                .operation("greetSc")
                .method(greetScMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("sc", ParamSource.DISPATCH_CONTEXT, SecurityContext.class)
                .param("name", ParamSource.PAYLOAD, String.class)
                .done()
                .build();
    }

    /**
     * Builds a minimal dummy {@link SecurityContext} carrying only a user id, mirroring the
     * precedent helper in {@code ServiceClientFactoryTest}.
     *
     * @param userId the actor user id to embed
     * @return a dummy security context
     */
    private static SecurityContext testSecurityContext(String userId) {
        SecurityIdentity identity =
                SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, java.util.Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.none(),
                java.util.List.of(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Map.of());
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return auth;
            }

            @Override
            public AuthorizationClaims authorization() {
                return AuthorizationClaims.empty();
            }

            @Override
            public java.util.Optional<RequestOrigin> origin() {
                return java.util.Optional.empty();
            }
        };
    }
}
