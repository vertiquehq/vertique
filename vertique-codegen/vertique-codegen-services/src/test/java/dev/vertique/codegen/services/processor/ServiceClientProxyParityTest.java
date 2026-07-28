// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.ServiceDispatchContextCapturer;
import dev.vertique.context.ServiceDispatchContextRegistry;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.util.GeneratedNames;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
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
 *       <b>RED ANCHOR</b>: {@code create()} must return an instance whose class name equals
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
 * </ol>
 *
 * <p><b>Expected state before CG-015 §6 S3 lands (current state — do not "fix" by relaxing
 * assertions):</b> {@code ServiceClientFactory.create()} unconditionally builds a JDK dynamic proxy
 * (no companion-selection seam exists yet). {@link #scenario1PayloadExtraction()} therefore fails at
 * the RED ANCHOR described above — the returned instance is a dynamic proxy, not the companion — and
 * the rest of that test method's body never runs once that assertion fails. Every other scenario
 * method (2, 3, 4, 5, 6) asserts only dispatch-parity facts that the existing reflective dispatch
 * already gets right regardless of whether an unused companion class happens to sit on the
 * classpath, so those pass unmodified today. {@link #scenario7CreateTimeFailFastParity()} and
 * {@link #noCompanionCompilationProducesNoCompanionClass()} pass unmodified today and after S3,
 * since neither touches companion selection at all. Once S3 lands, {@code create()} selects the
 * companion whenever one is present, the RED ANCHOR flips green, and scenario 1's dispatch-parity
 * assertions run and pass for the first time.
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
            PARITY_PARTIAL_GREETER);

    /**
     * Path (b): compiled with an explicit empty processor list — no processor runs at all, so no
     * companion class is ever produced. See the class javadoc for why this is the chosen mechanism.
     */
    private static final ProcessorTestHarness.Result NO_COMPANION = ProcessorTestHarness.run(
            List.<Processor>of(), PARITY_GREETER, PARITY_SC_GREETER, PARITY_ONE_WAY_GREETER, PARITY_PARTIAL_GREETER);

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

    // --- Scenario 1: payload extraction + RED ANCHOR (companion selection) ---

    @Test
    @DisplayName("Scenario 1 — payload extraction: create() selects the companion (RED); both paths dispatch alike")
    void scenario1PayloadExtraction() throws Exception {
        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_GREETER_FQN);
        ContractEntry<?> entryA = parityGreeterEntry(contractA);
        ServiceRequestSender senderA = mock(ServiceRequestSender.class);
        when(senderA.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("echo:x")));
        Object proxyA = factoryFor(entryA, senderA).create(contractA);

        // RED ANCHOR (CG-015 §6 S3): create() must select the generated companion instance — not a
        // JDK dynamic proxy — whenever one is present on the classpath. Fails today because the
        // factory has no companion-selection seam yet and always returns a dynamic proxy.
        assertEquals(
                GeneratedNames.companionFqn(contractA, "_ServiceClientProxy"),
                proxyA.getClass().getName(),
                "create() must return the generated companion instance when one is present on the classpath");
        assertFalse(
                Proxy.isProxyClass(proxyA.getClass()),
                "create() must NOT fall back to a JDK dynamic proxy when a generated companion is present");

        Method greetMethodA = contractA.getMethod("greet", String.class);
        DispatchCapture capturedA = invokeRequestResponse(proxyA, greetMethodA, new Object[] {"x"}, senderA);

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_GREETER_FQN);
        ContractEntry<?> entryB = parityGreeterEntry(contractB);
        ServiceRequestSender senderB = mock(ServiceRequestSender.class);
        when(senderB.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("echo:x")));
        Object proxyB = factoryFor(entryB, senderB).create(contractB);
        assertTrue(
                Proxy.isProxyClass(proxyB.getClass()),
                "sanity: the no-companion fixture must fall back to the JDK dynamic proxy");

        Method greetMethodB = contractB.getMethod("greet", String.class);
        DispatchCapture capturedB = invokeRequestResponse(proxyB, greetMethodB, new Object[] {"x"}, senderB);

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
        SecurityContext explicitSc = testSecurityContext("explicit-user");

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

        SecurityContext ambientSc = testSecurityContext("ambient-user");
        SecurityContext explicitSc = testSecurityContext("explicit-user");

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
        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_GREETER_FQN);
        ContractEntry<?> entryA = parityGreeterEntry(contractA);
        ServiceRequestSender senderA = mock(ServiceRequestSender.class);
        when(senderA.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));
        Object proxyA = factoryFor(entryA, senderA).create(contractA);
        Method greetMethodA = contractA.getMethod("greet", String.class);
        DispatchCapture capturedA = invokeRequestResponse(proxyA, greetMethodA, new Object[] {null}, senderA);

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_GREETER_FQN);
        ContractEntry<?> entryB = parityGreeterEntry(contractB);
        ServiceRequestSender senderB = mock(ServiceRequestSender.class);
        when(senderB.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("ok")));
        Object proxyB = factoryFor(entryB, senderB).create(contractB);
        Method greetMethodB = contractB.getMethod("greet", String.class);
        DispatchCapture capturedB = invokeRequestResponse(proxyB, greetMethodB, new Object[] {null}, senderB);

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
        Class<?> contractA = WITH_COMPANION.loadGeneratedClass(PARITY_GREETER_FQN);
        ContractEntry<?> entryA = parityGreeterEntry(contractA);
        ServiceRequestSender senderA = mock(ServiceRequestSender.class);
        when(senderA.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("hello, x")));
        Object proxyA = factoryFor(entryA, senderA).create(contractA);
        Method greetMethodA = contractA.getMethod("greet", String.class);
        DispatchCapture capturedA = invokeRequestResponse(proxyA, greetMethodA, new Object[] {"x"}, senderA);

        Class<?> contractB = NO_COMPANION.loadGeneratedClass(PARITY_GREETER_FQN);
        ContractEntry<?> entryB = parityGreeterEntry(contractB);
        ServiceRequestSender senderB = mock(ServiceRequestSender.class);
        when(senderB.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("hello, x")));
        Object proxyB = factoryFor(entryB, senderB).create(contractB);
        Method greetMethodB = contractB.getMethod("greet", String.class);
        DispatchCapture capturedB = invokeRequestResponse(proxyB, greetMethodB, new Object[] {"x"}, senderB);

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
        return new ServiceClientFactory(sender, registryOf(entry), envelopeBuilder());
    }

    /**
     * Builds a {@link DispatchEnvelopeBuilder} backed by empty SPI registries — no encoders run, so
     * the built envelope's dispatch context is exactly the caller-supplied overrides.
     *
     * @return a fresh envelope builder with no registered encoders/decoders
     */
    private static DispatchEnvelopeBuilder envelopeBuilder() {
        return new DispatchEnvelopeBuilder(new ServiceDispatchContextCapturer(
                new ServiceDispatchContextRegistry(Set.of(), Set.of()), new DefaultContextHolder()));
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

    // --- SecurityContext test fixture ---

    /**
     * Builds a minimal dummy {@link SecurityContext} carrying only a user id, mirroring the
     * precedent helper in {@code ServiceClientProxyRoundtripTest} / {@code ServiceClientFactoryTest}.
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
