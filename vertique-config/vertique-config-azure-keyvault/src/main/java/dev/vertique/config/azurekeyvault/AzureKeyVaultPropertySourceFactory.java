// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import dev.vertique.config.source.SourceConfigValues;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * {@link ConfigPropertySourceFactory} for Azure Key Vault.
 *
 * <p>This factory is discovered via {@link java.util.ServiceLoader} (type key:
 * {@code "azure-keyvault"}) and creates on-demand {@link AzureKeyVaultPropertySource} instances
 * from the per-source configuration block.
 *
 * <h2>Configuration Schema</h2>
 * <p>The {@code sourceConfig} JsonObject (the full entry from
 * {@code config.propertySources[*]}) supports the following fields:
 *
 * <pre>{@code
 * {
 *   "type": "azure-keyvault",
 *   "endpoint": "https://myvault.vault.azure.net",  // required, non-blank
 *   "prefix": "db.",                                 // optional; keys not starting with it are skipped
 *   "auth": {                                        // optional; default: {"method":"default"}
 *     "method": "default",                           // "default" or "managed-identity"
 *     "clientId": "my-client-id"                    // optional
 *   },
 *   "connectTimeoutMs": 5000,                        // optional; default 5000 ms; must be > 0
 *   "readTimeoutMs": 5000                            // optional; default 5000 ms; must be > 0
 * }
 * }</pre>
 *
 * <h2>Auth Methods</h2>
 * <ul>
 *   <li><strong>{@code "default"}</strong> (default when {@code auth} is absent or
 *       {@code method} is {@code "default"}) — uses {@link com.azure.identity.DefaultAzureCredentialBuilder}.
 *       With an optional {@code clientId}, narrows managed identity selection via
 *       {@link com.azure.identity.DefaultAzureCredentialBuilder#managedIdentityClientId(String)}.</li>
 *   <li><strong>{@code "managed-identity"}</strong> — uses
 *       {@link com.azure.identity.ManagedIdentityCredentialBuilder}. With an optional
 *       {@code clientId}, selects a specific user-assigned managed identity via
 *       {@link com.azure.identity.ManagedIdentityCredentialBuilder#clientId(String)}.</li>
 * </ul>
 * <p>Any other {@code method} value is a create-time error that names the invalid method.
 *
 * <h2>On-Demand Lookup</h2>
 * <p>Unlike the eager-load AWS Secrets source, this source issues one {@code getSecret} call per
 * distinct placeholder key during bootstrap. The bootstrap engine memoizes per
 * {@code (source, key)}, so each secret is fetched at most once. The
 * {@link com.azure.security.keyvault.secrets.SecretClient} is built once at create time; auth
 * errors only surface at the first vault call, not at startup.
 *
 * <h2>Timeouts</h2>
 * <p>{@code connectTimeoutMs} and {@code readTimeoutMs} are in milliseconds (matching the
 * framework naming convention) and are applied via
 * {@link com.azure.core.util.HttpClientOptions} to the underlying Netty HTTP client.
 *
 * <h2>Credentials</h2>
 * <p>Credentials are resolved via the Azure SDK credential chain. They are never configured
 * inline and never appear in error messages.
 *
 * <h2>Redaction</h2>
 * <p>Error messages include field names and structural descriptions. They MUST NOT include any
 * resolved secret value or credentials.
 *
 * @see AzureKeyVaultPropertySource
 */
public class AzureKeyVaultPropertySourceFactory implements ConfigPropertySourceFactory {

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    /**
     * Normalized host forms exempted from the https-only requirement.
     *
     * <p>These are test-double addresses where the SDK cannot obtain a CA-trusted certificate.
     * {@code localhost}, {@code 127.0.0.1}, and {@code ::1} are recognised loopback addresses;
     * any host ending with {@code ".localhost"} is a sub-domain that DNS resolves to the loopback
     * address (per RFC 2606 and modern OS resolver rules) and is used by containers like Lowkey
     * Vault that run inside a local test harness.
     *
     * <p>Values are in their normalised form (lowercase, IPv6 brackets stripped) because the
     * incoming host string from {@link URI#getHost()} is lowercased and de-bracketed before
     * comparison via {@link #normaliseHost(String)}.
     */
    private static final Set<String> HTTP_ALLOWED_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private final BiFunction<String, AzureConnectionSettings, KeyVaultGateway> gatewayFactory;

    // --- Constructors ---

    /**
     * Public no-arg constructor used in production (registered via ServiceLoader).
     *
     * <p>Uses {@link SdkKeyVaultGateway} as the gateway implementation.
     */
    public AzureKeyVaultPropertySourceFactory() {
        this(SdkKeyVaultGateway::new);
    }

    /**
     * Package-private constructor for unit tests.
     *
     * <p>Accepts a custom gateway factory so tests can inject a stub without a real Azure
     * endpoint. The factory receives the source instance name and the fully-parsed connection
     * settings; the name is carried into the gateway for accurate error reporting.
     *
     * @param gatewayFactory function that produces a {@link KeyVaultGateway} from source name
     *                       and connection settings
     */
    AzureKeyVaultPropertySourceFactory(BiFunction<String, AzureConnectionSettings, KeyVaultGateway> gatewayFactory) {
        this.gatewayFactory = gatewayFactory;
    }

    // --- ConfigPropertySourceFactory ---

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "azure-keyvault";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validates the configuration schema, builds an {@link AzureConnectionSettings},
     * creates the gateway via the gateway factory, and returns an
     * {@link AzureKeyVaultPropertySource} for on-demand lookup.
     *
     * @throws ConfigPropertySourceException if schema validation fails (missing or blank
     *                                        {@code endpoint}, unknown {@code auth.method},
     *                                        or invalid timeout values)
     */
    @Override
    public ConfigPropertySource create(String name, JsonObject sourceConfig) {
        AzureConnectionSettings settings = parseSettings(name, sourceConfig);
        return new AzureKeyVaultPropertySource(name, settings, gatewayFactory);
    }

    // --- Schema parsing and validation ---

    /**
     * Parses and validates the {@code sourceConfig} JSON object into an
     * {@link AzureConnectionSettings}.
     *
     * @param name         the source instance name for error messages
     * @param sourceConfig the per-source configuration object
     * @return validated connection settings
     * @throws ConfigPropertySourceException if any required field is missing or invalid
     */
    private static AzureConnectionSettings parseSettings(String name, JsonObject sourceConfig) {
        // --- endpoint (required, non-blank, valid absolute URI) ---
        String endpoint = sourceConfig.getString("endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            throw new ConfigPropertySourceException(name, "required field 'endpoint' is missing or blank");
        }
        String canonicalEndpoint = validateAndCanonicaliseEndpoint(name, endpoint);

        // --- prefix (optional) ---
        String prefix = sourceConfig.getString("prefix");

        // --- auth block (optional; defaults to method="default") ---
        JsonObject auth = sourceConfig.getJsonObject("auth");
        String authMethod;
        String clientId;
        if (auth == null) {
            authMethod = AzureCredentials.METHOD_DEFAULT;
            clientId = null;
        } else {
            authMethod = auth.getString("method", AzureCredentials.METHOD_DEFAULT);
            clientId = auth.getString("clientId");
            if (!AzureCredentials.METHOD_DEFAULT.equals(authMethod)
                    && !AzureCredentials.METHOD_MANAGED_IDENTITY.equals(authMethod)) {
                throw new ConfigPropertySourceException(
                        name,
                        "unknown auth method '" + authMethod + "'; supported methods: 'default', 'managed-identity'");
            }
        }

        // --- timeouts ---
        int connectTimeoutMs =
                SourceConfigValues.positiveInt(name, sourceConfig, "connectTimeoutMs", DEFAULT_TIMEOUT_MS);
        int readTimeoutMs = SourceConfigValues.positiveInt(name, sourceConfig, "readTimeoutMs", DEFAULT_TIMEOUT_MS);

        return new AzureConnectionSettings(
                endpoint, canonicalEndpoint, prefix, authMethod, clientId, connectTimeoutMs, readTimeoutMs);
    }

    // --- Endpoint validation helpers ---

    /**
     * Normalises the host string returned by {@link URI#getHost()} for the HTTP allowlist check.
     *
     * <p>{@link URI#getHost()} returns:
     * <ul>
     *   <li>The hostname in the original case (e.g. {@code "LOCALHOST"} for
     *       {@code http://LOCALHOST:8443}).</li>
     *   <li>The IPv6 address wrapped in brackets (e.g. {@code "[::1]"} for
     *       {@code http://[::1]:8443}).</li>
     * </ul>
     *
     * <p>This method lowercases the host and strips surrounding {@code [} / {@code ]} so it can be
     * compared against the canonical forms in {@link #HTTP_ALLOWED_HOSTS}.
     *
     * @param uriHost the host string from {@link URI#getHost()}
     * @return the normalised lowercase host with IPv6 brackets removed
     */
    private static String normaliseHost(String uriHost) {
        String lower = uriHost.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            return lower.substring(1, lower.length() - 1);
        }
        return lower;
    }

    /**
     * Parses and validates the {@code endpoint} field as an absolute http/https URI.
     *
     * <p>Rules enforced:
     * <ul>
     *   <li>Must be a valid URI (no {@link java.net.URISyntaxException}).</li>
     *   <li>Must be absolute and have a non-null host ({@code scheme://host[...]).</li>
     *   <li>Scheme must be {@code http} or {@code https} (case-insensitive).</li>
     *   <li>No userinfo, query string, or fragment.</li>
     *   <li>No path component other than {@code ""} or {@code "/"}.</li>
     *   <li>{@code http} is only allowed for loopback test-double addresses:
     *       {@code localhost}, {@code 127.0.0.1}, {@code ::1}, or any host ending with
     *       {@code ".localhost"} (e.g., Lowkey Vault container addresses). The check is
     *       case-insensitive and handles IPv6 bracket notation.</li>
     * </ul>
     *
     * <p>Returns the canonical form: {@code scheme://host} or {@code scheme://host:port}.
     * The path, query, fragment, and userinfo components of the original {@code endpoint}
     * are intentionally stripped from the canonical form so that the INFO log cannot leak
     * any credential artefacts that might have been placed in those positions.
     *
     * <p>Error messages name the field and the violation only — they never echo back the
     * configured endpoint value, path, or any other user-supplied string, because that
     * material is treated as log-unsafe.
     *
     * @param name     the source instance name for error messages
     * @param endpoint the raw endpoint string from configuration
     * @return the canonical endpoint string ({@code scheme://host[:port]})
     * @throws ConfigPropertySourceException if the endpoint fails validation
     */
    private static String validateAndCanonicaliseEndpoint(String name, String endpoint) {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            throw new ConfigPropertySourceException(name, "field 'endpoint' is not a valid URI: " + e.getReason());
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new ConfigPropertySourceException(
                    name, "field 'endpoint' must be an absolute http(s) URI with a host");
        }
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ConfigPropertySourceException(name, "field 'endpoint' scheme must be 'http' or 'https'");
        }
        if (uri.getUserInfo() != null) {
            throw new ConfigPropertySourceException(name, "field 'endpoint' must not contain userinfo/query/fragment");
        }
        if (uri.getQuery() != null) {
            throw new ConfigPropertySourceException(name, "field 'endpoint' must not contain userinfo/query/fragment");
        }
        if (uri.getFragment() != null) {
            throw new ConfigPropertySourceException(name, "field 'endpoint' must not contain userinfo/query/fragment");
        }
        // Normalize the host for the allowlist check: lowercase + strip IPv6 brackets.
        // URI.getHost() returns "[::1]" for IPv6 literals and preserves the original case of
        // hostnames, so "LOCALHOST" and "[::1]" would both miss an exact-match set lookup.
        // The canonical endpoint reconstruction below always uses the original uri.getHost() form
        // so that IPv6 brackets are preserved in the output (scheme://[::1]:port).
        String host = uri.getHost();
        String normalisedHost = normaliseHost(host);
        if ("http".equals(scheme)
                && !HTTP_ALLOWED_HOSTS.contains(normalisedHost)
                && !normalisedHost.endsWith(".localhost")) {
            throw new ConfigPropertySourceException(
                    name,
                    "field 'endpoint' must use HTTPS for non-localhost hosts; "
                            + "http is only allowed for test doubles "
                            + "(localhost, 127.0.0.1, ::1, *.localhost)");
        }
        // Reject endpoints with a non-empty path component (other than "" or "/").
        // A path component would silently survive if the raw endpoint were passed to the SDK,
        // causing the raw and effective endpoint to diverge.
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new ConfigPropertySourceException(name, "field 'endpoint' must not contain a path component");
        }
        // Compute canonical form: scheme://host[:port] — strips any trailing "/" and userinfo.
        // Use the original uri.getHost() (not normalisedHost) so IPv6 brackets are preserved.
        int port = uri.getPort();
        return scheme + "://" + host + (port != -1 ? ":" + port : "");
    }
}
