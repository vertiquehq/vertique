// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.extension.ExtensionPhase;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.vertx.core.VertxBuilder;
import io.vertx.core.json.JsonObject;
import io.vertx.core.spi.VertxTracerFactory;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link VertxBuilderContributor} that bootstraps the OpenTelemetry SDK at application startup and
 * installs the Vert.x tracer.
 *
 * <p>This contributor is discovered via {@link java.util.ServiceLoader} from the
 * {@code META-INF/services/dev.vertique.bootstrap.VertxBuilderContributor} registration. It runs in
 * phase {@link ExtensionPhase#SYSTEM_FIRST} at priority {@code 110} so that it executes after the
 * Micrometer metrics contributor (priority 100) and before any application-level contributors.
 *
 * <h2>Disabled path</h2>
 * <p>When {@code tracing.enabled=false}, the contributor installs {@link VertxTracerFactory#NOOP}
 * via {@code builder.withTracer(...)}. This explicitly defeats {@code vertx-opentelemetry}'s
 * ServiceLoader auto-discovery, ensuring no tracer is wired. No SDK is built, no global is
 * registered, and {@link BootstrapContext#vertxOptions()} is not touched.
 *
 * <h2>Enabled path — new SDK</h2>
 * <p>When tracing is enabled and {@link GlobalOpenTelemetry#isSet()} is {@code false}:
 * <ol>
 *   <li>Builds an {@link OpenTelemetrySdk} via
 *       {@link AutoConfiguredOpenTelemetrySdk} using {@link OtelConfigProperties} as the
 *       lowest-precedence property layer. Environment variables and system properties always win.
 *       {@code disableShutdownHook()} prevents the SDK from registering a JVM shutdown hook —
 *       lifecycle is managed by {@link #onShutdown()}.
 *       {@code setResultAsGlobal()} registers the SDK as the global on successful build only.</li>
 *   <li>Sets {@link OpenTelemetryOptions} on {@code ctx.vertxOptions()} and wires an
 *       {@link OpenTelemetryTracingFactory} carrying the SDK into the builder. Both steps run
 *       inside the try/catch so that a failure at either step is caught: the owned SDK is closed
 *       (best-effort) and {@code ownedSdk} is cleared before throwing, preventing a leaked global
 *       SDK and ensuring {@link #onShutdown()} does not attempt to close an already-closed SDK.</li>
 * </ol>
 *
 * <h2>Enabled path — reuse existing global</h2>
 * <p>When {@link GlobalOpenTelemetry#isSet()} is {@code true} and this process has never previously
 * owned and closed the global, the contributor reuses the existing global (e.g. installed by a
 * javaagent) without building a new SDK. The {@code tracing.otel.*} configuration subtree is
 * silently ignored on this path.
 *
 * <h2>Fail-fast on closed-global relaunch</h2>
 * <p>If this process previously built and then closed an owned {@link GlobalOpenTelemetry} instance,
 * a subsequent {@code contribute()} call on the enabled path throws {@link TracingBootstrapException}
 * immediately — before attempting to reuse or rebuild the global. Re-launching tracing in the same
 * JVM after the global SDK has been closed is unsupported because {@link GlobalOpenTelemetry} is a
 * JVM-wide singleton that cannot be safely re-registered. This mirrors the fail-fast semantics of
 * the Micrometer side for embedded multi-launch.
 *
 * <h2>Failure handling and secret safety</h2>
 * <p>Any exception from {@link AutoConfiguredOpenTelemetrySdk} is caught and wrapped in a
 * {@link TracingBootstrapException} with <em>no cause attached</em>. SDK exceptions may embed
 * configuration values (hostnames, credentials, endpoint URLs) in their message or cause chain.
 * Severing the chain prevents those values from reaching the launcher's error log. Only the
 * underlying exception's simple class name is encoded in the message.
 *
 * <h2>Shutdown</h2>
 * <p>{@link #onShutdown()} closes only the {@link OpenTelemetrySdk} that this contributor built.
 * A reused global is never closed. The SDK's {@code close()} is a blocking call (up to 10 s)
 * and is idempotent.
 *
 * @see TracingConfig
 * @see OtelConfigProperties
 * @see TracingBootstrapException
 */
public final class OpenTelemetryBootstrapContributor implements VertxBuilderContributor {

    private static final Logger LOG = LoggerFactory.getLogger(OpenTelemetryBootstrapContributor.class);

    /**
     * Set to {@code true} when this process has owned and then closed a {@link GlobalOpenTelemetry}
     * instance. A second launch attempt after the global has been closed would silently wire a
     * closed SDK; this latch makes that fail-fast.
     *
     * <p>The latch is intentionally static and JVM-wide: {@link GlobalOpenTelemetry} itself is a
     * JVM-global singleton, so once an owned global has been closed, no future contributor in this
     * JVM can safely reuse or replace it.
     */
    private static final AtomicBoolean OWNED_GLOBAL_CLOSED = new AtomicBoolean(false);

    // --- Fields ---

    /**
     * The SDK this contributor built and owns; {@code null} if the contributor is on the disabled
     * path, has not yet contributed, or reused an existing global.
     */
    private volatile OpenTelemetrySdk ownedSdk;

    // --- Constructors ---

    /**
     * No-arg constructor used by the {@link java.util.ServiceLoader} discovery path.
     */
    public OpenTelemetryBootstrapContributor() {}

    // --- Test seam ---

    /**
     * Resets the {@link #OWNED_GLOBAL_CLOSED} latch to {@code false}.
     *
     * <p><b>For test use only.</b> Call from {@code @BeforeEach} / {@code @AfterEach} alongside
     * {@link GlobalOpenTelemetry#resetForTest()} to isolate test classes.
     */
    static void resetOwnedGlobalClosedForTests() {
        OWNED_GLOBAL_CLOSED.set(false);
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
     * Returns {@code 110} as the fine-grained priority within the {@link ExtensionPhase#SYSTEM_FIRST}
     * phase, placing this contributor after the Micrometer metrics contributor (priority 100).
     *
     * @return {@code 110}
     */
    @Override
    public int priority() {
        return 110;
    }

    // --- VertxBuilderContributor ---

    /**
     * Bootstraps the OpenTelemetry SDK (when enabled and no global exists) or reuses the existing
     * global, and wires the tracer into the {@link VertxBuilder}.
     *
     * <p>On the disabled path, installs {@link VertxTracerFactory#NOOP} to suppress
     * ServiceLoader-discovered tracers and returns the builder unchanged.
     *
     * @param builder the current {@link VertxBuilder}; never {@code null}
     * @param ctx     the bootstrap context providing config and live {@link io.vertx.core.VertxOptions};
     *                never {@code null}
     * @return the (potentially mutated) {@link VertxBuilder}; never {@code null}
     * @throws ConfigurationException    if the {@code tracing} config section is present but not a
     *                                   JSON object (value-free message, passes through untouched)
     * @throws TracingBootstrapException if SDK auto-configuration fails (no cause attached), or if
     *                                   this process previously owned and closed the global SDK
     *                                   (relaunching tracing in the same JVM is unsupported)
     */
    @Override
    public VertxBuilder contribute(VertxBuilder builder, BootstrapContext ctx) {
        // --- Parse TracingConfig ---
        // navigateObject throws ConfigurationException when "tracing" is present but not a JsonObject
        JsonObject tracingJson = JsonConfigPaths.navigateObject(ctx.config(), "tracing");
        TracingConfig config = tracingJson.mapTo(TracingConfig.class);

        // --- Disabled path ---
        if (!config.enabled()) {
            // Explicitly defeat vertx-opentelemetry's ServiceLoader auto-discovery
            return builder.withTracer(VertxTracerFactory.NOOP);
        }

        // --- Fail-fast: owned global was previously closed in this JVM ---
        if (OWNED_GLOBAL_CLOSED.get()) {
            throw new TracingBootstrapException("GlobalOpenTelemetry points at an SDK this process already closed"
                    + " — relaunching tracing in the same JVM is unsupported");
        }

        // --- Enabled path ---
        if (GlobalOpenTelemetry.isSet()) {
            // Reuse an existing global (e.g. installed by a javaagent) — nothing owned to roll back;
            // any exception from setTracingOptions/withTracer propagates directly (no cleanup needed)
            OpenTelemetry reused = GlobalOpenTelemetry.get();
            LOG.info(
                    "OpenTelemetry: reusing existing global instance; tracing.otel.* configuration is ignored on this path");
            ctx.vertxOptions().setTracingOptions(new OpenTelemetryOptions());
            return builder.withTracer(new OpenTelemetryTracingFactory(reused));
        } else {
            // Build a new SDK via autoconfigure
            try {
                JsonObject rootConfig = ctx.config();
                // Compute config properties eagerly so any ConfigurationException surfaces before
                // the SDK build, keeping config-shape errors distinct from SDK initialization errors
                Map<String, String> props = OtelConfigProperties.properties(rootConfig);
                OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                        .addPropertiesSupplier(() -> props)
                        .disableShutdownHook()
                        .setResultAsGlobal()
                        .build()
                        .getOpenTelemetrySdk();
                this.ownedSdk = sdk;

                // Wire the Vert.x tracing subsystem INSIDE the try block so that a failure
                // here is caught and the owned SDK is rolled back (closed + reference cleared)
                ctx.vertxOptions().setTracingOptions(new OpenTelemetryOptions());
                return builder.withTracer(new OpenTelemetryTracingFactory(sdk));
            } catch (ConfigurationException ce) {
                // Config-shape error from JsonConfigPaths: re-throw as-is (value-free message)
                throw ce;
            } catch (Exception e) {
                // Roll back the owned SDK if it was created — prevent a leaked global SDK
                if (this.ownedSdk != null) {
                    // The SDK was built via setResultAsGlobal() and is about to be closed. Poison the
                    // latch BEFORE close() — mirroring onShutdown() — so a concurrent contribute()
                    // cannot pass the top-of-method latch guard and enter the GlobalOpenTelemetry
                    // reuse branch while this SDK is mid-close.
                    OWNED_GLOBAL_CLOSED.set(true);
                    try {
                        this.ownedSdk.close();
                    } catch (Exception ignore) {
                        // Best-effort close; original failure takes priority
                    }
                    this.ownedSdk = null;
                }
                // Sever the cause chain to prevent config value leakage in logs
                throw new TracingBootstrapException(
                        "OpenTelemetry bootstrap failed: " + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Closes the {@link OpenTelemetrySdk} created during {@link #contribute}, if any, and latches
     * {@link #OWNED_GLOBAL_CLOSED} so that a subsequent relaunch attempt fails fast.
     *
     * <p>A reused global SDK is never closed by this method — only the SDK that was provisioned by
     * this contributor is released. Idempotent: calling this method multiple times is safe because
     * {@link OpenTelemetrySdk#close()} is itself idempotent, and the {@code ownedSdk} reference is
     * read once.
     *
     * <p><b>Latch ordering (Fix 2):</b> {@link #OWNED_GLOBAL_CLOSED} is set to {@code true}
     * <em>before</em> {@code local.close()} is called. This prevents a concurrent
     * {@link #contribute} call from passing the latch check and wiring a mid-close SDK: the window
     * where the latch is false but the SDK is shutting down is eliminated. The latch is JVM-wide
     * and monotonic (never reset to false in production), so there is no correctness cost to
     * setting it before the close completes.
     */
    @Override
    public void onShutdown() {
        OpenTelemetrySdk local = this.ownedSdk;
        if (local != null) {
            // Set latch BEFORE close() to eliminate the race window where a concurrent contribute()
            // could pass the latch check while the SDK is mid-shutdown. The latch is monotonic
            // (only set to true, never back to false in production), so early-set has no cost.
            OWNED_GLOBAL_CLOSED.set(true);
            local.close();
        }
    }
}
