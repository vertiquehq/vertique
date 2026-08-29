// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.codegen.services.processor.ServiceContractProcessor;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.services.ServiceContractContributor;
import dev.vertique.services.ServiceContractEntries;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-002: the Services codegen and reflective registration paths must share the canonical
 * resilience metadata package after the atomic vocabulary move.
 */
class ResilienceCodegenMigrationTest {

    private static final String CANONICAL_RESILIENCE_ANNOTATIONS =
            "dev.vertique.resilience.annotation.ResilienceAnnotations";
    private static final String LEGACY_RESILIENCE_PACKAGE = "dev.vertique.core.resilience";
    private static final String CONTRIBUTOR = "com.example.BillingService_ContractContributor";

    private static final JavaFileObject BILLING_SERVICE = SourceFiles.inline("com.example.BillingService", """
            package com.example;

            import dev.vertique.resilience.annotation.CircuitBreaker;
            import dev.vertique.resilience.annotation.Retry;
            import dev.vertique.resilience.annotation.Timeout;
            import dev.vertique.services.ServiceContract;
            import dev.vertique.services.ServiceOperation;
            import io.vertx.core.Future;

            @ServiceContract(value = "billing-service", namespace = "billing")
            @Timeout(10_000)
            @CircuitBreaker(maxFailures = 7)
            public interface BillingService {
                @ServiceOperation("charge")
                @Timeout(2_000)
                @Retry(maxRetries = 2, delayMs = 10)
                Future<String> charge(String chargeRequest);
            }
            """);

    private static final JavaFileObject BILLING_SERVICE_IMPL =
            SourceFiles.inline("com.example.BillingServiceImpl", """
            package com.example;

            import io.vertx.core.Future;
            import jakarta.inject.Inject;

            public class BillingServiceImpl implements BillingService {
                @Inject
                public BillingServiceImpl() {}

                @Override
                public Future<String> charge(String chargeRequest) {
                    return Future.succeededFuture("ok");
                }
            }
            """);

    private static final JavaFileObject STALE_IMPORT_FIXTURE =
            SourceFiles.inline("com.example.StaleResilienceConsumer", """
            package com.example;

            import dev.vertique.core.resilience.ResilienceAnnotations;

            public final class StaleResilienceConsumer {
                private final ResilienceAnnotations metadata;
            }
            """);

    @Test
    @DisplayName("clean Services codegen round trip uses only canonical resilience metadata")
    void cleanRoundTripUsesOnlyCanonicalPackage() throws Exception {
        // Given: one annotated contract and a direct implementation compiled through the real
        // Services processor seam.
        var generated = ProcessorTestHarness.run(new ServiceContractProcessor(), BILLING_SERVICE, BILLING_SERVICE_IMPL);

        // When: generated source is compiled and its contributor is loaded.
        generated
                .assertSuccess()
                .assertGeneratedSourceContains(CONTRIBUTOR, "import " + CANONICAL_RESILIENCE_ANNOTATIONS + ";")
                .assertGeneratedSourceDoesNotContain(CONTRIBUTOR, LEGACY_RESILIENCE_PACKAGE);
        assertCanonicalRepositoryConsumerSignatures();

        Class<?> implementationClass = generated.loadGeneratedClass("com.example.BillingServiceImpl");
        Class<?> contributorClass = generated.loadGeneratedClass(CONTRIBUTOR);
        Object implementation = implementationClass.getDeclaredConstructor().newInstance();
        Constructor<?> contributorConstructor = singleParameterConstructor(contributorClass);
        Object provider = providerFor(implementation, contributorClass.getClassLoader());
        ServiceContractContributor contributor =
                (ServiceContractContributor) contributorConstructor.newInstance(provider);

        ContractEntry<?> generatedEntry =
                contributor.contribute(new JsonObject()).get(0);
        ContractEntry<?> reflectiveEntry = ServiceContractRegistry.build(Set.of(implementation), configParser())
                .entries()
                .iterator()
                .next();

        // Then: generated and reflective metadata agree, including the canonical metadata type
        // and the method-level-over-type-level resilience resolution.
        assertContractEntriesEquivalent(generatedEntry, reflectiveEntry);

        ProcessorTestHarness.run(new ServiceContractProcessor(), STALE_IMPORT_FIXTURE)
                .assertFailed();
    }

    private static void assertCanonicalRepositoryConsumerSignatures() {
        var metadataComponent = Arrays.stream(ServiceMethodMeta.class.getRecordComponents())
                .filter(component -> component.getName().equals("resilienceAnnotations"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                CANONICAL_RESILIENCE_ANNOTATIONS, metadataComponent.getType().getName());

        var builderMethod = Arrays.stream(ServiceContractEntries.OperationBuilder.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("resilienceAnnotations"))
                .findFirst()
                .orElseThrow();
        assertEquals(CANONICAL_RESILIENCE_ANNOTATIONS, builderMethod.getParameterTypes()[0].getName());
    }

    private static void assertContractEntriesEquivalent(ContractEntry<?> generated, ContractEntry<?> reflective) {
        assertEquals(reflective.baseAddress(), generated.baseAddress(), "baseAddress mismatch");
        assertEquals(reflective.namespace(), generated.namespace(), "namespace mismatch");
        assertEquals(reflective.name(), generated.name(), "name mismatch");
        assertEquals(reflective.stableContractId(), generated.stableContractId(), "stableContractId mismatch");
        assertEquals(reflective.operations().keySet(), generated.operations().keySet(), "operation set mismatch");

        for (String operation : reflective.operations().keySet()) {
            ServiceMethodMeta generatedMeta = generated.operations().get(operation);
            ServiceMethodMeta reflectiveMeta = reflective.operations().get(operation);
            assertNotNull(generatedMeta, "Missing generated operation: " + operation);
            assertEquals(reflectiveMeta.address(), generatedMeta.address(), operation + ": address mismatch");
            assertEquals(reflectiveMeta.operation(), generatedMeta.operation(), operation + ": operation mismatch");
            assertEquals(reflectiveMeta.payloadType(), generatedMeta.payloadType(), operation + ": payload mismatch");
            assertEquals(reflectiveMeta.returnType(), generatedMeta.returnType(), operation + ": return mismatch");
            assertEquals(
                    reflectiveMeta.params().size(), generatedMeta.params().size(), operation + ": params mismatch");
            assertEquals(
                    reflectiveMeta.resilienceAnnotations(),
                    generatedMeta.resilienceAnnotations(),
                    operation + ": resilience metadata mismatch");
            assertEquals(
                    CANONICAL_RESILIENCE_ANNOTATIONS,
                    generatedMeta.resilienceAnnotations().getClass().getName(),
                    operation + ": generated resilience type mismatch");
            assertEquals(
                    CANONICAL_RESILIENCE_ANNOTATIONS,
                    reflectiveMeta.resilienceAnnotations().getClass().getName(),
                    operation + ": reflective resilience type mismatch");
        }
    }

    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private static Constructor<?> singleParameterConstructor(Class<?> contributorClass) {
        return Arrays.stream(contributorClass.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == 1)
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("No single-parameter constructor on " + contributorClass.getName()));
    }

    private static Object providerFor(Object value, ClassLoader loader) throws ClassNotFoundException {
        Class<?> providerType = Class.forName("jakarta.inject.Provider", true, loader);
        return Proxy.newProxyInstance(
                loader,
                new Class<?>[] {providerType},
                (proxy, method, args) -> "get".equals(method.getName()) ? value : null);
    }
}
