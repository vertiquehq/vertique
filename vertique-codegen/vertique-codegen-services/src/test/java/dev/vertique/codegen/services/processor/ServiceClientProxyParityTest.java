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
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.ContextValues;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.util.GeneratedNames;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceClientFactory;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.opentest4j.AssertionFailedError;

/**
 * CG-015 §6 S3 parity proof: {@link ServiceClientFactory#create(Class)} must behave identically
 * whether the resolved contract has a real generated {@code {Contract}_ServiceClientProxy}
 * companion on the classpath (path (a)) or not (path (b), the JDK dynamic-proxy fallback).
 *
 * <p><b>Path (a) — real generated companion.</b> {@link #WITH_COMPANION} compiles the fixtures in
 * this class through the real {@link ServiceContractProcessor}, so
 * {@link ProcessorTestHarness.Result#loadGeneratedClass(String)} loads a genuine
 * {@code {Contract}_ServiceClientProxy} class alongside each contract interface, both served by the
 * harness's child-first {@code GeneratedClassLoader}. A {@link ServiceContractRegistry} entry is
 * then hand-built for that loaded contract class (mirroring
 * {@code ServiceClientFactoryTest.CreateTimeCompletenessCheck}'s contributor-SPI idiom, the only
 * way to install a caller-controlled {@link ContractEntry} from outside {@code dev.vertique.services}),
 * and {@link ServiceClientFactory#create(Class)} is invoked for real — this is the only mechanism
 * under test in this file; nothing here bypasses the factory the way
 * {@code ServiceClientProxyRoundtripTest} deliberately does.
 *
 * <p><b>Path (b) — no companion on the classpath.</b> {@link #NO_COMPANION} compiles the exact same
 * fixture sources a second time via {@link ProcessorTestHarness#run(Iterable, JavaFileObject...)}
 * with an <em>explicit empty</em> processor list ({@code List.<Processor>of()}). Per
 * {@code compile-testing}'s documented behaviour (see the precedent javadoc on
 * {@code CronJobProcessorServiceDiscoveryTest}: {@code Compiler.javac()} runs zero processors
 * unless a processor list is explicitly supplied), this produces a plain {@code javac} compile of
 * the annotated interfaces with no annotation processing at all — no
 * {@code {Contract}_ServiceClientProxy} companion is ever written. This is deliberately stronger
 * than compiling a *different*, structurally-similar contract that merely happens to lack a
 * companion: it is the exact same source text, so every scenario below compares genuinely
 * equivalent inputs across both paths. {@link #noCompanionCompilationProducesNoCompanionClass()}
 * pins this mechanism directly, independent of the scenario matrix.
 *
 * <p><b>Scenario matrix</b> (each driven through both paths and asserted identically — resolved
 * target address, envelope payload, and envelope SecurityContext-override presence, read from the
 * mock {@link ServiceRequestSender}):
 * <ol>
 *   <li>{@link #scenario1PayloadExtraction()} — payload-only operation; also carries the
 *       <b>SELECTION ANCHOR</b>: {@code create()} must return an instance whose class name equals
 *       {@link GeneratedNames#companionFqn(Class, String)} for the companion suffix, and must
 *       <em>not</em> be a {@link Proxy#isProxyClass(Class) JDK dynamic proxy}, on path (a). This
 *       identity check is asserted exactly once (here) rather than in every scenario below, since
 *       every scenario drives the identical {@code factory.create(...)} selection call — repeating
 *       it per scenario would just re-prove the same single fact.</li>
 *   <li>{@link #scenario2ExplicitSecurityContextNoAmbient()} — explicit SC argument, no ambient SC
 *       bound: override present, keyed {@code SecurityContext.class.getName()}.</li>
 *   <li>{@link #scenario3AmbientSecurityContextSuppressesOverride()} — ambient SC bound (FR-CTX-063):
 *       no caller override, on either path.</li>
 *   <li>{@link #scenario4NullPayload()} — {@code null} payload argument.</li>
 *   <li>{@link #scenario5OneWayDispatch()} — {@code @OneWay} operation: {@code sendOneWay} invoked,
 *       {@code send} never invoked.</li>
 *   <li>{@link #scenario6ResultUnwrap()} — request-response {@link Result} unwrap value.</li>
 *   <li>{@link #scenario7CreateTimeFailFastParity()} — {@code create()}-time fail-fast on a
 *       partial entry: identical {@link IllegalStateException} message on both paths. Unlike the
 *       other scenarios, this exercises no sender interaction at all — the §4.2 step-2 completeness
 *       check throws before proxy construction is ever attempted on either path.</li>
 *   <li>{@link #scenario8SecurityContextIndexDriftParity()} — runtime metadata marks a
 *       non-{@code SecurityContext}-typed parameter position as the SC slot: both paths must throw
 *       {@link ClassCastException} from the invocation rather than one of them silently forwarding
 *       a wrong-typed caller override.</li>
 * </ol>
 *
 * <p><b>Shipped state (do not "fix" by relaxing assertions):</b> {@code ServiceClientFactory.create()}
 * selects the generated companion whenever one is present on the classpath (path (a)) and falls back
 * to a JDK dynamic proxy only when none exists (path (b)). {@link #scenario1PayloadExtraction()}'s
 * SELECTION ANCHOR pins that selection fact directly — its class-identity assertions must pass before
 * the rest of that test method's dispatch-parity assertions run. Every other scenario method
 * (2, 3, 4, 5, 6) asserts dispatch-parity facts that both paths get right identically, independent of
 * whether a companion class happens to sit on the classpath. {@link #scenario7CreateTimeFailFastParity()}
 * and {@link #noCompanionCompilationProducesNoCompanionClass()} likewise assert facts that hold
 * regardless of companion selection.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("Service client factory — companion vs dynamic-proxy dispatch parity (CG-015 §6 S3)")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceClientProxyParityTest {

    // --- Fixture sources (contract-only — no impl classes; identical text compiled twice) ---

    private static final String PARITY_GREETER_FQN = "com.example.parity.ParityGreeter";
    private static final String PARITY_SC_GREETER_FQN = "com.example.parity.ParityScGreeter";
    private static final String PARITY_ONE_WAY_GREETER_FQN = "com.example.parity.ParityOneWayGreeter";
    private static final String PARITY_PARTIAL_GREETER_FQN = "com.example.parity.ParityPartialGreeter";
    private static final String PARITY_SC_DRIFT_GREETER_FQN = "com.example.parity.ParityScDriftGreeter";

    private static final JavaFileObject PARITY_GREETER = SourceFiles.inline(PARITY_GREETER_FQN, """
            package com.example.parity;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("parity-greeter")
            public interface ParityGreeter {
                @ServiceOperation("greet")
                Future<String> greet(String name);
            }
            """);

    private static final JavaFileObject PARITY_SC_GREETER = SourceFiles.inline(PARITY_SC_GREETER_FQN, """
            package com.example.parity;
            import dev.vertique.security.SecurityContext;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("parity-sc-greeter")
            public interface ParityScGreeter {
                @ServiceOperation("greetSc")
                Future<String> greetSc(SecurityContext sc, String name);
            }
            """);

    private static final JavaFileObject PARITY_ONE_WAY_GREETER = SourceFiles.inline(PARITY_ONE_WAY_GREETER_FQN, """
            package com.example.parity;
            import dev.vertique.services.OneWay;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("parity-oneway-greeter")
            public interface ParityOneWayGreeter {
                @OneWay
                @ServiceOperation("notify")
                Future<Void> notify(String name);
            }
            """);

    /**
     * A legal contract — one {@code SecurityContext} param, one payload param — whose hand-built
     * entry deliberately swaps both roles, so the runtime {@code SecurityContext} slot lands on the
     * {@code String} parameter. That is the metadata drift scenario 8 drives through both paths.
     */
    private static final JavaFileObject PARITY_SC_DRIFT_GREETER = SourceFiles.inline(PARITY_SC_DRIFT_GREETER_FQN, """
            package com.example.parity;
            import dev.vertique.security.SecurityContext;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("parity-sc-drift-greeter")
            public interface ParityScDriftGreeter {
                @ServiceOperation("driftOp")
                Future<String> driftOp(SecurityContext sc, String name);
            }
            """);

    /** Two operations; the hand-built entry deliberately omits {@code opB} (§4.2 step 2 fail-fast). */
    private static final JavaFileObject PARITY_PARTIAL_GREETER = SourceFiles.inline(PARITY_PARTIAL_GREETER_FQN, """
            package com.example.parity;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract("parity-partial-greeter")
            public interface ParityPartialGreeter {
                @ServiceOperation("opA")
                Future<String> opA(String x);

                @ServiceOperation("opB")
                Future<String> opB(String x);
            }
            """);

    /** Path (a): compiled with the real {@link ServiceContractProcessor} — companions are emitted. */
    private static final ProcessorTestHarness.Result WITH_COMPANION = ProcessorTestHarness.run(
            new ServiceContractProcessor(),
            PARITY_GREETER,
            PARITY_SC_GREETER,
            PARITY_ONE_WAY_GREETER,
            PARITY_SC_DRIFT_GREETER,
            PARITY_PARTIAL_GREETER);

    /**
     * Path (b): compiled with an explicit empty processor list — no processor runs at all, so no
     * companion class is ever produced. See the class javadoc for why this is the chosen mechanism.
     */
    private static final ProcessorTestHarness.Result NO_COMPANION = ProcessorTestHarness.run(
            List.<Processor>of(),
            PARITY_GREETER,
            PARITY_SC_GREETER,
            PARITY_ONE_WAY_GREETER,
            PARITY_SC_DRIFT_GREETER,
            PARITY_PARTIAL_GREETER);

    private static final ConfigParser CONFIG_PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    // --- Sanity check for the path-(b) compilation mechanism ---

    @Test
    @DisplayName("Sanity — compiling with an empty processor list produces no companion class")
    void noCompanionCompilationProducesNoCompanionClass() {
        String companionFqn = PARITY_GREETER_FQN + "_ServiceClientProxy";
        assertThrows(
                AssertionFailedError.class,
                () -> NO_COMPANION.loadGeneratedClass(companionFqn),
                "an empty explicit processor list must not produce a generated companion class");
    }

    // --- Scenario 1: payload extraction + SELECTION ANCHOR (companion selection) ---

    @Test
    @DisplayName("Scenario 1 — payload extraction: create() selects the companion; both paths dispatch alike")
    void scenario1PayloadExtraction() throws Exception {
        DualPathCapture captures = greeterDualPathCapture("echo:x", "x");

        // SELECTION ANCHOR (CG-015 §6 S3): create() selects the generated companion instance — not
        // a JDK dynamic proxy — whenever one is present on the classpath. Asserted once here rather
        // than in every scenario, since every scenario drives the identical selection call.
        assertEquals(
                GeneratedNames.companionFqn(captures.contractA(), "_ServiceClientProxy"),
                captures.proxyA().getClass().getName(),
                "create() must return the generated companion instance when one is present on the classpath");
        assertFalse(
                Proxy.isProxyClass(captures.proxyA().getClass()),
                "create() must NOT fall back to a JDK dynamic proxy when a generated companion is present");
        assertTrue(
                Proxy.isProxyClass(captures.proxyB().getClass()),
                "sanity: the no-companion fixture must fall back to the JDK dynamic proxy");

        DispatchCapture capturedA = captures.capturedA();
        DispatchCapture capturedB = captures.capturedB();

        assertEquals(capturedA.address(), capturedB.address(), "both paths must dispatch to the same resolved address");
        assertEquals("x", capturedA.payload(), "path (a) must extract the caller's payload argument");
        assertEquals(capturedA.payload(), capturedB.payload(), "both paths must extract the same payload");
        assertFalse(capturedA.scOverridePresent(), "this contract has no SC param — no override on path (a)");
        assertEquals(
                capturedA.scOverridePresent(),
                capturedB.scOverridePresent(),
                "both paths must agree on SC-override presence");
    }

    // --- Scenario 2: explicit SecurityContext, no ambient SC ---

    @Test
    @DisplayName("Scenario 2 — explicit SecurityContext with no ambient SC: override present on both paths")
    void scenario2ExplicitSecurityContextNoAmbient() throws Exception {
        SecurityContext explicitSc = ServiceClientProxyRoundtripTest.testSecurityContext("explicit-user");

        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_SC_GREETER_FQN);
        ContractEntry<?> entryA = parityScGreeterEntry(contractA);
        ServiceRequestSender senderA = mock(ServiceRequestSender.class);
        when(senderA.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));
        Object proxyA = factoryFor(entryA, senderA).create(contractA);
        Method greetScMethodA = contractA.getMethod("greetSc", SecurityContext.class, String.class);
        DispatchCapture capturedA =
                invokeRequestResponse(proxyA, greetScMethodA, new Object[] {explicitSc, "y"}, senderA);

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_SC_GREETER_FQN);
        ContractEntry<?> entryB = parityScGreeterEntry(contractB);
        ServiceRequestSender senderB = mock(ServiceRequestSender.class);
        when(senderB.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));
        Object proxyB = factoryFor(entryB, senderB).create(contractB);
        Method greetScMethodB = contractB.getMethod("greetSc", SecurityContext.class, String.class);
        DispatchCapture capturedB =
                invokeRequestResponse(proxyB, greetScMethodB, new Object[] {explicitSc, "y"}, senderB);

        assertTrue(capturedA.scOverridePresent(), "path (a) must carry the explicit SC as a caller override");
        assertTrue(capturedB.scOverridePresent(), "path (b) must carry the explicit SC as a caller override");
        assertSame(explicitSc, capturedA.scOverride(), "path (a) override value must be the caller's explicit SC");
        assertSame(explicitSc, capturedB.scOverride(), "path (b) override value must be the caller's explicit SC");
        assertEquals(capturedA.address(), capturedB.address(), "both paths must dispatch to the same resolved address");
        assertEquals("y", capturedA.payload());
        assertEquals(capturedA.payload(), capturedB.payload(), "both paths must extract the same payload");
    }

    // --- Scenario 3: ambient SecurityContext bound (no override) ---

    /**
     * Reuses {@code ServiceClientProxyRoundtripTest}'s ambient-binding pattern: a duplicated Vert.x
     * context is required because {@code ContextValues.bind}'s write-side guard rejects binding
     * outside a running context.
     *
     * @param vertx the Vert.x instance
     * @param ctx   the test context
     * @throws Throwable if the test times out or an assertion inside the bound context fails
     */
    @Test
    @DisplayName("Scenario 3 — ambient SecurityContext bound: no SC override on either path (FR-CTX-063)")
    void scenario3AmbientSecurityContextSuppressesOverride(Vertx vertx, VertxTestContext ctx) throws Throwable {
        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_SC_GREETER_FQN);
        ContractEntry<?> entryA = parityScGreeterEntry(contractA);
        ServiceRequestSender senderA = mock(ServiceRequestSender.class);
        when(senderA.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));
        Object proxyA = factoryFor(entryA, senderA).create(contractA);
        Method greetScMethodA = contractA.getMethod("greetSc", SecurityContext.class, String.class);

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_SC_GREETER_FQN);
        ContractEntry<?> entryB = parityScGreeterEntry(contractB);
        ServiceRequestSender senderB = mock(ServiceRequestSender.class);
        when(senderB.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));
        Object proxyB = factoryFor(entryB, senderB).create(contractB);
        Method greetScMethodB = contractB.getMethod("greetSc", SecurityContext.class, String.class);

        SecurityContext ambientSc = ServiceClientProxyRoundtripTest.testSecurityContext("ambient-user");
        SecurityContext explicitSc = ServiceClientProxyRoundtripTest.testSecurityContext("explicit-user");

        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(v -> {
            try (ContextHolder.Scope scope = ContextValues.bind(SecurityContext.class, ambientSc)) {
                DispatchCapture capturedA =
                        invokeRequestResponse(proxyA, greetScMethodA, new Object[] {explicitSc, "y"}, senderA);
                DispatchCapture capturedB =
                        invokeRequestResponse(proxyB, greetScMethodB, new Object[] {explicitSc, "y"}, senderB);

                ctx.verify(() -> {
                    assertFalse(
                            capturedA.scOverridePresent(),
                            "path (a) must carry no SC override when an ambient SC is bound");
                    assertFalse(
                            capturedB.scOverridePresent(),
                            "path (b) must carry no SC override when an ambient SC is bound");
                });
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

    // --- Scenario 4: null payload ---

    @Test
    @DisplayName("Scenario 4 — null payload: envelope payload is null on both paths and dispatch does not throw")
    void scenario4NullPayload() throws Exception {
        DualPathCapture captures = greeterDualPathCapture("ok", null);
        DispatchCapture capturedA = captures.capturedA();
        DispatchCapture capturedB = captures.capturedB();

        assertNull(capturedA.payload(), "path (a) envelope payload must be null when the caller passes null");
        assertNull(capturedB.payload(), "path (b) envelope payload must be null when the caller passes null");
        assertNotNull(capturedA.resultValue(), "invocation with a null payload argument must not throw on path (a)");
        assertNotNull(capturedB.resultValue(), "invocation with a null payload argument must not throw on path (b)");
    }

    // --- Scenario 5: one-way dispatch ---

    @Test
    @DisplayName("Scenario 5 — oneWay: sendOneWay invoked and send never invoked, on both paths")
    void scenario5OneWayDispatch() throws Exception {
        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_ONE_WAY_GREETER_FQN);
        ContractEntry<?> entryA = parityOneWayGreeterEntry(contractA);
        ServiceRequestSender senderA = mock(ServiceRequestSender.class);
        when(senderA.sendOneWay(any(), any())).thenReturn(Future.succeededFuture());
        Object proxyA = factoryFor(entryA, senderA).create(contractA);
        Method notifyMethodA = contractA.getMethod("notify", String.class);

        Future<?> resultA = (Future<?>) notifyMethodA.invoke(proxyA, "ping");
        verify(senderA).sendOneWay(any(), any());
        verify(senderA, never()).send(any(), any());
        assertTrue(resultA.succeeded(), "one-way dispatch must return a succeeded Future<Void> on path (a)");

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_ONE_WAY_GREETER_FQN);
        ContractEntry<?> entryB = parityOneWayGreeterEntry(contractB);
        ServiceRequestSender senderB = mock(ServiceRequestSender.class);
        when(senderB.sendOneWay(any(), any())).thenReturn(Future.succeededFuture());
        Object proxyB = factoryFor(entryB, senderB).create(contractB);
        Method notifyMethodB = contractB.getMethod("notify", String.class);

        Future<?> resultB = (Future<?>) notifyMethodB.invoke(proxyB, "ping");
        verify(senderB).sendOneWay(any(), any());
        verify(senderB, never()).send(any(), any());
        assertTrue(resultB.succeeded(), "one-way dispatch must return a succeeded Future<Void> on path (b)");
    }

    // --- Scenario 6: request-response Result unwrap ---

    @Test
    @DisplayName("Scenario 6 — request-response Result unwrap: both paths unwrap the same stubbed success value")
    void scenario6ResultUnwrap() throws Exception {
        DualPathCapture captures = greeterDualPathCapture("hello, x", "x");
        DispatchCapture capturedA = captures.capturedA();
        DispatchCapture capturedB = captures.capturedB();

        assertEquals("hello, x", capturedA.resultValue(), "path (a) must unwrap the stubbed Result value");
        assertEquals("hello, x", capturedB.resultValue(), "path (b) must unwrap the stubbed Result value");
        assertEquals(capturedA.resultValue(), capturedB.resultValue(), "both paths must unwrap the same value");
    }

    // --- Scenario 7: create()-time fail-fast parity ---

    @Test
    @DisplayName("Scenario 7 — create()-time fail-fast parity: identical IllegalStateException on both paths")
    void scenario7CreateTimeFailFastParity() throws Exception {
        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_PARTIAL_GREETER_FQN);
        ContractEntry<?> entryA = parityPartialEntry(contractA);
        ServiceClientFactory factoryA = factoryFor(entryA, mock(ServiceRequestSender.class));

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_PARTIAL_GREETER_FQN);
        ContractEntry<?> entryB = parityPartialEntry(contractB);
        ServiceClientFactory factoryB = factoryFor(entryB, mock(ServiceRequestSender.class));

        IllegalStateException exA = assertThrows(IllegalStateException.class, () -> factoryA.create(contractA));
        IllegalStateException exB = assertThrows(IllegalStateException.class, () -> factoryB.create(contractB));

        assertTrue(
                exA.getMessage().startsWith("Service client contract mismatch: "),
                "path (a) message must start with the §4.2-pinned prefix, was: " + exA.getMessage());
        assertTrue(
                exB.getMessage().startsWith("Service client contract mismatch: "),
                "path (b) message must start with the §4.2-pinned prefix, was: " + exB.getMessage());
        assertTrue(exA.getMessage().contains(contractA.getName()), "path (a) message must name the contract FQCN");
        assertTrue(exB.getMessage().contains(contractB.getName()), "path (b) message must name the contract FQCN");
        assertTrue(exA.getMessage().contains("opB"), "path (a) message must name the missing operation 'opB'");
        assertTrue(exB.getMessage().contains("opB"), "path (b) message must name the missing operation 'opB'");
        assertEquals(exA.getMessage(), exB.getMessage(), "the mismatch message shape must be identical on both paths");
    }

    // --- Scenario 8: SecurityContext-index drift ---

    /**
     * The contradiction pattern of {@code ServiceClientProxyRoundtripTest}'s D1 tests, applied to the
     * {@code SecurityContext} slot and driven through <em>both</em> paths: the runtime metadata marks
     * parameter position 1 — a plain {@code String} in the source — as the
     * {@code SecurityContext}-keyed dispatch-context param.
     *
     * <p>The reflective path casts that argument ({@code (SecurityContext) args[i]}) and therefore
     * throws {@link ClassCastException}. Parity demands the companion do exactly the same rather than
     * silently forwarding a wrong-typed override, so both paths must fail identically here — the
     * entry is deliberately wrong; do not "correct" it to match the source.
     *
     * @throws Exception if either path's proxy cannot be built
     */
    @Test
    @DisplayName("Scenario 8 — SecurityContext-index drift: both paths throw ClassCastException on invocation")
    void scenario8SecurityContextIndexDriftParity() throws Exception {
        DualPathProxies proxies = scDriftDualPathProxies();
        SecurityContext payloadByDrift = ServiceClientProxyRoundtripTest.testSecurityContext("payload-by-drift");

        InvocationTargetException thrownA = assertThrows(
                InvocationTargetException.class,
                () -> proxies.methodA().invoke(proxies.proxyA(), payloadByDrift, "not-a-security-context"),
                "path (a) must not silently accept a wrong-typed SecurityContext override");
        InvocationTargetException thrownB = assertThrows(
                InvocationTargetException.class,
                () -> proxies.methodB().invoke(proxies.proxyB(), payloadByDrift, "not-a-security-context"),
                "path (b) must reject a wrong-typed SecurityContext override");

        assertInstanceOf(ClassCastException.class, thrownA.getCause(), "path (a) must fail with a ClassCastException");
        assertInstanceOf(ClassCastException.class, thrownB.getCause(), "path (b) must fail with a ClassCastException");
    }

    // --- Entry-building helpers (one hand-built ContractEntry per fixture contract) ---

    /**
     * Builds a single-operation entry matching {@link #PARITY_GREETER}'s declaration exactly (one
     * {@code PAYLOAD} param).
     *
     * @param contractClass the loaded {@code ParityGreeter} interface (either compilation)
     * @return the built contract entry
     * @throws ReflectiveOperationException if {@code greet} cannot be found on {@code contractClass}
     */
    private static ContractEntry<?> parityGreeterEntry(Class<?> contractClass) throws ReflectiveOperationException {
        Method greetMethod = contractClass.getMethod("greet", String.class);
        return ServiceContractEntries.deployable()
                .contract(contractClass)
                .serviceInstance(new Object())
                .name("parity-greeter")
                .operation("greet")
                .method(greetMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("name", ParamSource.PAYLOAD, String.class)
                .done()
                .build();
    }

    /**
     * Builds a single-operation entry matching {@link #PARITY_SC_GREETER}'s declaration exactly (SC
     * param first, payload param second).
     *
     * @param contractClass the loaded {@code ParityScGreeter} interface (either compilation)
     * @return the built contract entry
     * @throws ReflectiveOperationException if {@code greetSc} cannot be found on {@code contractClass}
     */
    private static ContractEntry<?> parityScGreeterEntry(Class<?> contractClass) throws ReflectiveOperationException {
        Method greetScMethod = contractClass.getMethod("greetSc", SecurityContext.class, String.class);
        return ServiceContractEntries.deployable()
                .contract(contractClass)
                .serviceInstance(new Object())
                .name("parity-sc-greeter")
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
     * Builds a one-way single-operation entry matching {@link #PARITY_ONE_WAY_GREETER}'s
     * declaration exactly.
     *
     * @param contractClass the loaded {@code ParityOneWayGreeter} interface (either compilation)
     * @return the built contract entry
     * @throws ReflectiveOperationException if {@code notify} cannot be found on {@code contractClass}
     */
    private static ContractEntry<?> parityOneWayGreeterEntry(Class<?> contractClass)
            throws ReflectiveOperationException {
        Method notifyMethod = contractClass.getMethod("notify", String.class);
        return ServiceContractEntries.deployable()
                .contract(contractClass)
                .serviceInstance(new Object())
                .name("parity-oneway-greeter")
                .operation("notify")
                .method(notifyMethod)
                .payloadType(String.class)
                .returnType(Void.class)
                .param("name", ParamSource.PAYLOAD, String.class)
                .oneWay()
                .done()
                .build();
    }

    /**
     * Builds a deliberately drifted entry for {@link #PARITY_SC_DRIFT_GREETER}, the mirror image of
     * its source declaration: parameter position 0 (the {@code SecurityContext}) is registered as the
     * payload and position 1 (a {@code String}) as the {@code SecurityContext}-keyed dispatch-context
     * param. Both dispatch paths must reject the wrong-typed slot; do not "correct" this entry.
     *
     * @param contractClass the loaded {@code ParityScDriftGreeter} interface (either compilation)
     * @return the built contract entry, with a wrong-typed SC slot
     * @throws ReflectiveOperationException if {@code driftOp} cannot be found on {@code contractClass}
     */
    private static ContractEntry<?> parityScDriftEntry(Class<?> contractClass) throws ReflectiveOperationException {
        Method driftOpMethod = contractClass.getMethod("driftOp", SecurityContext.class, String.class);
        return ServiceContractEntries.deployable()
                .contract(contractClass)
                .serviceInstance(new Object())
                .name("parity-sc-drift-greeter")
                .operation("driftOp")
                .method(driftOpMethod)
                .payloadType(SecurityContext.class)
                .returnType(String.class)
                .param("sc", ParamSource.PAYLOAD, SecurityContext.class)
                .param("name", ParamSource.DISPATCH_CONTEXT, SecurityContext.class)
                .done()
                .build();
    }

    /**
     * Builds a deliberately partial entry for {@link #PARITY_PARTIAL_GREETER}: only {@code opA} is
     * registered, {@code opB} is omitted so §4.2 step 2 fails {@code create()} fast.
     *
     * @param contractClass the loaded {@code ParityPartialGreeter} interface (either compilation)
     * @return the built contract entry, missing operation {@code opB}
     * @throws ReflectiveOperationException if {@code opA} cannot be found on {@code contractClass}
     */
    private static ContractEntry<?> parityPartialEntry(Class<?> contractClass) throws ReflectiveOperationException {
        Method opAMethod = contractClass.getMethod("opA", String.class);
        return ServiceContractEntries.deployable()
                .contract(contractClass)
                .serviceInstance(new Object())
                .name("parity-partial-greeter")
                .operation("opA")
                .method(opAMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("x", ParamSource.PAYLOAD, String.class)
                .done()
                .build();
    }

    // --- Registry / factory construction helpers ---

    /**
     * Registers {@code entry} into a fresh registry via the {@link ServiceContractContributor} SPI —
     * the only way to install a caller-controlled {@link ContractEntry} from outside
     * {@code dev.vertique.services} (mirrors {@code ServiceClientFactoryTest.CreateTimeCompletenessCheck}).
     *
     * @param entry the pre-built contract entry to register
     * @return a registry containing only {@code entry}
     */
    private static ServiceContractRegistry registryOf(ContractEntry<?> entry) {
        ServiceContractContributor contributor = config -> List.of(entry);
        return ServiceContractRegistry.build(Set.of(), Set.of(contributor), new JsonObject(), CONFIG_PARSER);
    }

    /**
     * Builds a {@link ServiceClientFactory} wired to a registry containing exactly {@code entry},
     * the given mock sender, and an envelope builder backed by empty dispatch-context registries.
     *
     * @param entry  the entry the factory's registry must resolve {@code entry.contract()} to
     * @param sender the (mock) sender to inject
     * @return the constructed factory
     */
    private static ServiceClientFactory factoryFor(ContractEntry<?> entry, ServiceRequestSender sender) {
        return new ServiceClientFactory(
                sender, registryOf(entry), ServiceClientProxyRoundtripTest.newEnvelopeBuilder());
    }

    // --- Dispatch capture helper ---

    /**
     * The observable outcome of one request-response dispatch, captured from the mock
     * {@link ServiceRequestSender}.
     *
     * @param address        the resolved event bus address the dispatch targeted
     * @param payload        the envelope payload the caller's argument was extracted into
     * @param scOverride     the caller-override {@link SecurityContext} keyed
     *                       {@code SecurityContext.class.getName()} in the envelope's dispatch
     *                       context, or {@code null} when no override was present
     * @param resultValue    the value the returned {@link Future} completed with, after unwrapping
     *                       the stubbed {@link Result}
     */
    private record DispatchCapture(String address, Object payload, Object scOverride, Object resultValue) {

        /**
         * Returns whether a SecurityContext caller-override was present in the captured envelope.
         *
         * @return {@code true} if {@link #scOverride()} is non-{@code null}
         */
        boolean scOverridePresent() {
            return scOverride != null;
        }
    }

    /**
     * Invokes a request-response contract method on {@code proxy}, captures the
     * {@link ServiceRequestSender#send} arguments, and asserts the returned future succeeded.
     *
     * @param proxy  the proxy instance (companion or dynamic proxy) to invoke the method on
     * @param method the contract method to invoke
     * @param args   the arguments to pass, in declaration order
     * @param sender the mock sender the proxy was built with
     * @return the captured dispatch outcome
     * @throws Exception if the method cannot be invoked reflectively
     */
    private static DispatchCapture invokeRequestResponse(
            Object proxy, Method method, Object[] args, ServiceRequestSender sender) throws Exception {
        Future<?> future = (Future<?>) method.invoke(proxy, args);
        ArgumentCaptor<ResolvedServiceTarget> targetCaptor = forClass(ResolvedServiceTarget.class);
        ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
        verify(sender).send(targetCaptor.capture(), envelopeCaptor.capture());
        Object scOverride =
                envelopeCaptor.getValue().metadata().dispatchContext().get(SecurityContext.class.getName());
        assertTrue(future.succeeded(), "dispatch must complete successfully");
        return new DispatchCapture(
                targetCaptor.getValue().address(), envelopeCaptor.getValue().payload(), scOverride, future.result());
    }

    // --- Dual-path capture helper for scenarios 1, 4, 6 ---

    /**
     * Both paths' constructed proxy instances and captured dispatch outcomes from
     * {@link #greeterDualPathCapture(String, Object)}.
     *
     * @param contractA the {@link #PARITY_GREETER} interface as loaded from the companion-bearing
     *                  compilation ({@link #WITH_COMPANION})
     * @param proxyA    path (a)'s constructed proxy instance (companion or dynamic proxy)
     * @param capturedA path (a)'s captured dispatch outcome
     * @param proxyB    path (b)'s constructed proxy instance (always a dynamic proxy)
     * @param capturedB path (b)'s captured dispatch outcome
     */
    private record DualPathCapture(
            Class<?> contractA, Object proxyA, DispatchCapture capturedA, Object proxyB, DispatchCapture capturedB) {}

    /**
     * Both paths' constructed proxy instances and the contract method to invoke on each, for
     * scenarios whose invocation is expected to throw and therefore cannot be captured.
     *
     * @param proxyA  path (a)'s constructed proxy instance (the generated companion)
     * @param methodA path (a)'s contract method, resolved on path (a)'s contract class
     * @param proxyB  path (b)'s constructed proxy instance (the JDK dynamic proxy)
     * @param methodB path (b)'s contract method, resolved on path (b)'s contract class
     */
    private record DualPathProxies(Object proxyA, Method methodA, Object proxyB, Method methodB) {}

    /**
     * Builds both dispatch paths for the {@link #PARITY_SC_DRIFT_GREETER} fixture against the drifted
     * entry from {@link #parityScDriftEntry(Class)}.
     *
     * <p>The senders are bare mocks: dispatch never reaches them, because both paths fail while
     * assembling the caller overrides.
     *
     * @return both paths' proxies and their {@code driftOp} methods
     * @throws Exception if either path's proxy cannot be built
     */
    private static DualPathProxies scDriftDualPathProxies() throws Exception {
        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_SC_DRIFT_GREETER_FQN);
        Object proxyA = factoryFor(parityScDriftEntry(contractA), mock(ServiceRequestSender.class))
                .create(contractA);

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_SC_DRIFT_GREETER_FQN);
        Object proxyB = factoryFor(parityScDriftEntry(contractB), mock(ServiceRequestSender.class))
                .create(contractB);

        return new DualPathProxies(
                proxyA,
                contractA.getMethod("driftOp", SecurityContext.class, String.class),
                proxyB,
                contractB.getMethod("driftOp", SecurityContext.class, String.class));
    }

    /**
     * Builds both dispatch paths (companion and dynamic-proxy) for the {@link #PARITY_GREETER}
     * fixture, invokes {@code greet} on each with the given stub value and invocation argument, and
     * captures both outcomes.
     *
     * <p>Shared by scenarios 1, 4, and 6, which repeat this exact build-invoke-capture shape and vary
     * only the stubbed success value and the invocation argument. Per-scenario assertions — including
     * scenario 1's SELECTION-ANCHOR class-identity checks — stay in the scenario methods; this helper only
     * builds, invokes, and captures.
     *
     * @param stubValue     the value the mock sender's stubbed {@link Result#success} completes with
     * @param invocationArg the single argument passed to {@code greet} (may be {@code null})
     * @return both paths' constructed proxy instances and captured dispatch outcomes
     * @throws Exception if either path's proxy cannot be built or invoked
     */
    private static DualPathCapture greeterDualPathCapture(String stubValue, Object invocationArg) throws Exception {
        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_GREETER_FQN);
        ContractEntry<?> entryA = parityGreeterEntry(contractA);
        ServiceRequestSender senderA = mock(ServiceRequestSender.class);
        when(senderA.send(any(), any())).thenReturn(Future.succeededFuture(Result.success(stubValue)));
        Object proxyA = factoryFor(entryA, senderA).create(contractA);
        Method greetMethodA = contractA.getMethod("greet", String.class);
        DispatchCapture capturedA = invokeRequestResponse(proxyA, greetMethodA, new Object[] {invocationArg}, senderA);

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_GREETER_FQN);
        ContractEntry<?> entryB = parityGreeterEntry(contractB);
        ServiceRequestSender senderB = mock(ServiceRequestSender.class);
        when(senderB.send(any(), any())).thenReturn(Future.succeededFuture(Result.success(stubValue)));
        Object proxyB = factoryFor(entryB, senderB).create(contractB);
        Method greetMethodB = contractB.getMethod("greet", String.class);
        DispatchCapture capturedB = invokeRequestResponse(proxyB, greetMethodB, new Object[] {invocationArg}, senderB);

        return new DualPathCapture(contractA, proxyA, capturedA, proxyB, capturedB);
    }
}
