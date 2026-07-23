// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.json.JsonObject;

/**
 * ServiceLoader SPI for contributing a Micrometer backend registry to the composite.
 *
 * <p>Each implementation contributes exactly one backend (one {@link MeterRegistryBackend}) to
 * the {@link MicrometerAssembly}. The assembly collects all discovered providers, sorts them via
 * {@link OrderedExtension#comparator()}, validates that no two share the same
 * {@link #backendName()}, then calls {@link #create} in sorted order.
 *
 * <p>Example implementation (Prometheus backend):
 * <pre>{@code
 * public class PrometheusRegistryProvider implements MeterRegistryProvider {
 *     @Override public String backendName() { return "prometheus"; }
 *
 *     @Override
 *     public MeterRegistryBackend create(JsonObject backendConfig) {
 *         PrometheusMeterRegistry registry =
 *             new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
 *         return new MeterRegistryBackend() {
 *             public MeterRegistry registry() { return registry; }
 *             public void close() { registry.close(); }
 *         };
 *     }
 * }
 * }</pre>
 *
 * <p>The {@code META-INF/services/dev.vertique.micrometer.MeterRegistryProvider} file is written
 * by each backend module (e.g. {@code vertique-micrometer-registry-prometheus}).
 *
 * @see MeterRegistryBackend
 * @see MicrometerAssembly
 * @see OrderedExtension
 */
public interface MeterRegistryProvider extends OrderedExtension {

    /**
     * Returns a unique backend identifier, e.g. {@code "prometheus"} or {@code "influx"}.
     *
     * <p>Duplicate names across discovered providers cause {@link MicrometerAssembly#assemble} to
     * fail with an {@link IllegalStateException} before any backend is created.
     *
     * @return the backend name; never {@code null} or blank
     */
    String backendName();

    /**
     * Creates the backend registry for this provider.
     *
     * <p>The assembly passes only the {@code metrics.backends.&lt;backendName()&gt;} subtree of
     * the configuration — never the full config object. When the subtree is absent, an empty
     * {@link JsonObject} is supplied. Implementations must not log or include {@code backendConfig}
     * values in exception messages.
     *
     * @param backendConfig the backend-specific configuration subtree; never {@code null}
     * @return a fully initialised backend handle; never {@code null}
     * @throws Exception if backend initialisation fails (the assembly will roll back all
     *     already-created backends)
     */
    MeterRegistryBackend create(JsonObject backendConfig) throws Exception;
}
