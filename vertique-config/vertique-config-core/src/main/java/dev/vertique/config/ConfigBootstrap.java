// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap helper for loading configuration before Dagger component creation.
 *
 * <p>Creates a {@link ConfigRetriever}, loads the initial configuration, and merges it
 * with the deployment configuration. The result contains both the merged {@link JsonObject}
 * (for passing to {@link dev.vertique.core.VertxModule}) and the {@link ConfigRetriever}
 * (for passing to {@link ConfigModule}).
 *
 * <p>Usage in a MainVerticle:
 * <pre>{@code
 * ConfigBootstrap.load(vertx, config())
 *     .compose(result -> {
 *         AppComponent app = DaggerAppComponent.builder()
 *             .vertxModule(new VertxModule(vertx, result.config()))
 *             .configModule(new ConfigModule(result.retriever()))
 *             .build();
 *         return vertx.deployVerticle(app.httpVerticle());
 *     })
 *     .onSuccess(id -> startPromise.complete())
 *     .onFailure(startPromise::fail);
 * }</pre>
 *
 * <p>Configuration source precedence (lowest to highest):
 * <ol>
 *   <li>{@code *.json} files from each config directory</li>
 *   <li>{@code *.properties} files from each config directory (hierarchical key expansion)</li>
 *   <li>Environment variables</li>
 *   <li>System properties</li>
 *   <li>Deployment config (CLI {@code --conf} / {@code DeploymentOptions.setConfig()})</li>
 * </ol>
 *
 * <p>Config directories default to {@code config/}. Override with the
 * {@value #CONFIG_LOCATIONS_ENV} environment variable (comma-separated list of directories).
 * Later directories override earlier ones. Within each directory, {@code *.properties}
 * overrides {@code *.json}.
 *
 * <p>The {@link #load(Vertx, JsonObject)} and {@link #load(Vertx, JsonObject, ConfigRetrieverOptions)}
 * methods are deprecated for the launcher-mode bootstrap path — applications launched via
 * {@link dev.vertique.launcher.VertiqueApplication} receive the resolved configuration tree as
 * their deployment config and no longer call {@code load} directly. The
 * {@link #defaultOptions()} and {@link #defaultOptions(List)} helpers remain available and are
 * used internally by {@link dev.vertique.config.bootstrap.BootstrapConfigLoader}.
 */
public final class ConfigBootstrap {

    /** Env variable for overriding config directory locations (comma-separated). */
    public static final String CONFIG_LOCATIONS_ENV = "VERTX_CONFIG_LOCATIONS";

    /** Default config directory when {@value #CONFIG_LOCATIONS_ENV} is not set. */
    public static final String DEFAULT_CONFIG_DIR = "config";

    private static final Logger log = LoggerFactory.getLogger(ConfigBootstrap.class);

    private ConfigBootstrap() {}

    /**
     * Result of the bootstrap configuration load.
     *
     * @param config    the merged configuration (retriever sources + deployment config overlay)
     * @param retriever the {@link ConfigRetriever} for runtime change listening
     */
    public record Result(JsonObject config, ConfigRetriever retriever) {}

    /**
     * Loads configuration using default options and merges with deployment config.
     *
     * @param vertx            the Vert.x instance
     * @param deploymentConfig the verticle deployment configuration (may be empty)
     * @return a future containing the bootstrap result
     * @deprecated Use {@link dev.vertique.launcher.VertiqueApplication} to launch the
     *     application; the resolved configuration tree is passed as deployment config
     *     automatically. For manual bootstrap, use
     *     {@link dev.vertique.config.bootstrap.BootstrapConfigLoader} instead.
     */
    @Deprecated
    public static Future<Result> load(Vertx vertx, JsonObject deploymentConfig) {
        return load(vertx, deploymentConfig, defaultOptions());
    }

    /**
     * Loads configuration using custom options and merges with deployment config.
     *
     * <p>The deployment config is merged last with highest priority, ensuring that
     * values passed via CLI {@code --conf} or {@code DeploymentOptions.setConfig()}
     * always override retriever sources.
     *
     * @param vertx            the Vert.x instance
     * @param deploymentConfig the verticle deployment configuration
     * @param options          custom {@link ConfigRetrieverOptions}
     * @return a future containing the bootstrap result
     * @deprecated Use {@link dev.vertique.launcher.VertiqueApplication} to launch the
     *     application; the resolved configuration tree is passed as deployment config
     *     automatically. For manual bootstrap, use
     *     {@link dev.vertique.config.bootstrap.BootstrapConfigLoader} instead.
     */
    @Deprecated
    public static Future<Result> load(Vertx vertx, JsonObject deploymentConfig, ConfigRetrieverOptions options) {
        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);
        return retriever
                .getConfig()
                .map(retrievedConfig -> {
                    JsonObject merged = retrievedConfig.copy().mergeIn(deploymentConfig, true);
                    log.info(
                            "Configuration loaded: {} keys from retriever, {} from deployment",
                            retrievedConfig.size(),
                            deploymentConfig.size());
                    return new Result(merged, retriever);
                })
                .onFailure(cause -> log.error("Failed to load configuration", cause));
    }

    /**
     * Returns the default {@link ConfigRetrieverOptions}.
     *
     * <p>For each config directory (from {@value #CONFIG_LOCATIONS_ENV} or the default
     * {@value #DEFAULT_CONFIG_DIR}), two directory stores are added:
     * <ol>
     *   <li>{@code *.json} files (optional)</li>
     *   <li>{@code *.properties} files with hierarchical key expansion (optional)</li>
     * </ol>
     * Followed by environment variable and system property stores.
     *
     * <p>Delegates to {@link #defaultOptions(List)} with an empty declared-store list.
     * Applications can override by passing custom options to
     * {@link #load(Vertx, JsonObject, ConfigRetrieverOptions)}.
     *
     * @return the default retriever options
     */
    public static ConfigRetrieverOptions defaultOptions() {
        return defaultOptions(List.of());
    }

    /**
     * Returns the default {@link ConfigRetrieverOptions} with additional declared stores
     * inserted between the file-directory stores and the env/sys stores.
     *
     * <p>The full store chain (lowest to highest priority):
     * <ol>
     *   <li>{@code *.json} files from each config directory (optional)</li>
     *   <li>{@code *.properties} files from each config directory with hierarchical key
     *       expansion (optional)</li>
     *   <li>Each declared store, in the order provided (later entries override earlier ones
     *       per Vert.x merge semantics)</li>
     *   <li>Environment variables</li>
     *   <li>System properties</li>
     * </ol>
     *
     * <p>Passing an empty list produces the same chain as {@link #defaultOptions()}.
     *
     * @param declaredStores additional stores to insert after the last directory store and
     *                       before the env store; order is preserved; must not be {@code null}
     * @return the default retriever options with declared stores inserted
     */
    public static ConfigRetrieverOptions defaultOptions(List<ConfigStoreOptions> declaredStores) {
        List<String> configDirs = resolveConfigDirs();
        ConfigRetrieverOptions options = new ConfigRetrieverOptions();
        for (String dir : configDirs) {
            // *.json from this directory (lower priority within dir)
            options.addStore(new ConfigStoreOptions()
                    .setType("directory")
                    .setOptional(true)
                    .setConfig(new JsonObject()
                            .put("path", dir)
                            .put(
                                    "filesets",
                                    new JsonArray()
                                            .add(new JsonObject()
                                                    .put("pattern", "*.json")
                                                    .put("format", "json")))));
            // *.properties from this directory (higher priority within dir, hierarchical)
            options.addStore(new ConfigStoreOptions()
                    .setType("directory")
                    .setOptional(true)
                    .setConfig(new JsonObject()
                            .put("path", dir)
                            .put(
                                    "filesets",
                                    new JsonArray()
                                            .add(new JsonObject()
                                                    .put("pattern", "*.properties")
                                                    .put("format", "properties")
                                                    .put("hierarchical", true)))));
        }
        // declared stores go between the directory stores and the env/sys stores
        declaredStores.forEach(options::addStore);
        // env vars and system properties always last (highest of the retriever stores)
        options.addStore(new ConfigStoreOptions().setType("env"));
        options.addStore(new ConfigStoreOptions().setType("sys"));
        return options;
    }

    /**
     * Resolves the list of config directories to scan.
     *
     * <p>If the {@value #CONFIG_LOCATIONS_ENV} environment variable is set, its value is
     * split on commas and each trimmed, non-empty segment is used as a directory path.
     * Otherwise, the single default directory {@value #DEFAULT_CONFIG_DIR} is returned.
     *
     * @return an ordered list of config directory paths (never null, never empty)
     */
    private static List<String> resolveConfigDirs() {
        String locations = System.getenv(CONFIG_LOCATIONS_ENV);
        if (locations != null && !locations.isBlank()) {
            return Arrays.stream(locations.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return List.of(DEFAULT_CONFIG_DIR);
    }
}
