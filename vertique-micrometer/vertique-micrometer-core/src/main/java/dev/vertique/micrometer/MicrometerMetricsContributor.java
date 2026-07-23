// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.extension.ExtensionPhase;
import io.vertx.core.VertxBuilder;
import io.vertx.core.json.JsonObject;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MetricsDomain;
import io.vertx.micrometer.MicrometerMetricsFactory;
import io.vertx.micrometer.MicrometerMetricsOptions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * {@link VertxBuilderContributor} that bootstraps the Micrometer metrics subsystem at application
 * startup.
 *
 * <p>This contributor is discovered via {@link ServiceLoader} from the
 * {@code META-INF/services/dev.vertique.bootstrap.VertxBuilderContributor} registration. It runs in
 * phase {@link ExtensionPhase#SYSTEM_FIRST} at priority {@code 100} so that it executes before
 * any application-level contributors.
 *
 * <h2>Active path</h2>
 * <p>When metrics are enabled ({@code metrics.enabled=true}, the default) and at least one
 * {@link MeterRegistryProvider} is present on the classpath:
 * <ol>
 *   <li>Validates the tag policy via {@link TagPolicyValidator} — throws
 *       {@link ConfigurationException} on the first violation (message names the key and rule but
 *       never any value).</li>
 *   <li>Assembles a {@link MicrometerAssembly} via
 *       {@link MicrometerAssembly#assemble(List, MetricsConfig, JsonObject)}. The assembly has its
 *       own internal rollback: if any backend creation fails, already-created backends are closed
 *       in reverse order before the exception propagates.</li>
 *   <li>Builds {@link MicrometerMetricsOptions}: sets {@code enabled=true},
 *       {@code registryName="vertique"}, applies disabled-domain flags from
 *       {@link MetricsConfig.VertxMetricsConfig}, and optionally overrides the label set when
 *       {@code metrics.vertx.labels} is non-null in config.</li>
 *   <li>Calls {@code ctx.vertxOptions().setMetricsOptions(options)} to wire the options into the
 *       Vert.x instance that will be built.</li>
 *   <li>Calls {@code builder.withMetrics(new MicrometerMetricsFactory(assembly.composite()))} to
 *       wire the assembly's inner composite as the Vert.x metrics backend.</li>
 *   <li>Calls {@link MeterRegistryHolder#bootstrap(MicrometerAssembly)} as the last bootstrapping
 *       step — this adds the assembly's inner composite as a child of the stable outer composite,
 *       late-wiring any pre-bootstrap meters so they begin forwarding to the registered backends.</li>
 * </ol>
 *
 * <h2>Inert path</h2>
 * <p>If {@code metrics.enabled=false} or no providers are present, the method returns the builder
 * unchanged with zero side-effects — no options are set, no holder is bootstrapped.
 *
 * <h2>Failure handling and secret safety</h2>
 * <p>Any failure during assembly or builder wiring (after tag validation) is caught and wrapped in
 * a {@link MetricsBootstrapException} with <em>no cause attached</em>. This is by design: backend
 * SDK exceptions may embed configuration values (hostnames, credentials) in their message or cause
 * chain. Severing the chain prevents those values from reaching the launcher's error log.
 * Tag-policy {@link ConfigurationException}s are re-thrown as-is because their messages are
 * value-free by construction (they name only the key and the violated rule).
 *
 * <h2>Shutdown</h2>
 * <p>{@link #onShutdown()} detaches the assembly's inner composite from the stable outer composite
 * (via {@link MeterRegistryHolder#detach(MicrometerAssembly)}) and then closes the assembly.
 * This prevents increments on the stable outer from forwarding to closed backends.
 * The assembly's {@link MicrometerAssembly#close()} is idempotent, so calling
 * {@link #onShutdown()} multiple times is safe.
 *
 * @see MeterRegistryProvider
 * @see MicrometerAssembly
 * @see MeterRegistryHolder
 * @see MetricsBootstrapException
 */
public final class MicrometerMetricsContributor implements VertxBuilderContributor {

    // --- Fields ---

    /** Providers discovered at construction time; immutable after construction. */
    private final List<MeterRegistryProvider> providers;

    /**
     * The assembly created during {@link #contribute}; {@code null} before a successful active-path
     * contribution, and used by {@link #onShutdown()} for teardown.
     */
    private volatile MicrometerAssembly assembly;

    // --- Constructors ---

    /**
     * No-arg constructor used by the {@link ServiceLoader} discovery path.
     *
     * <p>Discovers all {@link MeterRegistryProvider} implementations on the classpath via
     * {@link ServiceLoader#load(Class)}.
     */
    public MicrometerMetricsContributor() {
        this.providers = StreamSupport.stream(
                        ServiceLoader.load(MeterRegistryProvider.class).spliterator(), false)
                .toList();
    }

    /**
     * Package-private test-seam constructor that accepts an explicit list of providers.
     *
     * @param providers the providers to use; must not be {@code null}
     */
    MicrometerMetricsContributor(List<MeterRegistryProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    // --- OrderedExtension ---

    /**
     * Returns {@link ExtensionPhase#SYSTEM_FIRST} so this contributor runs before any
     * application-level contributors.
     *
     * @return {@link ExtensionPhase#SYSTEM_FIRST}
     */
    @Override
    public ExtensionPhase phase() {
        return ExtensionPhase.SYSTEM_FIRST;
    }

    /**
     * Returns {@code 100} as the fine-grained priority within the {@link ExtensionPhase#SYSTEM_FIRST}
     * phase.
     *
     * @return {@code 100}
     */
    @Override
    public int priority() {
        return 100;
    }

    // --- VertxBuilderContributor ---

    /**
     * Bootstraps the Micrometer metrics subsystem, wiring the composite registry into the
     * {@link io.vertx.core.VertxBuilder} and publishing it to {@link MeterRegistryHolder}.
     *
     * <p>Returns the builder unchanged when the inert path applies (disabled or no providers).
     * On the active path, the builder is returned after {@code withMetrics} has been called.
     *
     * @param builder the current {@link VertxBuilder}; never {@code null}
     * @param ctx     the bootstrap context providing config and live {@link io.vertx.core.VertxOptions};
     *                never {@code null}
     * @return the (potentially mutated) {@link VertxBuilder}; never {@code null}
     * @throws MetricsBootstrapException if metrics are already bootstrapped, or if any assembly or
     *                                   builder-wiring step fails (no cause attached)
     * @throws ConfigurationException    if tag policy validation fails (value-free message)
     */
    @Override
    public VertxBuilder contribute(VertxBuilder builder, BootstrapContext ctx) throws Exception {
        // --- Preflight: guard against embedded multi-launch ---
        if (MeterRegistryHolder.bootstrapped()) {
            throw new MetricsBootstrapException(
                    "Micrometer metrics already bootstrapped; embedded multi-launch is unsupported");
        }

        // --- Parse MetricsConfig from ctx.config() ---
        // navigateObject throws ConfigurationException when "metrics" is present but not a JsonObject
        JsonObject metricsJson = JsonConfigPaths.navigateObject(ctx.config(), "metrics");
        MetricsConfig config = metricsJson.mapTo(MetricsConfig.class);

        // --- Inert path: disabled or no providers ---
        if (!config.enabled() || providers.isEmpty()) {
            return builder;
        }

        // --- Active path ---
        // navigateObject throws ConfigurationException when "backends" is present but not a JsonObject
        JsonObject backendsJson = JsonConfigPaths.navigateObject(ctx.config(), "metrics", "backends");

        // Step 1: Tag policy validation — ConfigurationException propagates as-is (value-free)
        TagPolicyValidator.validate(config.tags());

        // Step 2: Assembly + builder wiring; failures are caught and cause is severed
        MicrometerAssembly localAssembly = null;
        try {
            localAssembly = MicrometerAssembly.assemble(providers, config, backendsJson);

            // Step 3: Build MicrometerMetricsOptions
            MicrometerMetricsOptions options = buildMetricsOptions(config);
            ctx.vertxOptions().setMetricsOptions(options);

            // Step 4: Wire the factory into the builder
            builder = builder.withMetrics(new MicrometerMetricsFactory(localAssembly.composite()));

            // Step 5: Publish to holder — LAST step before storing assembly
            MeterRegistryHolder.bootstrap(localAssembly);
            this.assembly = localAssembly;

        } catch (MetricsBootstrapException mbe) {
            // MetricsBootstrapException from MicrometerAssembly already carries backend-name context
            // and has its cause severed at the assembly boundary — pass through as-is.
            // The assembly's internal rollback already ran before it threw; guard against a null
            // local assembly (e.g. if the exception was thrown before assemble() completed).
            if (localAssembly != null) {
                closeQuietly(localAssembly);
            }
            throw mbe;
        } catch (Exception e) {
            // Teardown the local assembly once, regardless of exception type
            if (localAssembly != null) {
                closeQuietly(localAssembly);
            }
            // ConfigurationException from label parsing re-throws as-is (value-free message)
            if (e instanceof ConfigurationException ce) {
                throw ce;
            }
            // All other failures: sever cause chain to prevent config value leakage
            throw new MetricsBootstrapException(
                    "Micrometer bootstrap failed: " + e.getClass().getSimpleName());
        }

        return builder;
    }

    /**
     * Detaches and closes the assembly created during {@link #contribute}, if any.
     *
     * <p>The assembly's inner composite is first removed from the stable outer composite via
     * {@link MeterRegistryHolder#detach(MicrometerAssembly)}, so that increments on the outer
     * no longer forward into the now-closing inner. The assembly is then closed.
     *
     * <p>Idempotent: calling this method multiple times is safe because
     * {@link MicrometerAssembly#close()} is itself idempotent.
     */
    @Override
    public void onShutdown() {
        MicrometerAssembly local = this.assembly;
        if (local != null) {
            MeterRegistryHolder.detach(local);
            local.close();
        }
    }

    // --- Private helpers ---

    /**
     * Builds a {@link MicrometerMetricsOptions} from the given {@link MetricsConfig}.
     *
     * <p>Sets {@code enabled=true}, {@code registryName="vertique"}, adds disabled-domain entries
     * for each Vert.x subsystem toggled off in config, and optionally replaces the default label
     * set when {@link MetricsConfig.VertxMetricsConfig#labels()} is non-null.
     *
     * @param config the resolved metrics configuration
     * @return the fully configured options; never {@code null}
     * @throws ConfigurationException if any label name in the config is not a valid
     *                                {@link Label} constant
     */
    private static MicrometerMetricsOptions buildMetricsOptions(MetricsConfig config) {
        MicrometerMetricsOptions options = new MicrometerMetricsOptions();
        options.setEnabled(true);
        options.setRegistryName("vertique");

        // --- Disabled domains ---
        MetricsConfig.VertxMetricsConfig vertx = config.vertx();
        if (!vertx.httpServer()) {
            options.addDisabledMetricsCategory(MetricsDomain.HTTP_SERVER);
        }
        if (!vertx.httpClient()) {
            options.addDisabledMetricsCategory(MetricsDomain.HTTP_CLIENT);
        }
        if (!vertx.netServer()) {
            options.addDisabledMetricsCategory(MetricsDomain.NET_SERVER);
        }
        if (!vertx.netClient()) {
            options.addDisabledMetricsCategory(MetricsDomain.NET_CLIENT);
        }
        if (!vertx.eventBus()) {
            options.addDisabledMetricsCategory(MetricsDomain.EVENT_BUS);
        }
        if (!vertx.datagramSocket()) {
            options.addDisabledMetricsCategory(MetricsDomain.DATAGRAM_SOCKET);
        }
        if (!vertx.namedPools()) {
            options.addDisabledMetricsCategory(MetricsDomain.NAMED_POOLS);
        }

        // --- Label override ---
        // When null: leave Vert.x defaults (HTTP_ROUTE stays off per MicrometerMetricsOptions defaults)
        // When non-null: replace defaults entirely
        List<String> labelNames = vertx.labels();
        if (labelNames != null) {
            EnumSet<Label> labelSet = EnumSet.noneOf(Label.class);
            for (String name : labelNames) {
                try {
                    labelSet.add(Label.valueOf(name));
                } catch (IllegalArgumentException e) {
                    throw new ConfigurationException("metrics.vertx.labels contains invalid label name '" + name
                            + "'; valid names are: " + validLabelNames());
                }
            }
            options.setLabels(labelSet);
        }

        return options;
    }

    /**
     * Returns a comma-separated string of all valid {@link Label} constant names.
     *
     * @return a non-null string listing all valid label names
     */
    private static String validLabelNames() {
        return Arrays.stream(Label.values()).map(Label::name).collect(Collectors.joining(", "));
    }

    /**
     * Closes the given assembly, suppressing any exception.
     *
     * @param a the assembly to close; must not be {@code null}
     */
    private static void closeQuietly(MicrometerAssembly a) {
        try {
            a.close();
        } catch (Exception ignored) {
            // best-effort: close exceptions must not mask the original failure
        }
    }
}
