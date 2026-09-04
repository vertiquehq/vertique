// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.swagger.v3.core.converter.ModelConverters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Proves that converter construction, which the swagger Maven plugin performs before registration,
 * cannot race the first initialization of swagger-core's process-wide registry.
 */
class ConverterRegistryBootstrapTest {

    @Test
    void parallelConverterConstructionUsesOneSwaggerRegistry() throws Exception {
        ModelConverters.reset();
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CyclicBarrier start = new CyclicBarrier(workers);
        List<Future<ModelConverters>> results = new ArrayList<>();

        try {
            for (int i = 0; i < workers; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    new FutureModelConverter();
                    return ModelConverters.getInstance(false);
                }));
            }

            ModelConverters registry = results.get(0).get(10, TimeUnit.SECONDS);
            for (Future<ModelConverters> result : results) {
                assertSame(registry, result.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            ModelConverters.reset();
        }
    }
}
