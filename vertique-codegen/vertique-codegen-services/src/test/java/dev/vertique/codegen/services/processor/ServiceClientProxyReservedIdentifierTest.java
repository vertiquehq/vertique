// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.services.ServiceClientFactory;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Proves the reserved-identifier escape hatch: a contract whose own identifiers collide with the
 * ones {@code ClientProxyEmitter} bakes into every generated proxy is skipped with an informational
 * {@code NOTE} and keeps working through the reflective client proxy — and that a contract method
 * merely sharing one of those method names, but not its erasure, is a legal overload that still
 * gets its generated companion.
 *
 * <p>Two identifier families are reserved. The generated dispatch body declares the method-locals
 * {@code _args_}, {@code _payload_}, {@code _overrides_}, {@code _envelope_} and {@code _dispatch_},
 * any of which a same-named contract <em>parameter</em> would shadow; and the generated class
 * declares the private static helpers {@code _payloadIndex_(ServiceMethodMeta)} and
 * {@code _securityContextIndex_(ServiceMethodMeta)}, whose names a contract <em>method</em> only
 * collides with when its erased parameter list is exactly {@code (ServiceMethodMeta)} — the
 * helper's own signature. A same-named contract method with any other erasure (e.g.
 * {@code _payloadIndex_(String)}) is a legal overload and is never skipped.
 *
 * <p>Each collision case asserts all three halves of the contract: the compilation still succeeds,
 * no companion source is written, a {@code NOTE} names both the contract and the colliding
 * identifier, and {@link ServiceClientFactory#create(Class)} returns a working JDK dynamic proxy
 * that dispatches the payload correctly.
 * {@link #reservedMethodNameLegalOverload_emitsCompanionAndDispatches()} proves the mirror-image
 * fact: no {@code NOTE}, a real companion source, and {@code create()} selecting that companion
 * rather than the dynamic-proxy fallback. The fixtures compile against the real framework classpath
 * (no stub sources) so the factory under test is the real one, exactly as
 * {@code ServiceClientProxyParityTest} does.
 */
@DisplayName("Service client proxy — reserved-identifier skip and reflective fallback")
class ServiceClientProxyReservedIdentifierTest {

    private static final String CONTRACT_FQN = "com.example.reserved.ReservedGreeter";
    private static final String COMPANION_FQN = CONTRACT_FQN + "_ServiceClientProxy";

    private static final ConfigParser CONFIG_PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    // --- Reserved parameter names ---

    @ParameterizedTest(name = "parameter named {0}")
    @ValueSource(strings = {"_args_", "_payload_", "_overrides_", "_envelope_", "_dispatch_"})
    @DisplayName("contract parameter using a reserved local name skips emission and falls back reflectively")
    void reservedParameterName_skipsEmissionAndFallsBack(String paramName) throws Exception {
        JavaFileObject contract = SourceFiles.inline(CONTRACT_FQN, """
                package com.example.reserved;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;

                @ServiceContract("reserved-greeter")
                public interface ReservedGreeter {
                    @ServiceOperation("greet")
                    Future<String> greet(String %s);
                }
                """.formatted(paramName));

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new ServiceContractProcessor(), contract);

        result.assertSuccess();
        assertNoCompanion(result, COMPANION_FQN);
        assertNoteNaming(result, CONTRACT_FQN, paramName);

        assertDispatches(result, CONTRACT_FQN, "greet", paramName, String.class, "x", false);
    }

    // --- Reserved method names: true erasure collisions ---

    @ParameterizedTest(name = "method named {0} with (ServiceMethodMeta) erasure")
    @ValueSource(strings = {"_payloadIndex_", "_securityContextIndex_"})
    @DisplayName(
            "contract method with a true (ServiceMethodMeta) erasure collision skips emission and falls back reflectively")
    void reservedMethodNameErasureCollision_skipsEmissionAndFallsBack(String methodName) throws Exception {
        JavaFileObject contract = SourceFiles.inline(CONTRACT_FQN, """
                package com.example.reserved;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import dev.vertique.services.dispatch.ServiceMethodMeta;
                import io.vertx.core.Future;

                @ServiceContract("reserved-greeter")
                public interface ReservedGreeter {
                    // Same erasure as the generated private static int %s(ServiceMethodMeta) helper
                    // — a true collision, not merely a same-named overload.
                    @ServiceOperation("greet")
                    Future<String> %s(ServiceMethodMeta meta);
                }
                """.formatted(methodName, methodName));

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new ServiceContractProcessor(), contract);

        result.assertSuccess();
        assertNoCompanion(result, COMPANION_FQN);
        assertNoteNaming(result, CONTRACT_FQN, methodName);

        assertDispatches(result, CONTRACT_FQN, methodName, "meta", ServiceMethodMeta.class, null, false);
    }

    @Test
    @DisplayName("contract method inherited from generic super-interface, substituted to (ServiceMethodMeta),"
            + " skips emission with NOTE — discriminates resolved-type erasure fix")
    void reservedMethodNameErasureCollisionThroughGenericSuperInterface_skipsEmissionWithNote() throws Exception {
        String contractFqn = "com.example.reserved.ReservedIndexHelperChild";
        String companionFqn = contractFqn + "_ServiceClientProxy";

        // Regression fixture for CG-015 round-4: the resolved-type erasure predicate.
        //
        // PURE INHERITANCE (child adds no re-declaration): the parent declares
        // _payloadIndex_(T) where T is a type variable. When the child specializes T to
        // ServiceMethodMeta through inheritance alone, the declared element type is still
        // T (erasure Object), but the RESOLVED parameter type (what extraction via
        // Types.asMemberOf(...) produces) is ServiceMethodMeta.
        //
        // The OLD predicate checked OperationModel.contractMethod().getParameters()
        // (element-declared T → erasure Object) and MISSED the collision.
        // The NEW predicate checks op.params() (already resolved by extraction) and CATCHES it.
        //
        // This pure-inheritance shape is the only discriminating fixture for the fix.
        // No re-declaration in the child means the declared signature remains generic.
        JavaFileObject parent = SourceFiles.inline("com.example.reserved.ReservedIndexHelperParent", """
                package com.example.reserved;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;

                public interface ReservedIndexHelperParent<T> {
                    @ServiceOperation("greet")
                    Future<String> _payloadIndex_(T meta);
                }
                """);

        JavaFileObject child = SourceFiles.inline(contractFqn, """
                package com.example.reserved;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.dispatch.ServiceMethodMeta;

                @ServiceContract("reserved-index-helper-child")
                public interface ReservedIndexHelperChild extends ReservedIndexHelperParent<ServiceMethodMeta> {
                    // Intentionally NO re-declaration — this is the pure-inheritance case that
                    // discriminates the resolved-type fix. The method exists only on the parent
                    // interface, with a type-variable parameter.
                }
                """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new ServiceContractProcessor(), parent, child);

        result.assertSuccess();
        assertNoCompanion(result, companionFqn);
        assertNoteNaming(result, contractFqn, "_payloadIndex_");

        // Compile-level assertions only; dispatch is already proven by other reserved-name tests.
    }

    @Test
    @DisplayName("contract method inherited from generic super-interface with re-declaration,"
            + " skips emission and falls back reflectively (dispatch sanity check)")
    void reservedMethodNameErasureCollisionThroughGenericSuperInterfaceReDeclaration_skipsEmissionAndDispatches()
            throws Exception {
        String contractFqn = "com.example.reserved.ReservedIndexHelperChildReDecl";
        String companionFqn = contractFqn + "_ServiceClientProxy";

        // Non-discriminating dispatch sanity check: re-declared method makes the specialized
        // signature accessible via reflection for runtime invocation. The collision detection
        // still works (resolved type is ServiceMethodMeta), no companion is emitted, and the
        // dynamic proxy fallback dispatches correctly. This is not a regression fixture for the
        // resolved-type fix, but rather a sanity proof that dispatch works when a reserved-name
        // collision is detected.
        JavaFileObject parent = SourceFiles.inline("com.example.reserved.ReservedIndexHelperParentReDecl", """
                package com.example.reserved;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;

                public interface ReservedIndexHelperParentReDecl<T> {
                    @ServiceOperation("greet")
                    Future<String> _payloadIndex_(T meta);
                }
                """);

        JavaFileObject child = SourceFiles.inline(contractFqn, """
                package com.example.reserved;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import dev.vertique.services.dispatch.ServiceMethodMeta;
                import io.vertx.core.Future;

                @ServiceContract("reserved-index-helper-child-redecl")
                public interface ReservedIndexHelperChildReDecl
                        extends ReservedIndexHelperParentReDecl<ServiceMethodMeta> {
                    // Re-declare the method so its specialized signature is accessible through
                    // reflection. This allows the runtime invocation in assertDispatches() to work.
                    @ServiceOperation("greet")
                    @Override
                    Future<String> _payloadIndex_(ServiceMethodMeta meta);
                }
                """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new ServiceContractProcessor(), parent, child);

        result.assertSuccess();
        assertNoCompanion(result, companionFqn);
        assertNoteNaming(result, contractFqn, "_payloadIndex_");

        assertDispatches(result, contractFqn, "_payloadIndex_", "meta", ServiceMethodMeta.class, null, false);
    }

    // --- Reserved method name: legal overload (different erasure) ---

    @Test
    @DisplayName(
            "contract method _payloadIndex_(String) is a legal overload — companion is still emitted and dispatches")
    void reservedMethodNameLegalOverload_emitsCompanionAndDispatches() throws Exception {
        JavaFileObject contract = SourceFiles.inline(CONTRACT_FQN, """
                package com.example.reserved;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;

                @ServiceContract("reserved-greeter")
                public interface ReservedGreeter {
                    // Different erasure from the generated private static int _payloadIndex_(ServiceMethodMeta)
                    // helper — a legal overload, not a collision.
                    @ServiceOperation("greet")
                    Future<String> _payloadIndex_(String name);
                }
                """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new ServiceContractProcessor(), contract);

        result.assertSuccess();
        assertCompanionGenerated(result, COMPANION_FQN);
        assertNoNoteNaming(result, CONTRACT_FQN, "_payloadIndex_");

        assertDispatches(result, CONTRACT_FQN, "_payloadIndex_", "name", String.class, "x", true);
    }

    // --- Assertions ---

    /**
     * Asserts that no {@code _ServiceClientProxy} companion source was written for the fixture.
     *
     * @param result      the successful harness result to inspect; must not be {@code null}
     * @param companionFqn the fully-qualified companion class name expected to be absent
     */
    private static void assertNoCompanion(ProcessorTestHarness.Result result, String companionFqn) {
        assertTrue(
                result.compilation().generatedSourceFile(companionFqn).isEmpty(),
                "no companion must be emitted for a contract using a reserved identifier");
    }

    /**
     * Asserts that a {@code _ServiceClientProxy} companion source was written for the fixture.
     *
     * @param result       the successful harness result to inspect; must not be {@code null}
     * @param companionFqn the fully-qualified companion class name expected to be present
     */
    private static void assertCompanionGenerated(ProcessorTestHarness.Result result, String companionFqn) {
        assertTrue(
                result.compilation().generatedSourceFile(companionFqn).isPresent(),
                "a companion must be emitted for a legal overload of a reserved method name");
    }

    /**
     * Asserts a {@link Diagnostic.Kind#NOTE} names both the contract and the colliding identifier.
     *
     * @param result     the harness result to inspect; must not be {@code null}
     * @param contractFqn the fully-qualified contract name the NOTE must name
     * @param identifier the identifier the NOTE must name
     */
    private static void assertNoteNaming(ProcessorTestHarness.Result result, String contractFqn, String identifier) {
        boolean found = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .map(d -> d.getMessage(null))
                .anyMatch(msg -> msg != null && msg.contains(contractFqn) && msg.contains(identifier));
        assertTrue(
                found,
                "expected a NOTE naming both '%s' and '%s' but none was found".formatted(contractFqn, identifier));
    }

    /**
     * Asserts that no {@link Diagnostic.Kind#NOTE} names both the contract and the given
     * identifier — the mirror image of {@link #assertNoteNaming}, used to prove a legal overload
     * produces no reserved-identifier skip note.
     *
     * @param result     the harness result to inspect; must not be {@code null}
     * @param contractFqn the fully-qualified contract name that must not be named by any
     *                    reserved-identifier NOTE alongside {@code identifier}
     * @param identifier the identifier that must not be named by any reserved-identifier NOTE
     */
    private static void assertNoNoteNaming(ProcessorTestHarness.Result result, String contractFqn, String identifier) {
        boolean found = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .map(d -> d.getMessage(null))
                .anyMatch(msg -> msg != null && msg.contains(contractFqn) && msg.contains(identifier));
        assertFalse(found, "expected no reserved-identifier NOTE naming '%s' but one was found".formatted(identifier));
    }

    /**
     * Builds the factory for the compiled fixture, invokes its single operation, and asserts the
     * dispatch outcome — shared by both the reflective-fallback scenarios (a reserved identifier
     * was used) and the companion-emission scenario (a legal overload of a reserved method name).
     *
     * <p>This is the half of the contract that makes both outcomes safe: the {@code NOTE} (or its
     * absence) would be cold comfort if the contract stopped dispatching correctly.
     *
     * @param result          the successful harness result carrying the compiled contract
     * @param contractFqn     the fully-qualified contract interface name to load
     * @param methodName      the single operation method's name on the contract
     * @param paramName       the operation's payload parameter name, as registered in the entry
     * @param paramType       the payload parameter's declared type
     * @param invocationArg   the argument passed to the operation method; may be {@code null}
     * @param expectCompanion {@code true} when {@code create()} must select the generated
     *                        companion; {@code false} when the JDK dynamic-proxy fallback is
     *                        expected
     * @throws Exception if the contract cannot be loaded, or the proxy built or invoked
     */
    private static void assertDispatches(
            ProcessorTestHarness.Result result,
            String contractFqn,
            String methodName,
            String paramName,
            Class<?> paramType,
            Object invocationArg,
            boolean expectCompanion)
            throws Exception {
        Class<?> contractClass = result.loadGeneratedClass(contractFqn);
        Method operation = contractClass.getMethod(methodName, paramType);

        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("hello, x")));

        Object client = factoryFor(entryFor(contractClass, operation, paramName, paramType), sender)
                .create(contractClass);

        if (expectCompanion) {
            assertFalse(
                    Proxy.isProxyClass(client.getClass()),
                    "create() must select the generated companion, not the JDK dynamic proxy");
        } else {
            assertTrue(
                    Proxy.isProxyClass(client.getClass()),
                    "create() must fall back to the JDK dynamic proxy when no companion was emitted");
        }

        Future<?> dispatched = (Future<?>) operation.invoke(client, invocationArg);

        ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
        verify(sender).send(any(), envelopeCaptor.capture());
        assertEquals(invocationArg, envelopeCaptor.getValue().payload(), "the proxy must extract the caller's payload");
        assertTrue(dispatched.succeeded(), "the dispatch must complete successfully");
        assertEquals("hello, x", dispatched.result(), "the proxy must unwrap the stubbed Result value");
    }

    // --- Registry / factory construction helpers ---

    /**
     * Hand-builds the single-operation {@link ContractEntry} matching the compiled fixture.
     *
     * @param contractClass the loaded contract interface
     * @param operation     the contract's single operation method
     * @param paramName     the payload parameter's name
     * @param paramType     the payload parameter's declared type
     * @return the built contract entry
     */
    private static ContractEntry<?> entryFor(
            Class<?> contractClass, Method operation, String paramName, Class<?> paramType) {
        return ServiceContractEntries.deployable()
                .contract(contractClass)
                .serviceInstance(new Object())
                .name("reserved-greeter")
                .operation("greet")
                .method(operation)
                .payloadType(paramType)
                .returnType(String.class)
                .param(paramName, ParamSource.PAYLOAD, paramType)
                .done()
                .build();
    }

    /**
     * Builds a {@link ServiceClientFactory} wired to a registry containing exactly {@code entry} and
     * the given mock sender (mirrors {@code ServiceClientProxyParityTest}'s helper).
     *
     * @param entry  the entry the factory's registry must resolve
     * @param sender the mock sender to inject
     * @return the constructed factory
     */
    private static ServiceClientFactory factoryFor(ContractEntry<?> entry, ServiceRequestSender sender) {
        ServiceContractContributor contributor = config -> List.of(entry);
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(), Set.of(contributor), new JsonObject(), CONFIG_PARSER);
        return new ServiceClientFactory(sender, registry, ServiceClientProxyRoundtripTest.newEnvelopeBuilder());
    }
}
