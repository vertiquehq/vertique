// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import dev.vertique.config.source.SourceConfigValues;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * {@link ConfigPropertySourceFactory} for HashiCorp Vault KV v2.
 *
 * <p>This factory is discovered via {@link java.util.ServiceLoader} (type key: {@code "vault"})
 * and creates eagerly-loaded {@link VaultPropertySource} instances from the per-source
 * configuration block.
 *
 * <h2>Configuration Schema</h2>
 * <p>The {@code sourceConfig} JsonObject (the full entry from {@code config.propertySources[*]})
 * must contain the following fields:
 *
 * <pre>{@code
 * {
 *   "type": "vault",
 *   "address": "http://vault:8200",           // required; Vault base URL
 *   "namespace": "my-ns",                     // optional; Vault Enterprise namespace
 *   "auth": {
 *     "method": "token",                      // "token" | "kubernetes" | "approle"
 *     "token": "s.mytoken"                    // required for "token" unless VAULT_TOKEN env is set
 *   },
 *   "paths": [
 *     { "path": "secret/app", "prefix": "app." }  // "prefix" optional; defaults to ""
 *   ],
 *   "openTimeoutMs": 5000,                    // optional; default 5000 ms
 *   "readTimeoutMs": 5000                     // optional; default 5000 ms
 * }
 * }</pre>
 *
 * <h3>Auth methods</h3>
 * <ul>
 *   <li><strong>token</strong>: {@code token} field in the {@code auth} object; or omit the field
 *       to have the framework resolve the {@code VAULT_TOKEN} environment variable at create time.
 *       If the env var is also absent, startup fails with a clear error — the Vault CLI token file
 *       {@code ~/.vault-token} is never consulted.
 *       If {@code auth} is absent entirely, token-from-{@code VAULT_TOKEN} is assumed.</li>
 *   <li><strong>kubernetes</strong>: {@code role} (required); {@code jwtPath} (optional, default
 *       {@code /var/run/secrets/kubernetes.io/serviceaccount/token})</li>
 *   <li><strong>approle</strong>: {@code roleId} (required) and {@code secretId} (required)</li>
 * </ul>
 *
 * <h2>Timeouts</h2>
 * <p>The jopenlibs driver accepts timeouts in integer seconds. {@code openTimeoutMs} and
 * {@code readTimeoutMs} are in milliseconds (matching the framework naming convention) and are
 * converted via {@code Math.max(1, ceil(ms / 1000.0))} at build time, so sub-1000ms values map
 * to 1 second (the driver treats zero as "no timeout").
 *
 * <h2>Path Collision Resolution</h2>
 * <p>When two paths produce the same flattened (prefixed) key, the later path in the declaration
 * order wins.
 *
 * <h2>Redaction</h2>
 * <p>Error messages include field names and structural descriptions. They MUST NOT include any
 * resolved secret value. The bad auth {@code method} string is safe to echo because it is a
 * structural identifier, not a secret.
 *
 * @see VaultPropertySource
 */
public class VaultPropertySourceFactory implements ConfigPropertySourceFactory {

    private static final String DEFAULT_KUBERNETES_JWT_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    /** Name of the environment variable consulted for token auth when no explicit token is configured. */
    private static final String VAULT_TOKEN_ENV_VAR = "VAULT_TOKEN";

    private final Function<VaultConnectionSettings, VaultGateway> gatewayFactory;

    /**
     * Environment variable lookup function.
     *
     * <p>Defaults to {@link System#getenv}. Package-private seam allows unit tests to inject a
     * controlled environment without spawning processes or mutating the real process environment.
     */
    private final UnaryOperator<String> envLookup;

    // --- Constructors ---

    /**
     * Public no-arg constructor used in production (registered via ServiceLoader).
     *
     * <p>Uses {@link JOpenLibsVaultGateway} as the gateway implementation and {@link System#getenv}
     * for environment variable resolution.
     */
    public VaultPropertySourceFactory() {
        this(JOpenLibsVaultGateway::new, System::getenv);
    }

    /**
     * Package-private constructor for unit tests: custom gateway factory only.
     *
     * <p>Uses {@link System#getenv} for environment variable resolution. Provided for backward
     * compatibility with existing tests that do not need to control the env seam.
     *
     * @param gatewayFactory function that produces a {@link VaultGateway} from connection settings
     */
    VaultPropertySourceFactory(Function<VaultConnectionSettings, VaultGateway> gatewayFactory) {
        this(gatewayFactory, System::getenv);
    }

