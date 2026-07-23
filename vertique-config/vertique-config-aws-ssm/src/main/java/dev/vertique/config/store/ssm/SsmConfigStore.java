// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.store.ssm;

import io.vertx.config.spi.ConfigStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * Vert.x {@link ConfigStore} that fetches parameters from AWS SSM Parameter Store.
 *
 * <p>Each call to {@link #get()} issues paginated {@code GetParametersByPath} SDK calls
 * (via {@link SsmGateway}) inside {@link Vertx#executeBlocking(java.util.concurrent.Callable)}
 * to avoid blocking the event loop, then maps the parameter names to a nested
 * {@link JsonObject} that is serialized to a {@link Buffer} for the Vert.x
 * {@link io.vertx.config.ConfigRetriever}.
 *
 * <h2>Parameter-Name to Nested-Key Mapping</h2>
 * <p>The configured {@code path} prefix is stripped from each parameter name, and the
 * remainder is split on {@code "/"} to produce nested JSON keys. For example:
 * <ul>
 *   <li>Path: {@code "/myapp/prod/"}</li>
 *   <li>Parameter: {@code "/myapp/prod/db/password"}</li>
 *   <li>Result key: {@code {"db":{"password":"..."}}}</li>
 * </ul>
 *
 * <h2>Parameter Types</h2>
 * <ul>
 *   <li>{@code String} and {@code SecureString} → stored as a JSON string value.</li>
 *   <li>{@code StringList} → stored as a {@link JsonArray} of comma-split strings.</li>
 * </ul>
 *
 * <h2>Optional {@code prefix} Re-rooting</h2>
 * <p>If the {@link SsmStoreSettings#prefix()} is set, the entire result is nested under that
 * dot-split path. For example, {@code "prefix": "app.db"} nests everything under
 * {@code {"app":{"db":{...}}}}.
 *
 * <h2>Empty Path Semantics</h2>
 * <p>An empty parameter list (no parameters found under the configured path) returns a
 * succeeded {@link Future} carrying an empty {@link JsonObject}. This is intentional — a
 * declared-but-empty path is not an error; the store contributes nothing to the merged tree.
 *
 * <h2>NFR-CONF-002</h2>
 * <p>Parameter values MUST NOT appear in log messages, exception messages, or any other
 * observable output. Only parameter names, counts, and structural diagnostics are logged.
 *
 * <p>This class is package-private.
 */
class SsmConfigStore implements ConfigStore {

    private static final Logger LOG = LoggerFactory.getLogger(SsmConfigStore.class);

    private final Vertx vertx;
    private final SsmStoreSettings settings;
    private final SsmGateway gateway;

    // --- Construction ---

    /**
     * Constructs a store.
     *
     * @param vertx    the Vert.x instance used for {@code executeBlocking}
     * @param settings the validated store configuration
     * @param gateway  the SSM gateway; holds the AWS SDK client
     */
    SsmConfigStore(Vertx vertx, SsmStoreSettings settings, SsmGateway gateway) {
        this.vertx = vertx;
        this.settings = settings;
        this.gateway = gateway;
    }

    // --- ConfigStore ---

    /**
     * {@inheritDoc}
     *
     * <p>Fetches all parameters under the configured path, maps them to a nested
     * {@link JsonObject}, applies the optional prefix re-root, and returns the result
     * as a JSON-encoded {@link Buffer}.
     *
     * <p>The SDK calls are issued inside {@link Vertx#executeBlocking(java.util.concurrent.Callable)}
     * to keep the event loop unblocked.
     *
     * @return a {@link Future} completed with the parameter tree as a JSON buffer;
     *         failed if the AWS SDK throws
     */
    @Override
    public Future<Buffer> get() {
        return vertx.executeBlocking(() -> {
            List<Parameter> parameters =
                    gateway.fetchAll(settings.path(), settings.recursive(), settings.withDecryption());

            JsonObject result = buildParameterTree(parameters, settings.path());

            if (settings.prefix() != null) {
                result = applyPrefix(result, settings.prefix());
            }

            LOG.info("AWS SSM store: loaded {} parameter(s) from path '{}'", parameters.size(), settings.path());

            return Buffer.buffer(result.encode());
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closes the underlying {@link SsmGateway} (and its AWS SDK client), releasing
     * connection pool resources. The SDK client's {@link AutoCloseable#close()} is dispatched
     * via {@link Vertx#executeBlocking(java.util.concurrent.Callable)} so that event-loop
     * callers are not blocked. Close errors are logged but do not fail the returned future.
     *
     * @return a {@link Future} that completes (always succeeded) once the gateway is closed;
     *         close failures are logged and swallowed
     */
    @Override
    public Future<Void> close() {
        if (!(gateway instanceof AutoCloseable)) {
            return Future.succeededFuture();
        }
        AutoCloseable closeable = (AutoCloseable) gateway;
        return vertx.<Void>executeBlocking(() -> {
                    try {
                        closeable.close();
                    } catch (Exception e) {
                        LOG.warn(
                                "AWS SSM store: error closing gateway for path '{}': {}",
                                settings.path(),
                                e.getMessage());
                    }
                    return null;
                })
                .recover(t -> {
                    LOG.warn("AWS SSM store: executeBlocking failed during close for path '{}'", settings.path(), t);
                    return Future.succeededFuture();
                });
    }

    // --- Internal helpers ---

    /**
     * Maps a list of SSM parameters to a nested {@link JsonObject} by stripping the path
     * prefix from each parameter name and splitting the remainder on {@code "/"}.
     *
     * @param parameters the parameters returned by the SDK
     * @param path       the configured path prefix (always ends with {@code "/"})
     * @return the nested parameter tree; never {@code null}; may be empty
     */
    private static JsonObject buildParameterTree(List<Parameter> parameters, String path) {
        JsonObject root = new JsonObject();

        for (Parameter parameter : parameters) {
            String name = parameter.name();
            // Strip the leading path prefix — path always ends with "/"
            String relative = name.startsWith(path) ? name.substring(path.length()) : name;
            if (relative.isBlank()) {
                // Parameter name equals the path itself — skip
                continue;
            }

            String[] segments = relative.split("/", -1);
            Object value = toJsonValue(parameter);
            putNested(root, segments, value, name);
        }

        return root;
    }

    /**
     * Converts a single {@link Parameter} to its JSON representation.
     *
     * <ul>
     *   <li>{@code StringList} → {@link JsonArray} of comma-split strings</li>
     *   <li>{@code String} / {@code SecureString} → the raw string value</li>
     * </ul>
     *
     * @param parameter the SSM parameter
     * @return the JSON-compatible value (String or JsonArray)
     */
    private static Object toJsonValue(Parameter parameter) {
        if (parameter.type() == ParameterType.STRING_LIST) {
            JsonArray array = new JsonArray();
            Arrays.stream(parameter.value().split(",", -1)).map(String::trim).forEach(array::add);
            return array;
        }
        return parameter.value();
    }

    /**
     * Recursively descends into {@code root} along {@code segments} and sets the leaf value.
     *
     * <p>Intermediate objects are created as needed.
     *
     * <h3>Name-Conflict Semantics</h3>
     * <p>Two conflict directions are possible and both are silent last-write-wins (SSM does not
     * guarantee delivery order for parameters in the same subtree):
     * <ul>
     *   <li><b>Leaf-after-subtree:</b> a parameter whose name maps to an intermediate key in a
     *       previously processed parameter's path overwrites that subtree node with a leaf value,
     *       discarding everything nested below it.</li>
     *   <li><b>Subtree-after-leaf:</b> a parameter whose path descends through a key that was
     *       previously set as a leaf value replaces that leaf with a new intermediate
     *       {@link JsonObject}.</li>
     * </ul>
     * <p>Both directions emit a WARN log naming the conflicting parameter name (safe to log —
     * it is an operator-declared path segment, not a value). Parameter values are never logged.
     * The order in which SSM returns parameters within a single {@code GetParametersByPath}
     * response is unspecified; callers must not rely on conflict resolution ordering.
     *
     * @param root          the object to write into
     * @param segments      the key path segments (at least one element)
     * @param value         the value to set at the leaf
     * @param parameterName the original SSM parameter name (for conflict warning logging; safe)
     */
    private static void putNested(JsonObject root, String[] segments, Object value, String parameterName) {
        JsonObject current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            Object existing = current.getValue(segment);
            if (existing instanceof JsonObject existingObj) {
                current = existingObj;
            } else {
                if (existing != null) {
                    // subtree-after-leaf conflict: a prior parameter set this key as a leaf;
                    // descending through it now replaces it with an intermediate object
                    LOG.warn(
                            "AWS SSM store: name conflict at key '{}' for parameter '{}' — "
                                    + "subtree-after-leaf: replacing leaf with intermediate object",
                            buildPath(segments, i),
                            parameterName);
                }
                JsonObject child = new JsonObject();
                current.put(segment, child);
                current = child;
            }
        }
        String leafKey = segments[segments.length - 1];
        Object existingLeaf = current.getValue(leafKey);
        if (existingLeaf instanceof JsonObject) {
            // leaf-after-subtree conflict: a prior parameter established a subtree here;
            // writing a leaf now discards that subtree
            LOG.warn(
                    "AWS SSM store: name conflict at key '{}' for parameter '{}' — "
                            + "leaf-after-subtree: discarding existing subtree",
                    buildPath(segments, segments.length - 1),
                    parameterName);
        }
        current.put(leafKey, value);
    }

    /**
     * Builds a slash-joined path string from the first {@code upTo} segments (inclusive).
     *
     * @param segments the path segments
     * @param upTo     the index of the last segment to include (0-based)
     * @return slash-joined path string for logging
     */
    private static String buildPath(String[] segments, int upTo) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= upTo; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(segments[i]);
        }
        return sb.toString();
    }

    /**
     * Nests {@code result} under the dot-split {@code prefix} path.
     *
     * <p>For example, {@code prefix = "app.db"} and {@code result = {"password":"..."}} yields
     * {@code {"app":{"db":{"password":"..."}}}}.
     *
     * @param result the parameter tree to nest
     * @param prefix the dot-separated prefix (e.g. {@code "app"} or {@code "app.db"})
     * @return the re-rooted object
     */
    private static JsonObject applyPrefix(JsonObject result, String prefix) {
        String[] parts = prefix.split("\\.", -1);
        // Build from the inside out
        JsonObject wrapped = result;
        for (int i = parts.length - 1; i >= 0; i--) {
            wrapped = new JsonObject().put(parts[i], wrapped);
        }
        return wrapped;
    }
}
