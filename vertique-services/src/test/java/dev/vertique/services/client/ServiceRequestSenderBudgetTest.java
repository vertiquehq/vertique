// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.Result;
import dev.vertique.deploy.SupervisionConfig;
import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.annotation.Retry;
import dev.vertique.resilience.exception.ResiliencePolicyException;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceRegistrationException;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceSupervisor;
import dev.vertique.services.config.RetryOverride;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServiceOperationConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.resilience.ServiceResilienceConfigAdapter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@Timeout(20)
class ServiceRequestSenderBudgetTest {

    public static final class CustomBackoff implements BackoffStrategy {
        public CustomBackoff() {}

        @Override
        public long delay(int retryCount) {
            return retryCount;
        }
    }

    interface BudgetContract {
        @dev.vertique.resilience.annotation.Timeout(5000)
        void op();
    }

    @ServiceContract(namespace = "test", value = "budget-registration")
    interface RegistrationContract {
        @Retry(backoff = CustomBackoff.class)
        @ServiceOperation("op")
        Future<Void> op();
    }

    static final class RegistrationService implements RegistrationContract {
        @Override
        public Future<Void> op() {
            return Future.succeededFuture();
        }
    }

    private static ServiceMethodMeta meta(Class<?> contract, String operation) throws Exception {
        Method method = contract.getMethod(operation);
        return ServiceMethodMeta.ofDirect(
                new Object(),
                ServiceMethodDescriptor.of(method),
                "services/test/budget/" + operation,
                "test.budget." + operation,
                "test",
                "budget",
                operation,
                null,
                Void.class,
                List.of(),
                dev.vertique.resilience.annotation.ResilienceAnnotations.resolve(contract, method),
                List.of(),
                List.of(),
                false);
    }

    private static ServiceResilienceConfigAdapter adapter(
            Resilience resilience, ServicesConfig config, Map<ServicesConfig.ServiceKey, ServiceConfig> index) {
        return new ServiceResilienceConfigAdapter(resilience, config, index);
    }

    @Test
    void usesResolvedBudgetWithLegacyPrecedence(Vertx vertx) throws Exception {
        Resilience resilience = Resilience.create(vertx);
        try {
            EventBusClient eventBus = mock(EventBusClient.class);
            ServiceSupervisor supervisor = mock(ServiceSupervisor.class);
            when(supervisor.isAvailable(any())).thenReturn(true);
            when(eventBus.request(anyString(), any(), anyLong()))
                    .thenReturn(Future.succeededFuture(Result.success(null)));
            ServiceMethodMeta meta = meta(BudgetContract.class, "op");
            ResolvedServiceTarget target = ResolvedServiceTarget.of(BudgetContract.class, meta);

            ServiceRequestSender derived = new ServiceRequestSender(
                    eventBus, supervisor, adapter(resilience, new ServicesConfig(null, List.of()), Map.of()));
            derived.send(target, DispatchEnvelope.empty());
            verify(eventBus).request(eq(target.address()), any(), eq(6000L));

            ServiceConfig service = new ServiceConfig(
                    "test",
                    "budget",
                    1,
                    false,
                    200L,
                    SupervisionConfig.DEFAULT,
                    List.of(new ServiceOperationConfig(
                            "op", 300L, null, null, new RetryOverride(null, null, null, null))));
            EventBusClient explicitEventBus = mock(EventBusClient.class);
            when(explicitEventBus.request(anyString(), any(), anyLong()))
                    .thenReturn(Future.succeededFuture(Result.success(null)));
            ServiceRequestSender explicit = new ServiceRequestSender(
                    explicitEventBus,
                    supervisor,
                    adapter(
                            resilience,
                            new ServicesConfig(100L, List.of(service)),
                            Map.of(new ServicesConfig.ServiceKey("test", "budget"), service)));
            explicit.send(target, DispatchEnvelope.empty());
            verify(explicitEventBus).request(eq(target.address()), any(), eq(300L));
        } finally {
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    @Test
    void unknownBudgetRequiresExplicitTimeoutAndPreservesPolicyCause(Vertx vertx) throws Exception {
        Resilience resilience = Resilience.create(vertx);
        try {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new RegistrationService()), new JsonObject(), configParser());
            ServiceResilienceConfigAdapter adapter = adapter(resilience, new ServicesConfig(null, List.of()), Map.of());

            ServiceRegistrationException failure =
                    assertThrows(ServiceRegistrationException.class, () -> adapter.validate(registry.entries()));
            assertInstanceOf(ResiliencePolicyException.class, failure.getCause());
            assertEquals(
                    "Service registration failed with 1 violation(s):\n" + "  - "
                            + failure.violations().getFirst(),
                    failure.getMessage());
            assertEquals(1, failure.violations().size());
        } finally {
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }
}
