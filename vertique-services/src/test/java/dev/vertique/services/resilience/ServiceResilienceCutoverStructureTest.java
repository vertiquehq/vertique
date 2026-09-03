// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.resilience;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@Timeout(20)
class ServiceResilienceCutoverStructureTest {

    interface PlainService {
        void op();
    }

    private static ServiceMethodMeta plainMeta() throws Exception {
        Method method = PlainService.class.getMethod("op");
        return ServiceMethodMeta.ofDirect(
                new Object(),
                ServiceMethodDescriptor.of(method),
                "services/test/plain/op",
                "test.plain.op",
                "test",
                "plain",
                "op",
                null,
                Void.class,
                List.of(),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    @Test
    void usesOnlyCommonExecutorAndBoundsRetry(Vertx vertx) throws Exception {
        assertFalse(Files.exists(Path.of("src/main/java/dev/vertique/services/dispatch/DispatchPipeline.java")));
        assertFalse(Files.exists(Path.of("src/main/java/dev/vertique/services/policy/PolicyChainBuilder.java")));
        assertFalse(Files.exists(Path.of("src/main/java/dev/vertique/services/policy/PolicyStage.java")));
        assertFalse(Files.exists(Path.of("src/main/java/dev/vertique/services/policy/CircuitBreakerStage.java")));
        Resilience resilience = Resilience.create(vertx);
        try {
            ServiceResilienceConfigAdapter adapter = new ServiceResilienceConfigAdapter(
                    resilience, new ServicesConfig(null, List.of()), java.util.Map.of(), java.util.Optional.empty());
            ServiceResiliencePipelineFactory factory = new ServiceResiliencePipelineFactory(adapter, resilience);
            assertNull(factory.pipeline(plainMeta()));
            assertDoesNotThrow(() -> dev.vertique.resilience.RetryConfig.builder()
                    .maxRetries(100)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> dev.vertique.resilience.RetryConfig.builder()
                    .maxRetries(101)
                    .build());
            assertFalse(java.util.Arrays.stream(ServicesConfig.class.getRecordComponents())
                    .anyMatch(component -> component.getName().toLowerCase().contains("bulkhead")));
        } finally {
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
