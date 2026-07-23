// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.core.extension.OrderedExtension;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles a {@link CompositeMeterRegistry} from a list of {@link MeterRegistryProvider}s and
 * a {@link MetricsConfig}.
 *
 * <p>The assembly lifecycle:
 * <ol>
 *   <li>Validate that no two providers share the same {@link MeterRegistryProvider#backendName()}.</li>
 *   <li>Build the ordered filter list: common-tag filter first, then cardinality-guard filters, then
 *       the optional global-meter cap. This list is both applied to the inner composite AND exposed
 *       via {@link #filters()} so the stable outer composite can install a delegating filter over it.</li>
 *   <li>Create each backend in {@link OrderedExtension#comparator()} order; add its registry as a
 *       composite child.</li>
 *   <li>Bind JVM metrics binders when {@code config.jvm().enabled()} is {@code true}.</li>
 * </ol>
 *
 * <p><b>Rollback on failure:</b> if any step after the first backend creation throws, every
 * already-created backend is closed in reverse creation order, {@link JvmGcMetrics} is closed if
 * it was bound, the composite is closed, and the exception propagates. Nothing is published to
 * {@link MeterRegistryHolder} on failure.
 *
 * <p>This class is package-private: callers interact through
 * {@link MeterRegistryHolder#bootstrap(MicrometerAssembly)}.
 *
 * @see MeterRegistryHolder
 * @see MeterRegistryProvider
 */
final class MicrometerAssembly implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MicrometerAssembly.class);

    /**
     * Associates a backend with the provider name it was created from, for use in close/rollback
     * log messages (Fix 3: identify the failing backend without leaking config values).
     *
     * @param name    the provider's {@link MeterRegistryProvider#backendName()} — a stable identifier
     *                that is NOT a config value and is safe to include in log messages
     * @param backend the backend handle
     */
    private record BackendEntry(String name, MeterRegistryBackend backend) {}

    private final CompositeMeterRegistry composite;
    private final List<BackendEntry> backends;
    private final List<MeterFilter> filterList;
    private final JvmGcMetrics jvmGcMetrics;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private MicrometerAssembly(
            CompositeMeterRegistry composite,
            List<BackendEntry> backends,
            List<MeterFilter> filterList,
            JvmGcMetrics jvmGcMetrics) {
        this.composite = composite;
        this.backends = backends;
        this.filterList = filterList;
        this.jvmGcMetrics = jvmGcMetrics;
    }

    // --- Factory ---

    /**
     * Assembles a new {@link MicrometerAssembly} from the given providers and configuration.
     *
     * <p>All state is local — nothing is published to {@link MeterRegistryHolder} here.
     * On any failure, already-created backends are closed in reverse order and the exception
     * propagates.
     *
     * @param providers       the ordered list of registry providers
     * @param config          the metrics configuration
     * @param backendsConfig  the {@code metrics.backends} subtree (may be empty)
     * @return a fully assembled, not-yet-published registry
     * @throws IllegalStateException if two providers share the same backend name, or if any
     *                               provider's {@link MeterRegistryProvider#create} fails
     */
    static MicrometerAssembly assemble(
            List<MeterRegistryProvider> providers, MetricsConfig config, JsonObject backendsConfig) {

        // --- 1. Validate uniqueness of backend names BEFORE creating any backend ---
        validateUniqueBackendNames(providers);

        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        List<BackendEntry> createdBackends = new ArrayList<>();
        JvmGcMetrics gcMetrics = null;

        try {
            // --- Step 1: Build the ordered filter list (shared source of truth for inner + outer) ---
            // Common-tag filter first, then per-key cardinality guards, then optional global meter cap.
            // This same list is applied to the inner composite here AND exposed via filters() so the
            // stable outer composite can install a delegating filter over it (Fix 1).
            List<MeterFilter> builtFilters = buildFilterList(config);

            // --- Step 2: Apply filter list to inner composite ---
            for (MeterFilter filter : builtFilters) {
                composite.config().meterFilter(filter);
            }

            // --- Step 3: Create backends in OrderedExtension order ---
            List<MeterRegistryProvider> sorted =
                    providers.stream().sorted(OrderedExtension.comparator()).toList();

            for (MeterRegistryProvider provider : sorted) {
                String name = provider.backendName();
                JsonObject providerConfig = backendsConfig.getJsonObject(name, new JsonObject());
                MeterRegistryBackend backend;
                try {
                    backend = provider.create(providerConfig);
                } catch (Exception createEx) {
                    // Wrap with backend name context so callers can identify which backend failed.
                    // Never include the exception message or cause chain — those may embed config
                    // values (hostnames, credentials) from push-backend SDKs.
                    throw new MetricsBootstrapException("backend '"
                            + name
                            + "' failed to initialize: "
                            + createEx.getClass().getSimpleName());
                }
                createdBackends.add(new BackendEntry(name, backend));
                composite.add(backend.registry());
            }

            // --- Step 4: JVM binders ---
            if (config.jvm().enabled()) {
                gcMetrics = new JvmGcMetrics();
                new JvmMemoryMetrics().bindTo(composite);
                gcMetrics.bindTo(composite);
                new JvmThreadMetrics().bindTo(composite);
                new ClassLoaderMetrics().bindTo(composite);
                new ProcessorMetrics().bindTo(composite);
            }

            return new MicrometerAssembly(composite, new ArrayList<>(createdBackends), builtFilters, gcMetrics);

        } catch (Exception e) {
            // Rollback: close gc metrics if already bound, then backends in reverse order, then composite.
            // Log the provider backendName (a stable non-config identifier) alongside the exception class name.
            // Never log the exception message or cause chain — those may embed configuration values
            // (hostnames, credentials) from push-backend SDKs.
            if (gcMetrics != null) {
                try {
                    gcMetrics.close();
                } catch (Exception gcEx) {
                    log.warn(
                            "Exception while closing JvmGcMetrics during rollback: {}",
                            gcEx.getClass().getSimpleName());
                }
            }
            for (int i = createdBackends.size() - 1; i >= 0; i--) {
                BackendEntry entry = createdBackends.get(i);
                try {
                    entry.backend().close();
                } catch (Exception closeEx) {
                    log.warn(
                            "Exception while closing backend [{}] during rollback: {}",
                            entry.name(),
                            closeEx.getClass().getSimpleName());
                }
            }
            try {
                composite.close();
            } catch (Exception compEx) {
                log.warn(
                        "Exception while closing composite during rollback: {}",
                        compEx.getClass().getSimpleName());
            }
            // Re-throw as unchecked if it's not already a RuntimeException
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Failed to assemble MicrometerAssembly", e);
        }
    }

    // --- Public accessors ---

    /**
     * Returns the composite registry assembled by this instance.
     *
     * @return the composite registry; never {@code null}
     */
    CompositeMeterRegistry composite() {
        return composite;
    }

    /**
     * Returns the ordered list of {@link MeterFilter}s applied to the inner composite.
     *
     * <p>This list is the shared source of truth used both for the inner composite and for the
     * delegating filter installed on the stable outer composite (Fix 1). The order is:
     * common-tags filter first, then per-key cardinality guards, then the optional global meter cap.
     *
     * @return an unmodifiable ordered list of filters; never {@code null}
     */
    List<MeterFilter> filters() {
        return filterList;
    }

    // --- Lifecycle ---

    /**
     * Shuts down this assembly: closes {@link JvmGcMetrics}, then backends in reverse creation
     * order, then the composite. Idempotent.
     *
     * <p>Any exception thrown by a close step is logged at {@code WARN} using only the exception's
     * simple class name — never the message or cause chain — to prevent push-backend SDK exceptions
     * from leaking configuration values (hostnames, credentials) into the log stream.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // Close JvmGcMetrics first.
        // Log the backendName (a stable non-config identifier) alongside the exception class name.
        // Never log the exception message or cause chain — those may embed configuration values
        // (hostnames, credentials) from push-backend SDKs.
        if (jvmGcMetrics != null) {
            try {
                jvmGcMetrics.close();
            } catch (Exception e) {
                log.warn(
                        "Exception while closing JvmGcMetrics during shutdown: {}",
                        e.getClass().getSimpleName());
            }
        }

        // Close backends in reverse creation order
        for (int i = backends.size() - 1; i >= 0; i--) {
            BackendEntry entry = backends.get(i);
            try {
                entry.backend().close();
            } catch (Exception e) {
                log.warn(
                        "Exception while closing backend [{}] during shutdown: {}",
                        entry.name(),
                        e.getClass().getSimpleName());
            }
        }

        // Close composite last
        try {
            composite.close();
        } catch (Exception e) {
            log.warn(
                    "Exception while closing composite during shutdown: {}",
                    e.getClass().getSimpleName());
        }
    }

    // --- Private helpers ---

    /**
     * Validates each provider's {@link MeterRegistryProvider#backendName()} is non-null and
     * non-blank, then validates that no two providers share the same backend name.
     *
     * <p>On a null or blank name, throws {@link MetricsBootstrapException} naming the provider
     * CLASS (not the value) so no config value leaks into the message.
     *
     * <p>On a duplicate name, throws {@link IllegalStateException} naming both provider class
     * names; no config values are included in the message.
     *
     * @param providers the list to validate
     * @throws MetricsBootstrapException if any provider returns a null or blank backend name
     * @throws IllegalStateException if a duplicate backend name is found
     */
    private static void validateUniqueBackendNames(List<MeterRegistryProvider> providers) {
        // --- Step 1: Validate each name is non-null and non-blank before grouping ---
        for (MeterRegistryProvider provider : providers) {
            String name = provider.backendName();
            if (name == null || name.isBlank()) {
                throw new MetricsBootstrapException("MeterRegistryProvider '"
                        + provider.getClass().getName()
                        + "' returned a null or blank backendName()");
            }
        }

        // --- Step 2: Group providers by their backend name; any group with size > 1 is a collision ---
        Map<String, List<MeterRegistryProvider>> byName =
                providers.stream().collect(Collectors.groupingBy(MeterRegistryProvider::backendName));

        for (Map.Entry<String, List<MeterRegistryProvider>> entry : byName.entrySet()) {
            if (entry.getValue().size() > 1) {
                String classNames = entry.getValue().stream()
                        .map(p -> p.getClass().getName())
                        .collect(Collectors.joining(", "));
                throw new IllegalStateException(
                        "Duplicate MeterRegistryProvider backend name detected; conflicting providers: " + classNames);
            }
        }
    }

    /**
     * Builds the ordered list of {@link MeterFilter}s for the given config.
     *
     * <p>The list is the single source of truth applied both to the inner composite and
     * (indirectly) to the stable outer composite via the delegating filter in
     * {@link MeterRegistryHolder}. Order:
     * <ol>
     *   <li>Common-tags filter — injects the {@code service} tag and any extra tags.</li>
     *   <li>Per-key cardinality guards from {@link CardinalityGuard#filters}.</li>
     *   <li>Optional global meter cap (when {@link MetricsConfig.CardinalityConfig#maxMeters()}
     *       is greater than zero).</li>
     * </ol>
     *
     * @param config the metrics configuration
     * @return an unmodifiable ordered list of filters; never {@code null}
     */
    private static List<MeterFilter> buildFilterList(MetricsConfig config) {
        List<MeterFilter> result = new ArrayList<>();

        // --- Common-tags filter (service name + extra tags) ---
        String serviceName = resolveServiceName(config, System::getenv);
        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.of("service", serviceName));
        if (config.tags().extra() != null) {
            config.tags().extra().forEach((k, v) -> tags.add(Tag.of(k, v)));
        }
        result.add(MeterFilter.commonTags(Tags.of(tags)));

        // --- Cardinality-guard filters (per-key caps + optional global meter cap) ---
        result.addAll(CardinalityGuard.filters(config.cardinality()));

        return List.copyOf(result);
    }

    /**
     * Resolves the service name using a three-level precedence chain:
     * <ol>
     *   <li>The {@code tags.service} field from {@code config} (when non-null and non-blank).</li>
     *   <li>The {@code OTEL_SERVICE_NAME} environment variable (via {@code envLookup}).</li>
     *   <li>The literal string {@code "unknown-service"}.</li>
     * </ol>
     *
     * <p>This method is package-private to allow unit testing of the env-lookup branch via a
     * fake lookup function.
     *
     * @param config    the metrics configuration
     * @param envLookup a function that resolves an environment variable by name; may return
     *                  {@code null} for absent variables
     * @return the resolved service name; never {@code null}
     */
    static String resolveServiceName(MetricsConfig config, Function<String, String> envLookup) {
        String fromConfig = config.tags() != null ? config.tags().service() : null;
        if (fromConfig != null && !fromConfig.isBlank()) {
            return fromConfig;
        }
        String fromEnv = envLookup.apply("OTEL_SERVICE_NAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return "unknown-service";
    }
}
