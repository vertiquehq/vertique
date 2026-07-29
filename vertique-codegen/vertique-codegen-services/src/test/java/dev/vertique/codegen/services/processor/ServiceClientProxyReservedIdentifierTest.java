// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code NOTE} and keeps working through the reflective client proxy.
 *
 * <p>Two identifier families are reserved. The generated dispatch body declares the method-locals
 * {@code _args_}, {@code _payload_}, {@code _overrides_}, {@code _envelope_} and {@code _dispatch_},
 * any of which a same-named contract <em>parameter</em> would shadow; and the generated class
 * declares the private static helpers {@code _payloadIndex_} and {@code _securityContextIndex_},
 * whose names a contract <em>method</em> may reuse (a legal overload — the reservation deliberately
 * over-approximates, because the cost of a false positive is only the reflective fallback proven
 * here).
 *
 * <p>Each case asserts all three halves of the contract: the compilation still succeeds, no
 * companion source is written, a {@code NOTE} names both the contract and the colliding identifier,
 * and {@link ServiceClientFactory#create(Class)} returns a working JDK dynamic proxy that dispatches
 * the payload correctly. The fixtures compile against the real framework classpath (no stub sources)
 * so the factory under test is the real one, exactly as {@code ServiceClientProxyParityTest} does.
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
        assertNoCompanion(result);
        assertNoteNaming(result, paramName);

        assertReflectiveFallbackDispatches(result, "greet", paramName);
    }

    // --- Reserved method name ---

    @Test
    @DisplayName("contract method named _payloadIndex_ skips emission and falls back reflectively")
    void reservedMethodName_skipsEmissionAndFallsBack() throws Exception {
        JavaFileObject contract = SourceFiles.inline(CONTRACT_FQN, """
                package com.example.reserved;
                import dev.vertique.services.ServiceContract;
                import dev.vertique.services.ServiceOperation;
                import io.vertx.core.Future;

                @ServiceContract("reserved-greeter")
                public interface ReservedGreeter {
                    // Same name as the generated private static int _payloadIndex_(ServiceMethodMeta)
                    // helper — a legal overload, but reserved all the same.
                    @ServiceOperation("greet")
                    Future<String> _payloadIndex_(String name);
                }
                """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new ServiceContractProcessor(), contract);

        result.assertSuccess();
        assertNoCompanion(result);
        assertNoteNaming(result, "_payloadIndex_");

        assertReflectiveFallbackDispatches(result, "_payloadIndex_", "name");
    }

    // --- Assertions ---

    /**
     * Asserts that no {@code _ServiceClientProxy} companion source was written for the fixture.
     *
     * @param result the successful harness result to inspect; must not be {@code null}
     */
    private static void assertNoCompanion(ProcessorTestHarness.Result result) {
        assertTrue(
                result.compilation().generatedSourceFile(COMPANION_FQN).isEmpty(),
                "no companion must be emitted for a contract using a reserved identifier");
    }

    /**
     * Asserts a {@link Diagnostic.Kind#NOTE} names both the contract and the colliding identifier.
     *
     * @param result     the harness result to inspect; must not be {@code null}
     * @param identifier the identifier the NOTE must name
     */
    private static void assertNoteNaming(ProcessorTestHarness.Result result, String identifier) {
        boolean found = result.compilation().diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .map(d -> d.getMessage(null))
                .anyMatch(msg -> msg != null && msg.contains(CONTRACT_FQN) && msg.contains(identifier));
        assertTrue(
                found,
                "expected a NOTE naming both '%s' and '%s' but none was found".formatted(CONTRACT_FQN, identifier));
    }

    /**
     * Asserts that {@link ServiceClientFactory#create(Class)} falls back to a JDK dynamic proxy for
     * the compiled fixture and that the proxy dispatches the caller's payload correctly.
     *
     * <p>This is the half of the contract that makes the skip safe: the NOTE would be cold comfort
     * if the contract stopped working.
     *
     * @param result     the successful harness result carrying the compiled contract
     * @param methodName the single operation method's name on the contract
     * @param paramName  the operation's payload parameter name, as registered in the entry
     * @throws Exception if the contract cannot be loaded, or the proxy built or invoked
     */
    private static void assertReflectiveFallbackDispatches(
            ProcessorTestHarness.Result result, String methodName, String paramName) throws Exception {
        Class<?> contractClass = result.loadGeneratedClass(CONTRACT_FQN);
        Method operation = contractClass.getMethod(methodName, String.class);

        ServiceRequestSender sender = mock(ServiceRequestSender.class);
        when(sender.send(any(), any())).thenReturn(Future.succeededFuture(Result.success("hello, x")));

        Object proxy = factoryFor(entryFor(contractClass, operation, paramName), sender)
                .create(contractClass);

        assertTrue(
                Proxy.isProxyClass(proxy.getClass()),
                "create() must fall back to the JDK dynamic proxy when no companion was emitted");

        Future<?> dispatched = (Future<?>) operation.invoke(proxy, "x");

        ArgumentCaptor<DispatchEnvelope<?>> envelopeCaptor = forClass(DispatchEnvelope.class);
        verify(sender).send(any(), envelopeCaptor.capture());
        assertEquals("x", envelopeCaptor.getValue().payload(), "the fallback proxy must extract the caller's payload");
        assertTrue(dispatched.succeeded(), "the fallback dispatch must complete successfully");
        assertEquals("hello, x", dispatched.result(), "the fallback proxy must unwrap the stubbed Result value");
    }

    // --- Registry / factory construction helpers ---

    /**
     * Hand-builds the single-operation {@link ContractEntry} matching the compiled fixture.
     *
     * @param contractClass the loaded contract interface
     * @param operation     the contract's single operation method
     * @param paramName     the payload parameter's name
     * @return the built contract entry
     */
    private static ContractEntry<?> entryFor(Class<?> contractClass, Method operation, String paramName) {
        return ServiceContractEntries.deployable()
                .contract(contractClass)
                .serviceInstance(new Object())
                .name("reserved-greeter")
                .operation("greet")
                .method(operation)
                .payloadType(String.class)
                .returnType(String.class)
                .param(paramName, ParamSource.PAYLOAD, String.class)
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