    /**
     * Package-private constructor for unit tests: custom gateway factory and env seam.
     *
     * <p>Allows tests to control both the gateway and the environment variable lookup so that
     * VAULT_TOKEN resolution can be tested without mutating the real process environment.
     *
     * @param gatewayFactory function that produces a {@link VaultGateway} from connection settings
     * @param envLookup      function that resolves an environment variable by name; called with
     *                       {@value #VAULT_TOKEN_ENV_VAR} when token auth has no explicit token
     */
    VaultPropertySourceFactory(
            Function<VaultConnectionSettings, VaultGateway> gatewayFactory, UnaryOperator<String> envLookup) {
        this.gatewayFactory = gatewayFactory;
        this.envLookup = envLookup;
    }

    // --- ConfigPropertySourceFactory ---

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "vault";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validates the configuration schema, builds a {@link VaultConnectionSettings}, creates the
     * gateway (which authenticates against Vault), and constructs a {@link VaultPropertySource}
     * (which eagerly reads all declared paths).
     *
     * @throws ConfigPropertySourceException if schema validation fails or if authentication or any
     *                                        path read fails during eager load
     */
    @Override
    public ConfigPropertySource create(String name, JsonObject sourceConfig) {
        VaultSourceSettings settings = parseSettings(name, sourceConfig);
        VaultGateway gateway = gatewayFactory.apply(settings.connection());
        return new VaultPropertySource(name, gateway, settings.paths());
    }

    // --- Schema parsing and validation ---

    /**
     * Parses and validates the {@code sourceConfig} JSON object into a {@link VaultSourceSettings}.
     *
     * @param name         the source instance name for error messages
     * @param sourceConfig the per-source configuration object
     * @return validated source settings including connection and path list
     * @throws ConfigPropertySourceException if any required field is missing or invalid
     */
    private VaultSourceSettings parseSettings(String name, JsonObject sourceConfig) {
        // --- address ---
        String address = requireNonBlank(name, "address", sourceConfig.getString("address"));

        // --- namespace (optional) ---
        String namespace = sourceConfig.getString("namespace");

        // --- timeouts ---
        int openTimeoutMs = SourceConfigValues.positiveInt(name, sourceConfig, "openTimeoutMs", DEFAULT_TIMEOUT_MS);
        int readTimeoutMs = SourceConfigValues.positiveInt(name, sourceConfig, "readTimeoutMs", DEFAULT_TIMEOUT_MS);

        // --- auth ---
        JsonObject authObj = sourceConfig.getJsonObject("auth");
        VaultAuthSettings authSettings = parseAuth(name, authObj, envLookup);

        // --- paths ---
        JsonArray pathsArray = sourceConfig.getJsonArray("paths");
        if (pathsArray == null || pathsArray.isEmpty()) {
            throw new ConfigPropertySourceException(name, "required field 'paths' is missing or empty");
        }
        List<VaultPathEntry> paths = parsePaths(name, pathsArray);

        VaultConnectionSettings connection =
                new VaultConnectionSettings(address, namespace, authSettings, openTimeoutMs, readTimeoutMs);
        return new VaultSourceSettings(connection, paths);
    }

    /**
     * Parses the {@code auth} configuration object and performs framework-owned token resolution.
     *
     * <p>For token auth (method absent or {@code "token"}) with no explicit {@code token} field,
     * the framework resolves {@value #VAULT_TOKEN_ENV_VAR} via {@code envLookup}. If the env var
     * is absent or blank, a {@link ConfigPropertySourceException} is thrown immediately — the Vault
     * driver's ambient resolution ({@code ~/.vault-token}, etc.) is never engaged.
     *
     * @param name      the source instance name for error messages
     * @param authObj   the {@code auth} JSON object; may be {@code null}
     * @param envLookup function for resolving environment variables; {@code null} return means absent
     * @return parsed auth settings with a non-null, non-blank token for token auth
     * @throws ConfigPropertySourceException if the auth method is unknown, required fields are
     *                                        missing, or token auth has no token and {@code VAULT_TOKEN}
     *                                        is absent from the environment
     */
    private static VaultAuthSettings parseAuth(String name, JsonObject authObj, UnaryOperator<String> envLookup) {
        if (authObj == null) {
            // No auth block: token auth via VAULT_TOKEN env var (framework-resolved)
            return resolveTokenAuth(name, null, envLookup);
        }

        String method = authObj.getString("method", "token");
        return switch (method) {
            case "token" -> resolveTokenAuth(name, authObj.getString("token"), envLookup);
            case "kubernetes" -> parseKubernetesAuth(name, authObj);
            case "approle" -> parseAppRoleAuth(name, authObj);
            default ->
                throw new ConfigPropertySourceException(
                        name, "unknown auth method '" + method + "'; supported: token, kubernetes, approle");
        };
    }

