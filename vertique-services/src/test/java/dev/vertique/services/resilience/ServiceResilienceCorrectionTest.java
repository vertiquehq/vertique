// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.vertique.deploy.SupervisionConfig;
import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResolvedResiliencePolicy;
import dev.vertique.resilience.Retry;
import dev.vertique.resilience.RetryBackoff;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.services.config.RetryOverride;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServiceOperationConfig;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@Timeout(20)
class ServiceResilienceCorrectionTest {

    public static final class SentinelBackoff implements BackoffStrategy {
        public SentinelBackoff() {}

        @Override
        public long delay(int retryCount) {
            return 999_999L;
        }
    }

    interface AnnotatedService {
        @dev.vertique.resilience.annotation.Retry(
                maxRetries = 1,
                delayMs = 17,
                backoffMultiplier = 2.5,
                maxDelayMs = 91,
                backoff = SentinelBackoff.class)
        void op();
    }

    @Test
    void usesZeroBasedRetryAndScalarOverridePrecedence(Vertx vertx) throws Exception {
        Resilience resilience = Resilience.create(vertx);
        try {
            AtomicInteger observedRetryOrdinal = new AtomicInteger(-1);
            AtomicInteger calls = new AtomicInteger();
            Retry retry = Retry.builder(resilience, "services.correction.ordinal")
                    .maxRetries(1)
                    .backoff(RetryBackoff.fixed(0))
                    .fallbackPolicy((failure, retryCount) -> {
                        observedRetryOrdinal.set(retryCount);
                        return true;
                    })
                    .build();

            String result = retry.execute(() -> calls.incrementAndGet() == 1
                            ? Future.failedFuture(new IllegalStateException("sentinel-attempt"))
                            : Future.succeededFuture("ok"))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();

            assertEquals("ok", result);
            assertEquals(2, calls.get());
            assertEquals(0, observedRetryOrdinal.get());

            Method method = AnnotatedService.class.getMethod("op");
            ServiceMethodMeta meta = ServiceMethodMeta.ofDirect(
                    new Object(),
                    ServiceMethodDescriptor.of(method),
                    "services/test/correction/op",
                    "test.correction.op",
                    "test",
                    "correction",
                    "op",
                    null,
                    Void.class,
                    List.of(),
                    ResilienceAnnotations.resolve(AnnotatedService.class, method),
                    List.of(),
                    List.of(),
                    false);
            ServiceConfig serviceConfig = new ServiceConfig(
                    "test",
                    "correction",
                    1,
                    false,
                    null,
                    SupervisionConfig.DEFAULT,
                    List.of(new ServiceOperationConfig(
                            "op", null, null, null, new RetryOverride(null, 23L, null, null))));
            ServiceResilienceConfigAdapter adapter = new ServiceResilienceConfigAdapter(
                    resilience,
                    new ServicesConfig(null, List.of(serviceConfig)),
                    Map.of(new ServicesConfig.ServiceKey("test", "correction"), serviceConfig),
                    java.util.Optional.empty());

            ResolvedResiliencePolicy policy = adapter.resolve(meta);
            RetryBackoff.Exponential backoff = assertInstanceOf(
                    RetryBackoff.Exponential.class, policy.retry().orElseThrow().backoff());
            assertEquals(23L, backoff.initialDelayMs());
            assertEquals(2.5, backoff.multiplier());
            assertEquals(91L, backoff.maxDelayMs());
        } finally {
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
