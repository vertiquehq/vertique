// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import io.github.jopenlibs.vault.Vault;
import io.github.jopenlibs.vault.VaultConfig;
import io.github.jopenlibs.vault.VaultException;
import io.github.jopenlibs.vault.response.AuthResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Production {@link VaultGateway} implementation backed by the jopenlibs vault-java-driver.
 *
 * <p>Authentication is performed once during construction. The resulting client token is used for
 * all subsequent {@link #readPath(String)} calls. Supports three auth methods:
 * <ul>
 *   <li><strong>token</strong> — static token from config or {@code VAULT_TOKEN} environment
 *       variable (resolved by the driver at build time)</li>
 *   <li><strong>kubernetes</strong> — reads the JWT from the service-account token file and calls
 *       {@link io.github.jopenlibs.vault.api.Auth#loginByKubernetes(String, String)}</li>
 *   <li><strong>approle</strong> — calls
 *       {@link io.github.jopenlibs.vault.api.Auth#loginByAppRole(String, String)}</li>
 * </ul>
 *
 * <p>The driver is configured with {@code engineVersion(}{@link #KV_ENGINE_VERSION}{@code )} so
 * that KV v2's {@code data/data} envelope is unwrapped transparently. Callers supply paths
 * without the {@code /data/} infix.
 *
 * <p>Timeouts: the driver takes integer seconds. This class converts milliseconds via
 * {@link #msToSeconds(int)} so that sub-1000ms values map to 1 second (never zero, which the
 * driver would interpret as "no timeout").
 *
 * <h2>Fail-closed HTTP status handling</h2>
 * <p>The jopenlibs driver intentionally does <em>not</em> throw on 4xx responses from
 * {@code Logical#read(String)} — it returns a response with an empty data map. This gateway
 * inspects the HTTP status after every read and throws {@link VaultReadException} for non-2xx
 * statuses rather than silently returning empty data:
 * <ul>
 *   <li><strong>200</strong> — data returned normally</li>
 *   <li><strong>404</strong> — throws: secret path not found</li>
 *   <li><strong>403</strong> — throws: permission denied (bad token or missing policy)</li>
 *   <li><strong>other non-2xx</strong> — throws with the HTTP status code</li>
 * </ul>
 * <p>Login calls ({@code loginByKubernetes}, {@code loginByAppRole}) already throw
 * {@link io.github.jopenlibs.vault.VaultException} on non-200 responses — no additional
 * status inspection is needed for authentication.
 *
 * <h2>Driver exception sanitization (NFR-CONF-002)</h2>
 * <p>The jopenlibs driver embeds raw HTTP response bodies in {@link VaultException} messages for
 * non-2xx reads and failed logins (format: {@code "...\nResponse body: <body>"}). Propagating the
 * driver message verbatim would expose unbounded, untrusted content (e.g. a proxy 502 HTML page)
 * in startup logs. Every caught {@link VaultException} is therefore sanitized via
 * {@link #sanitizeDriverFailure(String, VaultException)} before being re-thrown as a
 * {@link VaultReadException}: only the exception class simple name and, when present, the HTTP
 * status code are included — both constructed by this module, never from the driver message. The
 * cause is NOT attached, severing the driver chain entirely.
 *
 * <p>This class is package-private.
 */
class JOpenLibsVaultGateway implements VaultGateway {

    /**
     * KV secret engine version passed to the jopenlibs driver so that the KV v2
     * {@code data/data} envelope is unwrapped transparently.
     */
    static final int KV_ENGINE_VERSION = 2;

    private final Vault vault;

    // --- Construction ---

    /**
     * Constructs a gateway and authenticates against Vault.
     *
     * <p>For token auth the driver resolves the token lazily from config or environment; no
     * explicit call to {@code auth()} is needed. For kubernetes and approle, authentication
     * is performed here and the resulting client token replaces the initial (empty) token.
     *
     * @param settings the connection and authentication parameters
     * @throws VaultReadException if authentication fails or the Vault config cannot be built
     */
    JOpenLibsVaultGateway(VaultConnectionSettings settings) {
        this.vault = buildAndAuthenticate(settings);
    }

    // --- VaultGateway ---

    /**
     * {@inheritDoc}
     *
     * <p>The jopenlibs driver's {@code getData()} returns a flat {@code Map<String, String>}
     * containing the KV v2 data keys exactly as stored. A JSON-object string stored as a
     * secret value is returned as a literal string and is never re-interpreted. The returned
     * map is re-boxed to {@code Map<String, Object>} for the {@link VaultGateway} contract.
     *
     * <p>This implementation is fail-closed: the HTTP response status is inspected after every
     * read. A 404 or 403 response — which the driver silently surfaces as an empty data map —
     * is converted to a {@link VaultReadException} so that a missing or forbidden path aborts
     * startup rather than producing a silently empty property source.
     *
     * @throws VaultReadException if the path is not found (404), permission is denied (403),
     *                            any other non-2xx status is returned, or the driver throws
     */
    @Override
    public Map<String, Object> readPath(String path) {
        try {
            var response = vault.logical().read(path);
            int status = response.getRestResponse().getStatus();
            if (status == 404) {
                throw new VaultReadException(path, "secret path '" + path + "' not found (HTTP 404)");
            }
            if (status == 403) {
                throw new VaultReadException(
                        path, "permission denied reading '" + path + "' (HTTP 403) — check token/policy");
            }
            if (status < 200 || status >= 300) {
                throw new VaultReadException(path, "unexpected HTTP status " + status);
            }
            Map<String, String> data = response.getData();
            Map<String, Object> result = new HashMap<>();
            if (data != null) {
                result.putAll(data);
            }
            return result;
        } catch (VaultException e) {
            throw sanitizeDriverFailure(path, e);
        }
    }

    // --- Internal helpers ---

    /**
     * Builds a configured {@link Vault} instance, authenticating via the declared method.
     *
     * @param settings connection and auth parameters
     * @return a ready-to-use {@link Vault} instance with a valid token
     * @throws VaultReadException if config building or authentication fails
     */
    private static Vault buildAndAuthenticate(VaultConnectionSettings settings) {
        try {
            VaultConfig config = buildVaultConfig(settings);

            return switch (settings.authSettings()) {
                case VaultAuthSettings.Token ignored -> Vault.create(config, KV_ENGINE_VERSION);
                case VaultAuthSettings.Kubernetes k -> authenticateKubernetes(config, k);
                case VaultAuthSettings.AppRole a -> authenticateAppRole(config, a);
            };
        } catch (VaultException e) {
            throw sanitizeDriverFailure("<config>", e);
        }
    }

    /**
     * Builds the base {@link VaultConfig} from connection settings.
     *
     * <p>For token auth the factory always resolves an explicit non-null token (either from
     * the config or from the {@code VAULT_TOKEN} environment variable) before constructing the
     * gateway. The token is therefore always set unconditionally here — the driver's ambient
     * resolution ({@code ~/.vault-token}, etc.) is never engaged.
     *
     * @param settings connection settings; for token auth {@link VaultAuthSettings.Token#token()}
     *                 is guaranteed non-null and non-blank
     * @return built {@link VaultConfig}
     * @throws VaultException if the config cannot be built (e.g. missing address)
     */
    private static VaultConfig buildVaultConfig(VaultConnectionSettings settings) throws VaultException {
        VaultConfig config = new VaultConfig()
                .address(settings.address())
                .engineVersion(KV_ENGINE_VERSION)
                .openTimeout(msToSeconds(settings.openTimeoutMs()))
                .readTimeout(msToSeconds(settings.readTimeoutMs()));

        if (settings.namespace() != null) {
            config = config.nameSpace(settings.namespace());
        }

        // Token auth: the factory always resolves an explicit token (VAULT_TOKEN env var fallback
        // is owned by the factory, not the driver). Set it unconditionally.
        if (settings.authSettings() instanceof VaultAuthSettings.Token t) {
            config = config.token(t.token());
        }

        return config.build();
    }

    /**
     * Authenticates via Kubernetes service-account JWT and returns a Vault instance with the
     * resulting client token.
     *
     * <p>Reads the JWT from the file at {@link VaultAuthSettings.Kubernetes#jwtPath()}, then
     * calls {@link io.github.jopenlibs.vault.api.Auth#loginByKubernetes(String, String)}.
     *
     * @param config base VaultConfig (no token set)
     * @param k      Kubernetes auth settings
     * @return authenticated Vault instance
     * @throws VaultException if the JWT file cannot be read or login fails
     */
    private static Vault authenticateKubernetes(VaultConfig config, VaultAuthSettings.Kubernetes k)
            throws VaultException {
        String jwt = readJwtFile(k.jwtPath());
        Vault unauthenticated = Vault.create(config, KV_ENGINE_VERSION);
        AuthResponse authResponse = unauthenticated.auth().loginByKubernetes(k.role(), jwt);
        String clientToken = authResponse.getAuthClientToken();
        VaultConfig authedConfig = config.token(clientToken);
        return Vault.create(authedConfig, KV_ENGINE_VERSION);
    }

    /**
     * Authenticates via AppRole and returns a Vault instance with the resulting client token.
     *
     * <p>Calls {@link io.github.jopenlibs.vault.api.Auth#loginByAppRole(String, String)}.
     *
     * @param config base VaultConfig (no token set)
     * @param a      AppRole auth settings
     * @return authenticated Vault instance
     * @throws VaultException if AppRole login fails
     */
    private static Vault authenticateAppRole(VaultConfig config, VaultAuthSettings.AppRole a) throws VaultException {
        Vault unauthenticated = Vault.create(config, KV_ENGINE_VERSION);
        AuthResponse authResponse = unauthenticated.auth().loginByAppRole(a.roleId(), a.secretId());
        String clientToken = authResponse.getAuthClientToken();
        VaultConfig authedConfig = config.token(clientToken);
        return Vault.create(authedConfig, KV_ENGINE_VERSION);
    }

    /**
     * Reads the contents of a JWT file from the filesystem.
     *
     * <p>The {@link IOException} message contains only the file path and the OS-level error
     * description — no secret content. It is safe to include directly in a
     * {@link VaultReadException} detail string.
     *
     * @param jwtPath path to the JWT token file
     * @return the JWT token string (trimmed)
     * @throws VaultReadException if the file cannot be read
     */
    private static String readJwtFile(String jwtPath) {
        try {
            return Files.readString(Paths.get(jwtPath)).trim();
        } catch (IOException e) {
            // IOException message is path + OS error description — safe to include.
            // Cause is not attached per NFR-CONF-002 cause-severing discipline.
            throw new VaultReadException(
                    "<config>", "IOException reading Kubernetes JWT from '" + jwtPath + "': " + e.getMessage());
        }
    }

    /**
     * Converts a caught driver {@link VaultException} into a {@link VaultReadException} whose
     * message contains only safe, framework-constructed text: the exception class simple name
     * and, when the driver set a non-zero HTTP status code, {@code "HTTP <code>"}.
     *
     * <p>The driver message is intentionally discarded. It may contain raw HTTP response bodies
     * (format: {@code "...\nResponse body: <body>"}) for non-2xx reads and failed logins — an
     * untrusted proxy 502 HTML page or a Vault error body in the cause chain would reach startup
     * logs as unbounded text. The driver cause is NOT attached per NFR-CONF-002.
     *
     * <p>This method is package-private to allow direct unit testing of the sanitization logic
     * without constructing a real {@link Vault} instance.
     *
     * @param path the Vault path being operated on (for diagnostics only)
     * @param e    the driver exception to sanitize
     * @return a {@link VaultReadException} with a safe, bounded message and no attached cause
     */
    static VaultReadException sanitizeDriverFailure(String path, VaultException e) {
        int statusCode = e.getHttpStatusCode();
        String detail = statusCode != 0
                ? e.getClass().getSimpleName() + " HTTP " + statusCode
                : e.getClass().getSimpleName();
        return new VaultReadException(path, detail);
    }

    /**
     * Converts a millisecond timeout to the integer seconds value the driver expects.
     *
     * <p>Uses {@code Math.max(1, (int) Math.ceil(ms / 1000.0))} so that sub-1000ms values map
     * to 1 second (never zero, which the driver interprets as "no timeout") and values above
     * 1000ms round up to the nearest whole second.
     *
     * @param ms the timeout in milliseconds
     * @return timeout in whole seconds, minimum 1
     */
    static int msToSeconds(int ms) {
        return Math.max(1, (int) Math.ceil(ms / 1000.0));
    }
}
