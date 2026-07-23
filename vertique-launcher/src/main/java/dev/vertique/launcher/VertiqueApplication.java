// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.bootstrap.BootstrapContext;
import dev.vertique.bootstrap.ContributorFailureException;
import dev.vertique.bootstrap.ContributorRunner;
import dev.vertique.bootstrap.DefaultBootstrapContext;
import dev.vertique.bootstrap.VertxBuilderContributor;
import dev.vertique.config.bootstrap.BootstrapConfigLoader;
import dev.vertique.config.bootstrap.BootstrapConfigLoader.BootstrapResult;
import dev.vertique.config.source.ConfigPropertySource;
import io.vertx.core.Deployable;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.launcher.application.ExitCodes;
import io.vertx.launcher.application.HookContext;
import io.vertx.launcher.application.VertxApplication;
import io.vertx.launcher.application.VertxApplicationHooks;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework-owned application entrypoint.
 *
 * <p>{@code VertiqueApplication} extends {@link VertxApplication} and implements
 * {@link VertxApplicationHooks}, acting as its own hooks delegate. Its responsibilities are:
 * <ol>
 *   <li>Capture the raw {@code --options} JSON from {@link #afterVertxOptionsParsed(JsonObject)},
 *       normalising {@code null} to an empty {@link JsonObject} as {@link #cliOptionsJson}.</li>
 *   <li>Capture the raw {@code --conf} configuration from {@link #afterConfigParsed(JsonObject)},
 *       normalising {@code null} to an empty {@link JsonObject}.</li>
 *   <li>Run the pre-Vertx bootstrap configuration load via {@link BootstrapConfigLoader#load} at
 *       the start of {@link #createVertxBuilder(VertxOptions)}: the resolved tree supersedes the
 *       raw {@code --conf} value so that {@link VertxBuilderContributor} instances always receive
 *       the fully-merged configuration. Any failure is captured and rethrown to abort startup;
 *       {@link #launch()} remaps the exit code to
 *       {@link ExitCodes#VERTX_INITIALIZATION} ({@code 11}).</li>
 *   <li>Discover and invoke the {@link VertxBuilderContributor} chain (via
 *       {@link ContributorRunner#discover()}) from {@link #createVertxBuilder(VertxOptions)},
 *       threading the contributed {@link VertxBuilder} into the Vert.x build. Each contributor
 *       receives the <em>pre-overlay</em> {@link VertxOptions} (the original instance provided by
 *       the upstream launcher) via {@link BootstrapContext#vertxOptions()}.</li>
 *   <li>Apply the {@code vertx.options} overlay in
 *       {@link #beforeStartingVertx(HookContext)}, which fires after the upstream launcher has
 *       applied cluster flags, {@code vertx.options.*} system properties, and metrics/tracer SPI
 *       conversions via {@code processVertxOptions}. The overlay is computed there so that all
 *       upstream mutations (cluster host/port, sysprops, SPI options) are visible in the base
 *       before the tree is merged on top. See the <em>vertx.options overlay</em> section below for
 *       the full precedence rules.</li>
 *   <li>Install the resolved configuration tree as the verticle deployment config in
 *       {@link #beforeDeployingVerticle(HookContext)} so that {@code MainVerticle.config()} always
 *       returns the canonical merged tree.</li>
 *   <li>Own the exit decision in {@link #launch()}: bootstrap or contributor failure → exit
 *       {@link ExitCodes#VERTX_INITIALIZATION} ({@code 11}); any other failure → the code
 *       returned by the upstream launcher; zero on success.</li>
 * </ol>
 *
 * <h2>vertx.options overlay</h2>
 * <p>The overlay is applied in {@link #beforeStartingVertx(HookContext)}, after the upstream
 * launcher's {@code processVertxOptions} has run. This ensures cluster flags, system-property
 * overrides, and metrics/tracer SPI conversions are all visible in the base before the tree is
 * merged. When the {@code vertx → options} section is absent or empty from the resolved config tree,
 * the original {@link VertxOptions} instance is passed through unchanged (zero-cost path that
 * preserves non-JSON-representable programmatic options). When the section is present, an effective
 * {@link VertxOptions} is produced with the following merge precedence (lowest to highest):
 * <ol>
 *   <li>Base options — {@link HookContext#vertxOptions()} after upstream processing.</li>
 *   <li>{@code vertx.options} tree section from the resolved bootstrap config.</li>
 *   <li>CLI {@code --options} JSON — re-applied above the tree so explicit CLI overrides survive
 *       the tree merge.</li>
 *   <li>{@code vertx.options.*} system properties — re-applied last, matching the upstream
 *       {@code processVertxOptions} behaviour where system properties override the {@code --options}
 *       JSON. This enables emergency runtime overrides without config file edits.</li>
 * </ol>
 * <p>Contributors receive the <em>pre-overlay</em> options (the original instance, already carrying
 * any contributor mutations, before the tree is applied). The resolved tree is observable via
 * {@link BootstrapContext#config()}. The final effective options after overlay are recorded in the
 * package-private field {@code effectiveVertxOptions} (test seam).
 * <p>Because the effective instance round-trips through JSON, programmatically-set options that
 * have no JSON representation are not preserved when the section is present; the absent-section
 * path exists precisely to handle that case with zero cost.
 *
 * <h2>Startup sequence</h2>
 * <ol>
 *   <li>{@link #afterVertxOptionsParsed(JsonObject)} — captures the raw {@code --options} JSON
 *       (or an empty object when absent) into {@link #cliOptionsJson}.</li>
 *   <li>{@link #afterConfigParsed(JsonObject)} — captures the raw {@code --conf} value
 *       (or an empty object when absent) into {@link #capturedConfig}.</li>
 *   <li>{@link #createVertxBuilder(VertxOptions)} — in three sub-steps:
 *     <ol>
 *       <li>Bootstrap config load: calls {@link BootstrapConfigLoader#load(JsonObject)} with
 *           the captured overlay. Creates a temporary Vert.x instance, resolves all config
 *           sources (file directories, env, sys, and any declared stores), overlays the
 *           {@code --conf} argument at highest precedence, and stores the resolved tree in
 *           {@link #capturedConfig}. On failure, captures the exception and rethrows to
 *           abort startup.</li>
 *       <li>Builder construction: creates the default builder via
 *           {@link VertxApplicationHooks#createVertxBuilder} bound to the <em>original</em>
 *           {@code options} instance (no overlay here). The builder reference is retained in
 *           {@link #retainedBuilder} for re-binding in {@link #beforeStartingVertx}.</li>
 *       <li>Shutdown construction: the {@link BootstrapShutdown} coordinator is built eagerly
 *           before the contributor chain so that a contributor failure path still has a
 *           correctly-wired shutdown; shutdown-hook threads and the launch thread always share
 *           exactly one instance.</li>
 *       <li>Contributor chain: discovers and runs all {@link VertxBuilderContributor}
 *           implementations. Each contributor receives the resolved tree via
 *           {@link BootstrapContext#config()} and the <em>pre-overlay</em> {@link VertxOptions}
 *           via {@link BootstrapContext#vertxOptions()}.</li>
 *     </ol>
 *   </li>
 *   <li>Upstream {@code processVertxOptions} runs (not overridden): applies cluster flags,
 *       {@code vertx.options.*} system properties, and metrics/tracer SPI conversions on the
 *       original {@code options} instance.</li>
 *   <li>{@link #beforeStartingVertx(HookContext)} — computes the {@code vertx.options} overlay
 *       from the resolved config tree. When present, re-binds the retained builder to the
 *       effective options. Errors abort startup with exit {@code 11}.</li>
 *   <li>{@link #beforeDeployingVerticle(HookContext)} — installs the resolved config as the
 *       deployment config so {@code MainVerticle.config()} receives the canonical tree.</li>
 * </ol>
 *
 * <h2>Subclassing contract</h2>
 * <p>Subclasses that override {@link #afterVertxOptionsParsed(JsonObject)},
 * {@link #afterConfigParsed(JsonObject)}, {@link #createVertxBuilder(VertxOptions)}, or
 * {@link #beforeStartingVertx(HookContext)} MUST call the corresponding {@code super} method so
 * that the captured config, CLI options JSON, contributor chain, and overlay are wired correctly.
 *
 * <h2>Shutdown contract</h2>
 * <p>Contributor shutdown hooks and bootstrap property-source closes run <em>exactly once</em>
 * across all stop/failure paths (normal Vert.x stop, deploy failure, start failure, contributor
 * failure, bootstrap failure), coordinated by {@link BootstrapShutdown}. Contributor hooks run
 * first, in reverse contribution order, confined to the successfully-contributed prefix. Property
 * sources are closed in reverse declaration order. See {@link VertxBuilderContributor#onShutdown()}
 * and {@link ConfigPropertySource#close()} for the individual contracts.
 *
 * <p><strong>Programmatic {@code vertx.close()} bypass:</strong> calling {@code vertx.close()}
 * directly (outside the launcher shutdown pipeline) does not invoke the contributor hook pipeline.
 * If hooks must run on a programmatic close, call {@link #runShutdownSequence()} explicitly
 * after closing.
 *
 * <h2>Exit-code contract</h2>
 * <ul>
 *   <li>{@code 0} — verticle deployed successfully (event loops keep the JVM alive).</li>
 *   <li>{@link ExitCodes#VERTX_INITIALIZATION} ({@code 11}) — bootstrap config load failed
 *       (detected in {@link #createVertxBuilder(VertxOptions)}), a
 *       {@link VertxBuilderContributor} failed during the contribution phase, or the
 *       {@code vertx.options} overlay failed (malformed section, detected in
 *       {@link #beforeStartingVertx}); shutdown sequence runs before exit.</li>
 *   <li>{@link ExitCodes#VERTX_DEPLOYMENT} ({@code 15}) — the main verticle failed to deploy.</li>
 * </ul>
 * <p>The no-args (production) constructor calls {@link System#exit} with the code when it is
 * non-zero. The protected constructor exposes {@code exitOnFailure} so tests and embedded uses can
 * suppress the {@code System.exit} call and inspect the returned code directly.
 *
 * <h2>Standalone entry</h2>
 * <p>{@link #verticleSupplier()} returns a framework-owned {@link VertiqueBootstrapVerticle} by
 * default, so an ordinary standalone application boots with <em>no</em> {@code MainVerticle} and
 * <em>no</em> {@code Main-Verticle} manifest entry: a non-null supplier makes the upstream launcher
 * bypass {@code Main-Verticle}/CLI verticle resolution entirely. Setting the system property
 * {@code -Dvertique.bootstrap.verticle=false} (case-insensitive) opts out — {@link #verticleSupplier()}
 * then returns {@code null}, deferring to the launcher's standard {@code Main-Verticle}/CLI resolution
 * so a custom-startup app can supply its own entry verticle the standard way.
 *
 * <h2>Configuration safety</h2>
 * <p>Config contents are never logged; only structural diagnostics (contributor class names,
 * exit codes) are emitted.
 */
public class VertiqueApplication extends VertxApplication implements VertxApplicationHooks {

    private static final Logger log = LoggerFactory.getLogger(VertiqueApplication.class);

    /** System-property prefix used by the upstream launcher for {@link VertxOptions} fields. */
    private static final String VERTX_OPTIONS_PROP_PREFIX = "vertx.options.";

    /**
     * System property that opts a standalone app out of the framework-owned bootstrap verticle.
     * When set to {@code "false"} (case-insensitive), {@link #verticleSupplier()} returns
     * {@code null} so the upstream launcher resolves the verticle via its standard
     * {@code Main-Verticle}/CLI mechanism instead (see FR-APP-030 escape hatch).
     */
    static final String BOOTSTRAP_VERTICLE_PROPERTY = "vertique.bootstrap.verticle";

    // --- Fields ---

    /** Copy of the exitOnFailure flag from the constructor (super always receives {@code false}). */
    private final boolean exitOnFailure;

    /**
     * Bootstrap configuration. Initialised to an empty {@link JsonObject}; updated to the raw
     * {@code --conf} overlay in {@link #afterConfigParsed(JsonObject)}, then replaced with the
     * fully-resolved tree by {@link BootstrapConfigLoader#load} in
     * {@link #createVertxBuilder(VertxOptions)} before the contributor chain runs.
     */
    private volatile JsonObject capturedConfig = new JsonObject();

    /**
     * The parsed {@code --options} JSON captured from {@link #afterVertxOptionsParsed(JsonObject)}.
     * Never {@code null}: normalised to an empty {@link JsonObject} when the flag is absent.
     * Re-applied in {@link #beforeStartingVertx(HookContext)} above the tree section to preserve
     * CLI precedence over config-file values.
     */
    private volatile JsonObject cliOptionsJson = new JsonObject();

    /**
     * The result of a successful bootstrap load. {@code null} until the load completes, or when
     * a bootstrap failure was captured instead.
     */
    private volatile BootstrapResult bootstrapResult;

    /**
     * Lazily initialised contributor runner; set on the first call to
     * {@link #createVertxBuilder(VertxOptions)}.
     */
    private volatile ContributorRunner runner;

    /**
     * The {@link VertxBuilder} constructed in {@link #createVertxBuilder(VertxOptions)} and
     * retained so that {@link #beforeStartingVertx(HookContext)} can re-bind it to the
     * {@code vertx.options} overlay options when the tree section is present.
     *
     * <p>This is the <em>final</em> builder returned by {@link #createVertxBuilder(VertxOptions)}
     * after all contributors have run (contributors may have called {@code withMetrics},
     * {@code withTracer}, etc.). Re-binding via {@link VertxBuilder#with(VertxOptions)} replaces
     * only the options; all other builder state (metrics factories, tracers) is preserved.
     */
    private volatile VertxBuilder retainedBuilder;

    /**
     * Stores a {@link Throwable} if startup fails — either a bootstrap load failure, a
     * {@link ContributorFailureException}, a {@code vertx.options} overlay failure, or a
     * {@link java.util.ServiceConfigurationError} from contributor discovery. The first writer wins;
     * all failure sources are mutually exclusive so there is never a collision.
     * {@link #launch()} consumes this field to remap the exit code to
     * {@link ExitCodes#VERTX_INITIALIZATION} and log the failure.
     */
    private volatile Throwable startupFailure;

    /**
     * Coordinates the exactly-once shutdown of contributor hooks and property sources.
     * Constructed eagerly at the end of {@link #createVertxBuilder(VertxOptions)} once all inputs
     * (runner and bootstrap result) are known. When {@link #createVertxBuilder} never ran (e.g.
     * CLI usage errors), the first caller to {@link #getOrBuildShutdown()} sets a fallback
     * instance via {@link AtomicReference#compareAndSet} so all concurrent callers share exactly
     * one instance.
     */
    private final AtomicReference<BootstrapShutdown> shutdownRef = new AtomicReference<>();

    /**
     * The effective {@link VertxOptions} resolved in {@link #beforeStartingVertx(HookContext)}.
     *
     * <p>When the {@code vertx.options} section is absent or empty, this is the same instance as
     * the original options (identical to {@link HookContext#vertxOptions()} at the time
     * {@link #beforeStartingVertx} runs). When the section is present, this is a new instance
     * produced by the overlay computation.
     *
     * <p>Exposed package-private as a test seam so that tests can verify the final effective
     * options (including both the tree values and the sysprop override layer) without going through
     * a full Vert.x metrics or options introspection.
     */
    volatile VertxOptions effectiveVertxOptions;

    // --- Constructors ---

    /**
     * Creates an instance with default failure behaviour: print usage and call
     * {@link System#exit} on failure.
     *
     * @param args the command-line arguments passed to the application
     */
    public VertiqueApplication(String[] args) {
        this(args, true, true);
    }

    /**
     * Creates an instance with customisable failure behaviour.
     *
     * <p>The upstream {@link VertxApplication} always receives {@code exitOnFailure=false};
     * {@link #launch()} is the sole owner of the exit decision, which ensures bootstrap-failure
     * and contributor-failure remapping, shutdown-hook execution, and property-source close are
     * all reachable before any {@link System#exit} call.
     *
     * @param args               the command-line arguments passed to the application
     * @param printUsageOnFailure whether to print usage to {@link System#out} on failure
     * @param exitOnFailure      whether to call {@link System#exit} with the exit code on failure
     */
    protected VertiqueApplication(String[] args, boolean printUsageOnFailure, boolean exitOnFailure) {
        super(args, null, printUsageOnFailure, false);
        this.exitOnFailure = exitOnFailure;
    }

    // --- Main ---

    /**
     * Application entry point. Creates a {@link VertiqueApplication} and calls {@link #launch()}.
     *
     * <p>On success, the JVM is kept alive by the Vert.x event loops — {@link System#exit} is
     * NOT called. On any failure, {@link System#exit} is called with the non-zero exit code.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        new VertiqueApplication(args).launch();
    }

    // --- VertxApplicationHooks ---

    /**
     * Captures the parsed {@code --options} JSON for later use in
     * {@link #beforeStartingVertx(HookContext)}.
     *
     * <p>This method returns the input unchanged (stock parity), including {@code null}. The
     * internal captured copy ({@link #cliOptionsJson}) is normalised to an empty
     * {@link JsonObject} when the input is {@code null} so the overlay logic always has a
     * non-null value to work with.
     *
     * <p>Subclasses that override this method MUST call
     * {@code super.afterVertxOptionsParsed(vertxOptions)} and return its result so that the CLI
     * options JSON is captured correctly.
     *
     * @param vertxOptions the parsed {@code --options} JSON, or {@code null} if the flag was absent
     * @return the same {@code vertxOptions} value, unchanged
     */
    @Override
    public JsonObject afterVertxOptionsParsed(JsonObject vertxOptions) {
        cliOptionsJson = (vertxOptions != null) ? vertxOptions.copy() : new JsonObject();
        return vertxOptions;
    }

    /**
     * Captures the parsed configuration for use in {@link #createVertxBuilder(VertxOptions)}.
     *
     * <p>This method returns the input unchanged (stock parity). A {@code null} input is returned
     * as-is to the caller, but the internal captured copy is normalised to an empty
     * {@link JsonObject} so that the bootstrap loader always receives a non-null overlay.
     *
     * <p>Subclasses that override this method MUST call {@code super.afterConfigParsed(config)} and
     * return its result so the config is captured correctly.
     *
     * @param config the parsed configuration, or {@code null} if no {@code --conf} argument was given
     * @return the same {@code config} value, unchanged
     */
    @Override
    public JsonObject afterConfigParsed(JsonObject config) {
        capturedConfig = (config != null) ? config.copy() : new JsonObject();
        return config;
    }

    /**
     * Runs the pre-Vertx bootstrap configuration load, constructs the default {@link VertxBuilder}
     * bound to the original (pre-overlay) options, then discovers and drives the
     * {@link VertxBuilderContributor} chain.
     *
     * <p><strong>Step 1 — Bootstrap config load:</strong> {@link BootstrapConfigLoader#load(JsonObject)}
     * is called with the {@code --conf} overlay at highest precedence. On success,
     * {@link #capturedConfig} is updated to the fully-resolved tree so that contributors receive it
     * via {@link BootstrapContext#config()}. On failure the exception is captured in
     * {@link #startupFailure} and re-thrown so the upstream launcher aborts startup;
     * {@link #launch()} then remaps the exit code to {@link ExitCodes#VERTX_INITIALIZATION} ({@code 11}).
     *
     * <p><strong>Step 2 — Builder construction:</strong> {@link VertxApplicationHooks#createVertxBuilder}
     * is called with the <em>original</em> {@code options} instance (no overlay here). The
     * {@code vertx.options} overlay is deferred to {@link #beforeStartingVertx(HookContext)} so
     * that it runs after the upstream launcher applies cluster flags, system properties, and
     * metrics/tracer SPI conversions. The builder is retained in {@link #retainedBuilder} for
     * re-binding in {@link #beforeStartingVertx}.
     *
     * <p><strong>Step 3 — Shutdown construction:</strong> once the runner is discovered and the
     * bootstrap result is available, a {@link BootstrapShutdown} is built eagerly via
     * {@link AtomicReference#compareAndSet} so that shutdown-hook threads always share exactly
     * one instance — no race with the launch thread or between multiple shutdown callbacks.
     * This happens before the contributor chain runs so that a contributor failure still has a
     * correctly-wired shutdown coordinator.
     *
     * <p><strong>Step 4 — Contributor chain:</strong> each registered
     * {@link VertxBuilderContributor} receives the resolved config and the <em>pre-overlay</em>
     * {@link VertxOptions} instance. If a {@link ContributorFailureException} is thrown, it is
     * stored in {@link #startupFailure} for remapping by {@link #launch()} and then re-thrown.
     *
     * <p>Subclasses that override this method MUST call {@code super.createVertxBuilder(options)}
     * and return its result.
     *
     * @param options the live, mutable {@link VertxOptions} instance provided by the launcher;
     *                the {@code vertx.options} overlay is NOT applied here — it is applied later
     *                in {@link #beforeStartingVertx(HookContext)}
     * @return the customised {@link VertxBuilder} after all contributors have run
     */
    @Override
    public VertxBuilder createVertxBuilder(VertxOptions options) {

        // --- Step 1: Bootstrap config load ---
        // Must run before contributors so that context.config() carries the resolved tree. On
        // failure, capture the exception and rethrow so the upstream launcher aborts startup.
        BootstrapResult result;
        try {
            result = BootstrapConfigLoader.load(capturedConfig);
            bootstrapResult = result;
            capturedConfig = result.config();
        } catch (RuntimeException e) {
            startupFailure = e;
            throw e;
        }

        // --- Step 2: Create builder bound to the ORIGINAL options (no overlay yet) ---
        // The vertx.options overlay is deferred to beforeStartingVertx so that upstream's
        // processVertxOptions (cluster flags, vertx.options.* sysprops, metrics/tracer SPI) runs
        // first. retainedBuilder is the final builder after contributors; beforeStartingVertx
        // re-binds it when the overlay is needed.
        VertxBuilder builder = VertxApplicationHooks.super.createVertxBuilder(options);
        if (runner == null) {
            try {
                runner = discoverContributors();
            } catch (Throwable t) {
                startupFailure = t;
                // Rethrow as RuntimeException so the upstream launcher's abort path activates.
                // The original Throwable (including ServiceConfigurationError) is captured in
                // startupFailure; launch() remaps to exit-11 and runs shutdown via getOrBuildShutdown().
                if (t instanceof RuntimeException re) {
                    throw re;
                }
                throw new ContributorFailureException("Contributor discovery failed", t);
            }
        }

        // --- Step 3: Eager shutdown construction ---
        // All inputs (runner + bootstrap result) are now known. Build once here so shutdown-hook
        // threads never race against the launch thread. Built before contributeAll so that a
        // contributor failure path still gets a correctly-wired shutdown.
        shutdownRef.compareAndSet(null, new BootstrapShutdown(runner, result.propertySources()));

        // --- Step 4: Contributor chain ---
        // Contributors receive the pre-overlay options. The tree overlay is applied in
        // beforeStartingVertx after processVertxOptions has run.
        VertxBuilder finalBuilder;
        try {
            finalBuilder = runner.contributeAll(builder, new DefaultBootstrapContext(capturedConfig, options));
        } catch (ContributorFailureException e) {
            startupFailure = e;
            throw e;
        }

        retainedBuilder = finalBuilder;
        return finalBuilder;
    }

    /**
     * Computes and applies the {@code vertx.options} overlay after the upstream launcher has
     * applied cluster flags, {@code vertx.options.*} system properties, and metrics/tracer SPI
     * conversions.
     *
     * <p>This hook fires <em>after</em> {@code processVertxOptions} in the upstream launcher,
     * so {@link HookContext#vertxOptions()} is the fully-prepared base (original options plus all
     * upstream mutations). The overlay is then applied with the precedence:
     * base &lt; tree &lt; CLI &lt; sysprops.
     *
     * <p>When the {@code vertx.options} section is absent or empty in the resolved config tree,
     * this method is a near-no-op: it records {@link HookContext#vertxOptions()} in
     * {@link #effectiveVertxOptions} and returns. The retained builder is not re-bound because it
     * was already bound to the same options instance (which upstream mutated in-place).
     *
     * <p>When the section is present:
     * <ol>
     *   <li>Scans {@link System#getProperties()} for keys starting with
     *       {@code "vertx.options."} and builds a {@link JsonObject} with properly-typed values
     *       (Long for parseable integers, Boolean for {@code "true"}/{@code "false"}, String
     *       otherwise). This mirrors the type-coercion logic in the upstream
     *       {@code configureFromSystemProperties} method.</li>
     *   <li>Calls {@link VertxOptionsOverlay#apply} to merge: base &lt; tree &lt; CLI &lt;
     *       sysprops.</li>
     *   <li>If a new effective instance was produced, calls {@link VertxBuilder#with(VertxOptions)}
     *       on {@link #retainedBuilder} to re-bind it before Vert.x is built.</li>
     *   <li>Records the effective options in {@link #effectiveVertxOptions}.</li>
     * </ol>
     *
     * <p>Any {@link RuntimeException} thrown during the overlay computation (e.g. a malformed
     * {@code vertx.options} section that is not a JSON object) is captured in
     * {@link #startupFailure} and re-thrown, causing the upstream launcher to abort startup with
     * exit {@link ExitCodes#VERTX_INITIALIZATION} ({@code 11}). Because
     * {@link BootstrapShutdown} was built eagerly in {@link #createVertxBuilder(VertxOptions)},
     * the property sources created by a successful bootstrap load are still closed on this
     * abort path.
     *
     * <p>Subclasses that override this method MUST call
     * {@code super.beforeStartingVertx(context)} so that the overlay is applied and
     * {@link #effectiveVertxOptions} is recorded correctly.
     *
     * @param context the hook context providing the fully-prepared {@link VertxOptions} (after
     *                upstream {@code processVertxOptions}); never {@code null} in production
     */
    @Override
    public void beforeStartingVertx(HookContext context) {
        VertxOptions base = context.vertxOptions();
        try {
            JsonObject sysPropsJson = collectVertxOptionsSysProps();
            VertxOptions effective = VertxOptionsOverlay.apply(base, capturedConfig, sysPropsJson, cliOptionsJson);
            if (effective != base) {
                retainedBuilder.with(effective);
            }
            effectiveVertxOptions = effective;
        } catch (RuntimeException e) {
            startupFailure = e;
            throw e;
        }
    }

    /**
     * Installs the resolved bootstrap configuration tree as the verticle deployment config.
     *
     * <p>Replaces the deployment config with the resolved tree so that
     * {@code MainVerticle.config()} returns the canonical merged configuration (all config sources
     * including declared stores, env, sys, and the {@code --conf} overlay at highest precedence).
     *
     * @param context the hook context providing mutable {@link DeploymentOptions}; never
     *                {@code null} in production
     */
    @Override
    public void beforeDeployingVerticle(HookContext context) {
        context.deploymentOptions().setConfig(capturedConfig.copy());
    }

    /**
     * Supplies the framework-owned {@link VertiqueBootstrapVerticle} so a standalone application
     * boots with no {@code Main-Verticle} manifest entry.
     *
     * <p>A non-null supplier makes the upstream launcher bypass {@code Main-Verticle}/CLI verticle
     * resolution entirely (this hook runs <em>before</em> manifest resolution). By default this
     * returns {@code () -> new VertiqueBootstrapVerticle()}.
     *
     * <p><strong>Escape hatch (FR-APP-030):</strong> when the system property
     * {@value #BOOTSTRAP_VERTICLE_PROPERTY} equals {@code "false"} (case-insensitive), this returns
     * {@code null}, deferring to the launcher's standard {@code Main-Verticle}/CLI resolution so a
     * custom-startup app can supply its own main verticle the standard way (CLI positional argument
     * or {@code Main-Verticle}). The opt-out is a system property — not manifest auto-detection —
     * because this hook runs before manifest resolution, so reading the manifest to decide would be
     * ambiguous.
     *
     * @return a supplier of the framework bootstrap verticle, or {@code null} when the
     *     {@value #BOOTSTRAP_VERTICLE_PROPERTY}{@code =false} opt-out is set
     */
    @Override
    public Supplier<? extends Deployable> verticleSupplier() {
        if ("false".equalsIgnoreCase(System.getProperty(BOOTSTRAP_VERTICLE_PROPERTY))) {
            return null;
        }
        return VertiqueBootstrapVerticle::new;
    }

    // --- Shutdown hook overrides ---

    /**
     * Runs the shutdown sequence (contributor hooks then property-source closes); idempotent —
     * see {@link BootstrapShutdown#runOnce()}.
     *
     * @param context the hook context; never {@code null} in production
     */
    @Override
    public void afterVertxStopped(HookContext context) {
        getOrBuildShutdown().runOnce();
    }

    /**
     * Runs the shutdown sequence; idempotent — see {@link BootstrapShutdown#runOnce()}.
     *
     * @param context the hook context; never {@code null} in production
     * @param t       the failure or {@code null} if Vert.x took too long to stop
     */
    @Override
    public void afterFailureToStopVertx(HookContext context, Throwable t) {
        getOrBuildShutdown().runOnce();
    }

    /**
     * Delegates to the default interface behaviour (closes the Vert.x instance), then runs
     * the shutdown sequence; idempotent — see {@link BootstrapShutdown#runOnce()}.
     * Closing Vert.x may trigger {@link #afterVertxStopped}, but the idempotency guard in
     * {@link BootstrapShutdown} absorbs the double invocation so the sequence runs exactly once.
     *
     * @param context the hook context providing the live {@link io.vertx.core.Vertx} instance;
     *                never {@code null} in production
     * @param t       the failure or {@code null} if the verticle took too long to start
     */
    @Override
    public void afterFailureToDeployVerticle(HookContext context, Throwable t) {
        VertxApplicationHooks.super.afterFailureToDeployVerticle(context, t);
        getOrBuildShutdown().runOnce();
    }

    /**
     * Runs the shutdown sequence for any contributors that completed successfully before the
     * Vert.x start failure; idempotent — see {@link BootstrapShutdown#runOnce()}.
     *
     * @param context the hook context; never {@code null} in production
     * @param t       the failure or {@code null} if Vert.x took too long to start
     */
    @Override
    public void afterFailureToStartVertx(HookContext context, Throwable t) {
        getOrBuildShutdown().runOnce();
    }

    // --- Public API ---

    /**
     * Runs the full shutdown sequence — contributor hooks (in reverse contribution order) followed
     * by bootstrap property-source closes — exactly once. Subsequent calls are no-ops.
     *
     * <p>This is the supported entry point for applications that close Vert.x programmatically
     * (outside the launcher shutdown pipeline) and need contributor hooks and property-source
     * closes to run. It is idempotent: subsequent calls after the first are no-ops (see
     * {@link BootstrapShutdown#runOnce()}).
     */
    public final void runShutdownSequence() {
        getOrBuildShutdown().runOnce();
    }

    /**
     * Launches the application and owns the exit decision.
     *
     * <p>Calls {@link VertxApplication#launch() super.launch()} (which runs with
     * {@code exitOnFailure=false} so the JVM stays alive regardless of the outcome). Then:
     * <ol>
     *   <li>If a startup failure (bootstrap, contributor, or overlay) was stored during startup:
     *       logs the failure with its full cause chain (safe by construction — framework messages
     *       carry references and counts only, and provider modules sever or sanitize SDK causes;
     *       pinned by the redaction log-capture tests), runs the shutdown sequence, and remaps
     *       the code to {@link ExitCodes#VERTX_INITIALIZATION} ({@code 11}).</li>
     *   <li>If {@link #exitOnFailure} is {@code true} and the final code is non-zero:
     *       calls {@link System#exit(int)} with that code.</li>
     *   <li>Returns the final exit code.</li>
     * </ol>
     *
     * @return the exit code; {@code 0} on success,
     *         {@link ExitCodes#VERTX_INITIALIZATION} on bootstrap, contributor, or overlay failure,
     *         {@link ExitCodes#VERTX_DEPLOYMENT} on deploy failure
     */
    @Override
    public int launch() {
        int code = super.launch();
        Throwable failure = startupFailure;
        if (failure != null) {
            log.error("Bootstrap aborted — {}", failure.getMessage(), failure);
            getOrBuildShutdown().runOnce();
            code = ExitCodes.VERTX_INITIALIZATION;
        }
        if (exitOnFailure && code != 0) {
            System.exit(code);
        }
        return code;
    }

    // --- Private helpers ---

    /**
     * Discovers {@link VertxBuilderContributor} implementations from the classpath via
     * {@link ContributorRunner#discover()}.
     *
     * <p>This is a package-private seam so that tests can override it to inject controlled
     * contributors or simulate a {@link java.util.ServiceConfigurationError} from a broken
     * provider registration, without needing real ServiceLoader entries. Production code always
     * reaches this default implementation.
     *
     * @return the discovered {@link ContributorRunner}; never {@code null}
     */
    ContributorRunner discoverContributors() {
        return ContributorRunner.discover();
    }

    /**
     * Scans {@link System#getProperties()} for keys starting with {@code "vertx.options."} and
     * builds a {@link JsonObject} suitable for merging into a {@link VertxOptions} JSON
     * representation.
     *
     * <p>Value coercion (matches the upstream {@code configureFromSystemProperties} type
     * resolution):
     * <ul>
     *   <li>If the value parses as a {@code long} via {@link Long#parseLong}, it is stored as a
     *       {@link Long} (compatible with {@code int}, {@code long} fields in
     *       {@link io.vertx.core.VertxOptionsConverter} via {@code Number.intValue()}).</li>
     *   <li>If the value is exactly {@code "true"} or {@code "false"} (case-insensitive), it is
     *       stored as a {@link Boolean}.</li>
     *   <li>Otherwise the raw {@link String} is stored (for enum and String fields).</li>
     * </ul>
     *
     * <p>The returned object is freshly constructed on each call; it is safe to mutate without
     * affecting system properties or other callers.
     *
     * @return a {@link JsonObject} mapping suffix-after-prefix field names to coerced values;
     *         never {@code null}; empty when no matching properties exist
     */
    private JsonObject collectVertxOptionsSysProps() {
        JsonObject result = new JsonObject();
        System.getProperties().forEach((key, val) -> {
            String propName = (String) key;
            if (propName.startsWith(VERTX_OPTIONS_PROP_PREFIX)) {
                String fieldName = propName.substring(VERTX_OPTIONS_PROP_PREFIX.length());
                String propVal = (String) val;
                Object typed = coerceSysPropValue(propVal);
                result.put(fieldName, typed);
            }
        });
        return result;
    }

    /**
     * Coerces a system-property string value to the most appropriate JSON type.
     *
     * <p>Attempts {@link Long} parse first; falls back to {@link Boolean} for
     * {@code "true"}/{@code "false"}; otherwise returns the raw string.
     *
     * @param value the raw string value from a system property; never {@code null}
     * @return the coerced value as {@link Long}, {@link Boolean}, or {@link String}
     */
    private static Object coerceSysPropValue(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // not a long
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }

    /**
     * Returns the {@link BootstrapShutdown} coordinator, constructing a fallback instance if
     * {@link #createVertxBuilder} never completed (e.g. CLI usage errors, or an abort between a
     * successful bootstrap load and the eager construction — a malformed {@code vertx.options}
     * section or a contributor-discovery failure).
     *
     * <p>Uses {@link AtomicReference#compareAndSet} so all racing callers share exactly one
     * instance. The fallback carries any property sources from a completed bootstrap load so
     * they are closed even on that abort window; the runner is {@code null} because no
     * contributor ran on those paths.
     *
     * @return the shared {@link BootstrapShutdown} instance
     */
    private BootstrapShutdown getOrBuildShutdown() {
        BootstrapResult result = bootstrapResult;
        shutdownRef.compareAndSet(
                null, new BootstrapShutdown(null, result != null ? result.propertySources() : List.of()));
        return shutdownRef.get();
    }
}