    /**
     * Resolves the token for token-based authentication.
     *
     * <p>Resolution order: explicit {@code configToken} (non-null, non-blank) wins; otherwise the
     * framework reads {@value #VAULT_TOKEN_ENV_VAR} via {@code envLookup}. If neither is present,
     * a {@link ConfigPropertySourceException} is thrown — the driver's {@code ~/.vault-token}
     * fallback is never engaged.
     *
     * @param name        the source instance name for error messages
     * @param configToken the token from the source config; {@code null} if absent
     * @param envLookup   environment variable lookup function
     * @return a {@link VaultAuthSettings.Token} with a non-null, non-blank token
     * @throws ConfigPropertySourceException if no token is available (config and env both absent)
     */
    private static VaultAuthSettings.Token resolveTokenAuth(
            String name, String configToken, UnaryOperator<String> envLookup) {
        if (configToken != null && !configToken.isBlank()) {
            return new VaultAuthSettings.Token(configToken);
        }
        String envToken = envLookup.apply(VAULT_TOKEN_ENV_VAR);
        if (envToken == null || envToken.isBlank()) {
            throw new ConfigPropertySourceException(
                    name,
                    "vault source '"
                            + name
                            + "' requires authentication: set auth.token, "
                            + "configure auth.method kubernetes/approle, or export VAULT_TOKEN");
        }
        return new VaultAuthSettings.Token(envToken);
    }

    /**
     * Parses Kubernetes auth configuration.
     *
     * @param name    the source instance name for error messages
     * @param authObj the {@code auth} JSON object
     * @return parsed Kubernetes auth settings
     * @throws ConfigPropertySourceException if {@code role} is missing or blank
     */
    private static VaultAuthSettings.Kubernetes parseKubernetesAuth(String name, JsonObject authObj) {
        String role = requireNonBlank(name, "auth.role", authObj.getString("role"));
        String jwtPath = authObj.getString("jwtPath", DEFAULT_KUBERNETES_JWT_PATH);
        return new VaultAuthSettings.Kubernetes(role, jwtPath);
    }

    /**
     * Parses AppRole auth configuration.
     *
     * @param name    the source instance name for error messages
     * @param authObj the {@code auth} JSON object
     * @return parsed AppRole auth settings
     * @throws ConfigPropertySourceException if {@code roleId} or {@code secretId} is missing or blank
     */
    private static VaultAuthSettings.AppRole parseAppRoleAuth(String name, JsonObject authObj) {
        String roleId = requireNonBlank(name, "auth.roleId", authObj.getString("roleId"));
        String secretId = requireNonBlank(name, "auth.secretId", authObj.getString("secretId"));
        return new VaultAuthSettings.AppRole(roleId, secretId);
    }

    /**
     * Parses the {@code paths} JSON array into an ordered list of {@link VaultPathEntry} records.
     *
     * @param name       the source instance name for error messages
     * @param pathsArray the paths JSON array
     * @return ordered list of path entries
     * @throws ConfigPropertySourceException if any path entry has a missing or blank {@code path}
     *                                        field
     */
    private static List<VaultPathEntry> parsePaths(String name, JsonArray pathsArray) {
        List<VaultPathEntry> result = new ArrayList<>(pathsArray.size());
        for (int i = 0; i < pathsArray.size(); i++) {
            JsonObject entry = pathsArray.getJsonObject(i);
            String path = entry.getString("path");
            if (path == null || path.isBlank()) {
                throw new ConfigPropertySourceException(
                        name, "path entry at index " + i + " has missing or blank 'path' field");
            }
            String prefix = entry.getString("prefix", "");
            result.add(new VaultPathEntry(path, prefix));
        }
        return List.copyOf(result);
    }

    // --- Validation helpers ---

    /**
     * Asserts that a string field is non-null and non-blank, throwing a create-time
     * {@link ConfigPropertySourceException} if it is not.
     *
     * @param sourceName the source instance name for the exception message
     * @param field      the config field name (used in the error message)
     * @param value      the field value to validate
     * @return {@code value} if non-null and non-blank
     * @throws ConfigPropertySourceException if {@code value} is {@code null} or blank
     */
    private static String requireNonBlank(String sourceName, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigPropertySourceException(sourceName, "required field '" + field + "' is missing or blank");
        }
        return value;
    }
}
