// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.bootstrap;

import dev.vertique.config.ConfigBootstrap;
import dev.vertique.config.placeholder.PlaceholderResolutionException;
import dev.vertique.config.placeholder.PlaceholderResolver;
import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import dev.vertique.config.source.ConfigPropertySources;
import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.config.spi.ConfigStoreFactory;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-Vertx bootstrap configuration loader.
 *
 * <p>Performs a two-phase synchronous configuration load using a temporary, minimal Vert.x
 * instance, followed by a three-pass placeholder resolution pass.
 *
 * <h2>Phase 1</h2>
 * <p>Runs the standard {@link ConfigBootstrap#defaultOptions()} chain
 * (per-dir {@code *.json} → per-dir {@code *.properties} → env → sys) and then overlays
 * the caller-supplied {@code deploymentConfig} at highest precedence.
 *
 * <h2>Phase 2 (optional)</h2>
 * <p>If the phase-1 merged tree contains a {@code config.stores} array, the stores array is
 * first resolved against the phase-1 tree (stores-declaration placeholder pass), then each
 * entry is validated and built into a {@link ConfigStoreOptions}. The entire chain is reloaded
 * via {@link ConfigBootstrap#defaultOptions(List)} with the declared stores inserted between
 * the file-directory stores and the env/sys stores, then the deployment overlay is re-applied.
 * Phase 2 is skipped entirely when {@code config.stores} is absent or empty.
 *
 * <p>Unknown store types are pre-validated before the reload; a helpful dependency hint is
 * included in the error message for known extension types.
 *
 * <h2>Three-pass placeholder resolution</h2>
 * <p>After the final merged tree (post-stores phase) is assembled, placeholder resolution runs
 * in three passes:
 * <ol>
 *   <li><strong>Pass 1 — propertySources subtree:</strong> reads {@code config.propertySources}
 *       from the merged tree and resolves it tree-only via
 *       {@link PlaceholderResolver#resolveAgainstTree(JsonArray, JsonObject)}. An unresolvable
 *       (source-requiring) reference in a source entry therefore fails with the
 *       "tree references only" message.</li>
 *   <li><strong>Pass 2 — instantiate sources:</strong> uses a validate-first-then-create
 *       approach. A first sub-pass over the entire array validates every entry (non-object,
 *       missing/blank {@code type}, unknown {@code type} — with dependency hint). A validation
 *       error throws {@link BootstrapConfigException} immediately before any source is created,
 *       so validation errors cannot leak already-started SDK clients. The optional {@code name}
 *       defaults to {@code type + "[" + index + "]"}. A second sub-pass creates sources in
 *       declaration order; a factory throw closes any already-created sources (reverse order,
 *       log-never-throw) and then throws a {@link BootstrapConfigException} naming the instance
 *       and type.</li>
 *   <li><strong>Pass 3 — whole-tree resolution:</strong>
 *       {@link PlaceholderResolver#resolveTree(JsonObject, List)} against the full merged tree
 *       using the instantiated sources. A {@link PlaceholderResolutionException} or
 *       {@link ConfigPropertySourceException} closes all created sources and propagates without
 *       wrapping.</li>
 * </ol>
 *
 * <p>Pass 3 always runs — even when no {@code config.propertySources} are declared, placeholders
 * that reference tree keys (or have defaults) are resolved.
 *
 * <p>The temporary Vert.x instance and its event-loop threads are fully shut down before this
 * method returns, so there are no leaked threads after the call completes.
 *
 * <p><b>Threading:</b> {@link #load(JsonObject)} MUST be called from a non-Vert.x thread.
 * An {@link IllegalStateException} is thrown immediately if a Vert.x context is active on
 * the calling thread.
 *
 * <h2>Section keys in the merged tree</h2>
 * <ul>
 *   <li>{@link #CONFIG_SECTION} — the {@code "config"} subtree for declared store configuration</li>
 *   <li>{@link #STORES_KEY} — the stores array within the config section</li>
 *   <li>{@link #PROPERTY_SOURCES_KEY} — the property-sources array within the config section</li>
 * </ul>
 *
 * @see ConfigBootstrap#defaultOptions()
 * @see ConfigBootstrap#defaultOptions(List)
 * @see PlaceholderResolver
 * @see BootstrapResult
 * @see BootstrapConfigException
 */
public final class BootstrapConfigLoader {

    // --- Section key constants ---

    /** Key for the {@code "config"} section in the canonical merged tree. */
    public static final String CONFIG_SECTION = "config";

    /**
     * Key for the stores array within the {@link #CONFIG_SECTION} section.
     * Each element must be a JsonObject with a non-blank {@code "type"} string,
     * an optional {@code "format"} string, and an optional {@code "config"} JsonObject.
     */
    public static final String STORES_KEY = "stores";

    /**
     * Key for the property-sources array within the {@link #CONFIG_SECTION} section.
     * Each element must be a JsonObject with a non-blank {@code "type"} string and an
     * optional {@code "name"} string (defaults to {@code type + "[" + index + "]"}).
     */
    public static final String PROPERTY_SOURCES_KEY = "propertySources";

    /**
     * Hard cap, in milliseconds, on each awaited bootstrap operation (load, temp-Vertx close).
     * A bootstrap step that takes longer than this is considered a failure.
     */
    public static final long BOOTSTRAP_TIMEOUT_MS = 30_000;

    /**
     * Well-known type → dependency hint map for Vert.x config store types that require an
     * optional module. Used to produce actionable error messages when an unknown declared
     * store type is encountered.
     */
    private static final Map<String, String> STORE_DEPENDENCY_HINTS = Map.of(
            "configmap", "add io.vertx:vertx-config-kubernetes-configmap",
            "aws-ssm", "add dev.vertique:vertique-config-aws-ssm");

    /**
     * Well-known type → dependency hint map for property-source types that require an
     * optional module. Used to produce actionable error messages when an unknown declared
     * property-source type is encountered.
     */
    private static final Map<String, String> SOURCE_DEPENDENCY_HINTS = Map.of(
            "vault", "add dev.vertique:vertique-config-vault",
            "aws-secrets", "add dev.vertique:vertique-config-aws-secrets",
            "azure-keyvault", "add dev.vertique:vertique-config-azure-keyvault");

    private static final Logger log = LoggerFactory.getLogger(BootstrapConfigLoader.class);

    private BootstrapConfigLoader() {}

    // --- Public API ---

    /**
     * Performs the pre-Vertx bootstrap load (two phases).
     *
     * <p>Creates a minimal temporary Vert.x instance, runs phase 1 (default chain +
     * deploymentConfig overlay), inspects the result for a {@code config.stores} declaration,
     * and — if stores are declared — runs phase 2 (full chain with declared stores +
     * deploymentConfig overlay). The temporary Vert.x instance is always closed before
     * returning.
     *
     * <p>{@code null} deploymentConfig is treated as an empty {@link JsonObject}.
     *
     * <p>Failure modes:
     * <ul>
     *   <li>{@link IllegalStateException} — thrown immediately if called from a Vert.x
     *       event-loop or worker thread.</li>
     *   <li>{@link BootstrapConfigException} — load timeout, I/O error, unknown store type,
     *       invalid store or source declaration, or factory instantiation failure (previously
     *       created sources are closed before throwing).</li>
     *   <li>{@link PlaceholderResolutionException} — unresolvable reference in
     *       {@code config.propertySources} (pass 1, "tree references only" message) or in the
     *       full tree (pass 3); all sources are closed before propagating.</li>
     *   <li>{@link ConfigPropertySourceException} — unrecoverable source lookup error during
     *       pass 3; all sources are closed before propagating.</li>
     *   <li>Temp Vert.x close failure — logged at {@code WARN} level and swallowed; never
     *       masks a load success or failure.</li>
     * </ul>
     *
     * @param deploymentConfig caller-supplied overlay config (highest precedence); {@code null}
     *                         is treated as empty
     * @return the bootstrap result with the fully resolved config tree and instantiated sources;
     *         the caller owns source lifecycle from this point (close at shutdown in reverse order)
     * @throws IllegalStateException          if called from a Vert.x event-loop or worker context
     * @throws BootstrapConfigException       if validation fails, the config load fails, or times out
     * @throws PlaceholderResolutionException if placeholder resolution fails in pass 1 or pass 3
     *                                        (propagated without wrapping so the caller sees the
     *                                        full {@code unresolvedReferences} list)
     * @throws ConfigPropertySourceException  if a source signals an unrecoverable lookup error
     *                                        during pass 3 (propagated without wrapping)
     */
    public static BootstrapResult load(JsonObject deploymentConfig) {
        if (Vertx.currentContext() != null) {
            throw new IllegalStateException("BootstrapConfigLoader.load must be called from a non-Vert.x thread");
        }

        JsonObject overlay = deploymentConfig != null ? deploymentConfig : new JsonObject();

        Vertx tempVertx = Vertx.builder()
                .with(new VertxOptions()
                        .setEventLoopPoolSize(1)
                        .setWorkerPoolSize(1)
                        .setInternalBlockingPoolSize(1))
                .build();

        long startMs = System.currentTimeMillis();
        try {
            return doLoad(tempVertx, overlay, startMs);
        } finally {
            // Always close the temp Vertx; awaitClose never throws, so it cannot mask a load failure.
            awaitClose(tempVertx);
        }
    }

    // --- Private helpers ---

    /**
     * Performs the actual phase-1, optional phase-2 config retrieval, and three-pass placeholder
     * resolution. See the class-level javadoc for the full algorithm description.
     *
     * @param tempVertx the temporary Vert.x instance to use for the retriever
     * @param overlay   the deployment config overlay (never {@code null})
     * @param startMs   the wall-clock start time for logging duration
     * @return the bootstrap result with the resolved config tree and instantiated sources
     * @see BootstrapConfigLoader
     */
    private static BootstrapResult doLoad(Vertx tempVertx, JsonObject overlay, long startMs) {
        // --- Phase 1 ---
        JsonObject phase1 = runRetriever(tempVertx, ConfigBootstrap.defaultOptions(), overlay);

        // --- Step A: stores-declaration placeholder pass ---
        // Before validating/building declared stores, resolve the config.stores array against
        // the phase-1 tree (tree-only). Unresolvable source-requiring references fail here.
        JsonObject configSection1 = JsonConfigPaths.navigateObject(phase1, CONFIG_SECTION);
        JsonArray storesArray = configSection1.getJsonArray(STORES_KEY);

        JsonObject mergedTree;
        boolean phase2Ran;
        if (storesArray == null || storesArray.isEmpty()) {
            mergedTree = phase1;
            phase2Ran = false;
        } else {
            // Resolve the stores declaration against the phase-1 tree before building stores
            JsonArray resolvedStoresArray = PlaceholderResolver.resolveAgainstTree(storesArray, phase1);

            // Validate and build declared store options from the resolved array
            List<ConfigStoreOptions> declaredStores = buildDeclaredStores(resolvedStoresArray);

            // Phase 2 reload with declared stores
            mergedTree = runRetriever(tempVertx, ConfigBootstrap.defaultOptions(declaredStores), overlay);
            phase2Ran = true;
        }

        // --- Pass 1: resolve config.propertySources array against the merged tree ---
        JsonArray propertySourcesArray =
                JsonConfigPaths.navigateObject(mergedTree, CONFIG_SECTION).getJsonArray(PROPERTY_SOURCES_KEY);

        JsonArray resolvedSourcesArray;
        if (propertySourcesArray != null && !propertySourcesArray.isEmpty()) {
            resolvedSourcesArray = PlaceholderResolver.resolveAgainstTree(propertySourcesArray, mergedTree);
        } else {
            resolvedSourcesArray = new JsonArray();
        }

        // --- Pass 2: instantiate sources in declared order ---
        List<ConfigPropertySource> sources = instantiateSources(resolvedSourcesArray);

        // --- Pass 3: whole-tree placeholder resolution ---
        JsonObject resolvedTree;
        try {
            resolvedTree = PlaceholderResolver.resolveTree(mergedTree, sources);
        } catch (RuntimeException e) {
            // Close sources in reverse order on failure; propagate original exception.
            // PlaceholderResolutionException and ConfigPropertySourceException are both
            // RuntimeExceptions — rethrow as-is so callers see the full diagnostic.
            ConfigPropertySources.closeAllReverse(sources);
            throw e;
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (phase2Ran) {
            log.info(
                    "Bootstrap configuration loaded (phase 1 + phase 2 with {} declared store(s), {} source(s)):"
                            + " {} keys in {} ms",
                    storesArray.size(),
                    sources.size(),
                    resolvedTree.size(),
                    elapsedMs);
        } else {
            log.info(
                    "Bootstrap configuration loaded (phase 1, {} source(s)): {} keys in {} ms",
                    sources.size(),
                    resolvedTree.size(),
                    elapsedMs);
        }

        return new BootstrapResult(resolvedTree, List.copyOf(sources));
    }

    /**
     * Immutable tuple capturing the validated fields needed to create one {@link ConfigPropertySource}.
     *
     * @param name    the effective instance name (from entry or defaulted to {@code type[index]})
     * @param type    the source type string (validated non-blank and factory-known)
     * @param factory the resolved factory for this type
     * @param config  a defensive copy of the per-source config object to pass to the factory
     */
    private record ValidatedSourceEntry(
            String name, String type, ConfigPropertySourceFactory factory, JsonObject config) {}

    /**
     * Instantiates {@link ConfigPropertySource} instances in declared order from the resolved
     * {@code config.propertySources} array.
     *
     * <p>Uses a <em>validate-first-then-create</em> approach to prevent source leaks on
     * validation errors:
     * <ol>
     *   <li><strong>First pass (validation):</strong> walks every entry in the array and validates
     *       it (non-object element, missing/blank type, unknown type). A validation error throws
     *       {@link BootstrapConfigException} immediately — at this point no source has been
     *       created, so there is nothing to close.</li>
     *   <li><strong>Second pass (creation):</strong> creates sources in declaration order using the
     *       validated tuples from the first pass. On factory failure, closes any already-created
     *       sources in reverse order (log-never-throw) then throws {@link BootstrapConfigException}
     *       naming the instance and type.</li>
     * </ol>
     *
     * <p>Validation rules (checked in the first pass):
     * <ol>
     *   <li>Each element must be a {@link JsonObject}.</li>
     *   <li>The {@code "type"} field must be a non-blank string.</li>
     *   <li>The {@code "type"} value must match a registered {@link ConfigPropertySourceFactory}.</li>
     * </ol>
     *
     * <p>The {@code "name"} field defaults to {@code type + "[" + index + "]"} when absent.
     *
     * @param sourcesArray the resolved {@code config.propertySources} JSON array
     * @return an ordered list of ready-to-use sources
     * @throws BootstrapConfigException if validation fails (first pass) or a factory throws during
     *                                  creation (second pass; previously-created sources are closed
     *                                  before throwing)
     */
    private static List<ConfigPropertySource> instantiateSources(JsonArray sourcesArray) {
        if (sourcesArray == null || sourcesArray.isEmpty()) {
            return List.of();
        }

        // Enumerate available factory types once (used in both passes)
        Map<String, ConfigPropertySourceFactory> factories = enumerateSourceFactories();

        // --- First pass: validate every entry before creating anything ---
        List<ValidatedSourceEntry> validated = new ArrayList<>(sourcesArray.size());
        for (int i = 0; i < sourcesArray.size(); i++) {
            Object rawEntry = sourcesArray.getValue(i);

            if (rawEntry == null) {
                throw new BootstrapConfigException(
                        "config.propertySources[" + i + "] must be a JSON object, but got: null");
            }
            if (!(rawEntry instanceof JsonObject entry)) {
                throw new BootstrapConfigException("config.propertySources[" + i + "] must be a JSON object");
            }

            String type = entry.getString("type");
            if (type == null || type.isBlank()) {
                throw new BootstrapConfigException(
                        "config.propertySources[" + i + "] is missing a non-blank 'type' field");
            }

            String name = entry.getString("name");
            if (name == null || name.isBlank()) {
                name = type + "[" + i + "]";
            }

            ConfigPropertySourceFactory factory = factories.get(type);
            if (factory == null) {
                String hint = SOURCE_DEPENDENCY_HINTS.getOrDefault(
                        type, "add the artifact providing a ConfigPropertySourceFactory of type '" + type + "'");
                throw new BootstrapConfigException(
                        "config.propertySources[" + i + "] declares unknown source type '" + type + "'; " + hint);
            }

            validated.add(new ValidatedSourceEntry(name, type, factory, entry.copy()));
        }

        // --- Second pass: create sources in declaration order ---
        // Validation succeeded for all entries; only factory.create() failures can happen here.
        List<ConfigPropertySource> created = new ArrayList<>(validated.size());
        for (ValidatedSourceEntry entry : validated) {
            try {
                ConfigPropertySource source = entry.factory().create(entry.name(), entry.config());
                created.add(source);
            } catch (Error e) {
                // Even fatal errors must not leak already-created sources (live SDK clients).
                closeSources(created);
                throw e;
            } catch (Exception e) {
                // Close already-created sources in reverse order, then throw
                closeSources(created);
                throw new BootstrapConfigException(
                        "Failed to create property source '" + entry.name() + "' of type '" + entry.type() + "'", e);
            }
        }

        return created;
    }

    /**
     * Closes all sources in reverse declaration order.
     *
     * <p>Delegates to {@link ConfigPropertySources#closeAllReverse(List)}: close failures are
     * logged at {@code ERROR} level and never rethrown, so a load failure is never masked by a
     * close failure.
     *
     * @param sources the sources to close
     */
    private static void closeSources(List<ConfigPropertySource> sources) {
        ConfigPropertySources.closeAllReverse(sources);
    }

    /**
     * Enumerates available {@link ConfigPropertySourceFactory} instances via {@link ServiceLoader},
     * keyed by {@link ConfigPropertySourceFactory#type()}.
     *
     * @return an immutable map from type key to factory instance
     */
    private static Map<String, ConfigPropertySourceFactory> enumerateSourceFactories() {
        return StreamSupport.stream(
                        ServiceLoader.load(ConfigPropertySourceFactory.class).spliterator(), false)
                .collect(Collectors.toUnmodifiableMap(ConfigPropertySourceFactory::type, f -> f));
    }

    /**
     * Runs a single config retrieval cycle and overlays the deployment config.
     *
     * @param tempVertx the temporary Vert.x instance
     * @param options   the retriever options to use
     * @param overlay   the deployment overlay (highest precedence)
     * @return the merged config tree
     * @throws BootstrapConfigException if the retriever fails or times out
     */
    private static JsonObject runRetriever(Vertx tempVertx, ConfigRetrieverOptions options, JsonObject overlay) {
        var retriever = ConfigRetriever.create(tempVertx, options);
        try {
            JsonObject loaded = retriever.getConfig().await(BOOTSTRAP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return loaded.mergeIn(overlay, true);
        } catch (TimeoutException e) {
            throw new BootstrapConfigException(
                    "Bootstrap config load timed out after " + BOOTSTRAP_TIMEOUT_MS + " ms", e);
        } catch (Exception e) {
            if (e instanceof BootstrapConfigException bce) {
                throw bce;
            }
            throw new BootstrapConfigException("Bootstrap config load failed", e);
        } finally {
            retriever.close();
        }
    }

    /**
     * Validates each entry in the {@code config.stores} array and builds
     * {@link ConfigStoreOptions} instances.
     *
     * <p>Validation rules (checked in order):
     * <ol>
     *   <li>Each element must be a {@link JsonObject}.</li>
     *   <li>The {@code "type"} field must be a non-blank string.</li>
     *   <li>The {@code "type"} value must match a registered {@link ConfigStoreFactory}.</li>
     * </ol>
     *
     * <p>Package-private for direct unit testing of validation logic (no temp {@link Vertx} needed).
     *
     * @param storesArray the raw {@code config.stores} JSON array
     * @return an ordered list of validated {@link ConfigStoreOptions}
     * @throws BootstrapConfigException if any entry is invalid or declares an unknown type
     */
    static List<ConfigStoreOptions> buildDeclaredStores(JsonArray storesArray) {
        // Enumerate available store types once
        Set<String> knownTypes = enumerateKnownTypes();

        List<ConfigStoreOptions> result = new ArrayList<>(storesArray.size());

        for (int i = 0; i < storesArray.size(); i++) {
            Object rawEntry = storesArray.getValue(i);

            // Validate: must be a non-null JsonObject
            if (rawEntry == null) {
                throw new BootstrapConfigException("config.stores[" + i + "] must be a JSON object, but got: null");
            }
            if (!(rawEntry instanceof JsonObject entry)) {
                throw new BootstrapConfigException("config.stores[" + i + "] must be a JSON object, but got: "
                        + rawEntry.getClass().getSimpleName());
            }

            // Validate: type must be a non-blank string
            String type = entry.getString("type");
            if (type == null || type.isBlank()) {
                throw new BootstrapConfigException("config.stores[" + i + "] is missing a non-blank 'type' field");
            }

            // Validate: type must be known
            if (!knownTypes.contains(type)) {
                String hint = STORE_DEPENDENCY_HINTS.getOrDefault(
                        type, "add the artifact providing a ConfigStoreFactory named '" + type + "'");
                throw new BootstrapConfigException(
                        "config.stores[" + i + "] declares unknown store type '" + type + "'; " + hint);
            }

            // Build the options
            JsonObject config = entry.getJsonObject("config", new JsonObject());
            String format = entry.getString("format");

            ConfigStoreOptions storeOptions =
                    new ConfigStoreOptions().setType(type).setConfig(config).setOptional(false);

            if (format != null && !format.isBlank()) {
                storeOptions.setFormat(format);
            }

            result.add(storeOptions);
        }

        return List.copyOf(result);
    }

    /**
     * Enumerates available {@link ConfigStoreFactory} names via {@link ServiceLoader}.
     *
     * @return an immutable set of registered factory names
     */
    private static Set<String> enumerateKnownTypes() {
        return StreamSupport.stream(ServiceLoader.load(ConfigStoreFactory.class).spliterator(), false)
                .map(ConfigStoreFactory::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Closes the temporary Vert.x instance and awaits shutdown.
     *
     * <p>Close failures are always logged as warnings but never rethrown, so a valid load result
     * is still returned to the caller and a load failure is never masked by a close failure.
     *
     * @param tempVertx the temporary Vert.x instance to close
     */
    private static void awaitClose(Vertx tempVertx) {
        try {
            tempVertx.close().await(BOOTSTRAP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Temporary bootstrap Vertx did not close within {} ms", BOOTSTRAP_TIMEOUT_MS, e);
        } catch (Exception e) {
            log.warn("Failed to close temporary bootstrap Vertx", e);
        }
    }

    // --- Result record ---

    /**
     * The result of a successful bootstrap load.
     *
     * <p>Ownership of the sources list transfers to the caller on successful return from
     * {@link #load(JsonObject)}: the loader closes sources on any failure path, but the caller
     * (typically the launcher's {@code BootstrapShutdown}) is responsible for closing them at
     * application shutdown.
     *
     * @param config          the fully resolved configuration tree — all {@code ${...}} placeholders
     *                        have been substituted; deploymentConfig overlay wins on key collisions
     * @param propertySources instantiated property sources in declared order; must be closed at
     *                        shutdown in reverse order; non-null, may be empty when no
     *                        {@code config.propertySources} are declared
     */
    public record BootstrapResult(JsonObject config, List<ConfigPropertySource> propertySources) {

        /**
         * Redacted representation: the config tree may carry secrets and must never be
         * stringified wholesale (NFR-CONF-002), so only the key count and source count appear.
         *
         * @return a value-free summary of this result
         */
        @Override
        public String toString() {
            return "BootstrapResult[configKeys=" + config.size() + ", propertySources=" + propertySources.size() + "]";
        }
    }
}
