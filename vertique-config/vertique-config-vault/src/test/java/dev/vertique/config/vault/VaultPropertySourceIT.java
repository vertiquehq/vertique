// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import static dev.vertique.config.testing.ExceptionChainAssertions.containsAnywhere;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.bootstrap.BootstrapConfigLoader;
import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.vault.VaultContainer;

/**
 * Integration tests for {@link VaultPropertySourceFactory} and {@link VaultPropertySource} against
 * a real HashiCorp Vault instance (hashicorp/vault:1.15) running in dev mode via Testcontainers.
 *
 * <p>Vault dev mode pre-enables the KV v2 secret engine at the default {@code secret/} mount point,
 * so no additional engine-enable commands are needed for the {@code secret/} mount.
 *
 * <h2>Fail-closed contract</h2>
 * <p>{@link JOpenLibsVaultGateway} inspects the HTTP response status after every read call and
 * throws {@link VaultReadException} for any non-2xx status, including 404 (path not found) and
 * 403 (permission denied / bad token). {@link VaultPropertySourceFactory#create} therefore throws
 * a {@link ConfigPropertySourceException} at source-creation time for these cases — startup is
 * aborted before any placeholder resolution runs.
 *
 * <p>Covers:
 * <ul>
 *   <li>AC-5: tree-style factory lookup — {@code VaultPropertySourceFactory.create()} + lookup</li>
 *   <li>End-to-end through {@link BootstrapConfigLoader} with placeholder resolution</li>
 *   <li>Missing path → {@code create()} fails with path named; sentinel not in exception message</li>
 *   <li>Unreachable Vault → {@code create()} fails (TCP refused) even when placeholder has a default</li>
 *   <li>Bad token → {@code create()} fails fast (HTTP 403) without echoing the token</li>
 *   <li>Prefix routing — two paths with distinct prefixes in one source</li>
 * </ul>
 *
 * <p>Image: {@code hashicorp/vault:1.15}, seeded via {@code withInitCommand} (dev mode + root
 * token {@code "root-token"}). The container is shared across all tests in this class for speed.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class VaultPropertySourceIT {

    // --- Vault image and credentials ---

    /** Docker image used for all tests in this class. */
    private static final String VAULT_IMAGE = "hashicorp/vault:1.15";

    /** Root token used in all tests (non-secret test value). */
    private static final String ROOT_TOKEN = "root-token";

    /** Sentinel value stored at {@code secret/app} under key {@code password}. */
    private static final String PASSWORD_SENTINEL = "s3cret-IT-SENTINEL";

    /** Sentinel value stored at {@code secret/app} under key {@code api.key}. */
    private static final String API_KEY_SENTINEL = "key-123";

    /** Sentinel value stored at {@code secret/other} under key {@code token}. */
    private static final String OTHER_TOKEN_SENTINEL = "tok-other-456";

    // --- Shared container ---

    @SuppressWarnings("resource")
    static final VaultContainer<?> vault = new VaultContainer<>(VAULT_IMAGE)
            // Dev mode root token; also sets VAULT_TOKEN env inside the container
            .withVaultToken(ROOT_TOKEN)
            // Seed secrets for the test suite:
            //   secret/app  → {password: s3cret-IT-SENTINEL, api.key: key-123}
            //   secret/other → {token: tok-other-456}
            // Note: Vault dev mode enables KV v2 at secret/ by default.
            // VaultContainer prepends "vault " to each init command automatically.
            .withInitCommand(
                    "kv put secret/app password=" + PASSWORD_SENTINEL + " api.key=" + API_KEY_SENTINEL,
                    "kv put secret/other token=" + OTHER_TOKEN_SENTINEL);

    // --- Lifecycle ---

    /**
     * Starts the shared Vault container once for all tests. Seeding is performed inside the
     * container via init commands declared at build time.
     */
    @BeforeAll
    static void startVault() {
        vault.start();
    }

    /**
     * Stops the shared Vault container after all tests complete.
     */
    @AfterAll
    static void stopVault() {
        vault.stop();
    }

    // --- Helper factories ---

    /**
     * Builds a vault source config JSON entry for use with {@link VaultPropertySourceFactory}.
     *
     * @param address  the Vault HTTP address
     * @param token    the Vault token
     * @param pathObjs one or more path entries ({@code {path, prefix}} JsonObjects)
     * @return a source config JsonObject ready for {@code factory.create("name", config)}
     */
    private static JsonObject buildSourceConfig(String address, String token, JsonObject... pathObjs) {
        JsonArray paths = new JsonArray();
        for (JsonObject p : pathObjs) {
            paths.add(p);
        }
        return new JsonObject()
                .put("type", "vault")
                .put("address", address)
                .put("auth", new JsonObject().put("method", "token").put("token", token))
                .put("paths", paths);
    }

    /**
     * Builds a single path entry for {@code buildSourceConfig}.
     *
     * @param path   the Vault KV path (without {@code /data/} infix)
     * @param prefix the key prefix to apply; empty string for no prefix
     * @return a path-entry JsonObject
     */
    private static JsonObject pathEntry(String path, String prefix) {
        return new JsonObject().put("path", path).put("prefix", prefix);
    }

    /**
     * Builds a deployment config for use with {@link BootstrapConfigLoader#load(JsonObject)}.
     * The config includes a single vault property source and an arbitrary tree payload.
     *
     * @param address     the Vault HTTP address
     * @param token       the Vault token
     * @param treePayload additional keys to merge into the top-level deployment config
     * @param pathObjs    path entries for the vault source
     * @return deployment config JsonObject
     */
    private static JsonObject buildDeploymentConfig(
            String address, String token, JsonObject treePayload, JsonObject... pathObjs) {
        JsonObject sourceEntry = buildSourceConfig(address, token, pathObjs).put("name", "vault-it");

        JsonObject config = new JsonObject()
                .put(
                        BootstrapConfigLoader.CONFIG_SECTION,
                        new JsonObject()
                                .put(BootstrapConfigLoader.PROPERTY_SOURCES_KEY, new JsonArray().add(sourceEntry)));

        // Merge in the caller's tree payload (e.g. placeholders to resolve)
        return config.mergeIn(treePayload, true);
    }

    // --- Tests ---

    /**
     * AC-5: factory-level lookup with prefix routing.
     *
     * <p>Creates a source directly via the factory (no BootstrapConfigLoader), seeds from
     * {@code secret/app} with prefix {@code "app."}, and verifies:
     * <ul>
     *   <li>{@code lookup("app.password")} returns the password sentinel</li>
     *   <li>{@code lookup("app.api.key")} returns the api-key sentinel (dot in data key
     *       is not further split — driver returns it flat)</li>
     *   <li>A key not in the vault returns empty</li>
     * </ul>
     */
    @Test
    @DisplayName("AC-5: factory create + lookup returns seeded secrets with prefix applied")
    void shouldLookupSeededSecretsWithPrefix() {
        String address = vault.getHttpHostAddress();
        JsonObject config = buildSourceConfig(address, ROOT_TOKEN, pathEntry("secret/app", "app."));

        VaultPropertySourceFactory factory = new VaultPropertySourceFactory();
        ConfigPropertySource source = factory.create("it-ac5", config);

        assertEquals(
                PASSWORD_SENTINEL,
                source.lookup("app.password").orElse(null),
                "app.password must resolve to the seeded sentinel");
        assertEquals(
                API_KEY_SENTINEL,
                source.lookup("app.api.key").orElse(null),
                "app.api.key must resolve to the api-key sentinel");
        assertFalse(source.lookup("app.nonexistent").isPresent(), "Missing key must return empty");
    }

    /**
     * End-to-end: placeholder resolution through {@link BootstrapConfigLoader}.
     *
     * <p>The deployment config declares a vault source for {@code secret/app} with prefix
     * {@code "db."}, and places {@code ${db.password}} as the value of {@code db.password} in the
     * tree. The self-reference idiom causes the resolver to skip the tree for that key (it's on the
     * stack) and fall through to the vault source, which supplies the sentinel.
     */
    @Test
    @DisplayName("End-to-end: BootstrapConfigLoader resolves vault placeholder")
    void shouldResolveVaultPlaceholderThroughBootstrapLoader() {
        String address = vault.getHttpHostAddress();

        // Tree: db.password = "${db.password}" — self-reference idiom; tree probe is skipped
        // (key is on the stack), falls through to vault source with prefix "db." → "password" key
        JsonObject treePayload = new JsonObject().put("db", new JsonObject().put("password", "${db.password}"));

        JsonObject deploymentConfig =
                buildDeploymentConfig(address, ROOT_TOKEN, treePayload, pathEntry("secret/app", "db."));

        BootstrapConfigLoader.BootstrapResult result =
                assertDoesNotThrow(() -> BootstrapConfigLoader.load(deploymentConfig));

        String resolved = result.config().getJsonObject("db").getString("password");
        assertEquals(PASSWORD_SENTINEL, resolved, "Vault-sourced placeholder must resolve to seeded sentinel");
    }

    /**
     * Missing path: declaring a non-existent Vault path causes {@code create()} to fail fast.
     *
     * <p>The gateway inspects the HTTP 404 response and throws {@link VaultReadException}, which
     * {@link VaultPropertySourceFactory#create} wraps in a
     * {@link ConfigPropertySourceException}. {@link BootstrapConfigLoader#load(JsonObject)} wraps
     * that in a {@link dev.vertique.config.bootstrap.BootstrapConfigException}. The path name must
     * appear in the exception chain; the password sentinel must not.
     */
    @Test
    @DisplayName("Missing path: create() fails fast with HTTP 404; path named in exception; sentinel absent")
    void shouldFailWhenPathDoesNotExist() {
        String address = vault.getHttpHostAddress();

        JsonObject treePayload = new JsonObject().put("db", new JsonObject().put("password", "${db.password}"));

        JsonObject deploymentConfig =
                buildDeploymentConfig(address, ROOT_TOKEN, treePayload, pathEntry("secret/nope", "db."));

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> BootstrapConfigLoader.load(deploymentConfig),
                "Load must throw when a declared path does not exist in Vault");

        // The root cause must be a ConfigPropertySourceException naming the missing path
        assertTrue(
                containsAnywhere(ex, "secret/nope"),
                "Exception chain must contain the missing path 'secret/nope'; was: " + ex.getMessage());
        assertFalse(containsAnywhere(ex, PASSWORD_SENTINEL), "Exception chain must NOT contain the sentinel value");
    }

    /**
     * Unreachable Vault: {@code create()} fails (TCP refused) even when the placeholder has a
     * default value, so the default is never applied.
     *
     * <p>Unlike a bad token (which the jopenlibs driver handles silently), an unreachable address
     * causes the driver to throw at connection time. The factory wraps this in a
     * {@link dev.vertique.config.source.ConfigPropertySourceException}, which {@link BootstrapConfigLoader}
     * wraps in a {@link dev.vertique.config.bootstrap.BootstrapConfigException}. The load must fail
     * before any placeholder resolution runs, so the {@code :fallback} default must not appear in
     * the resolved tree and must not appear in the exception message.
     */
    @Test
    @DisplayName("Unreachable Vault: load fails before resolution, default is not used")
    void shouldFailOnUnreachableVault() {
        // Port 1 is TCP-refused on all platforms (assigned to TCP Port Service Multiplexer)
        String badAddress = "http://localhost:1";

        // Placeholder with a default — if the default were applied that would be a bug
        JsonObject treePayload =
                new JsonObject().put("db", new JsonObject().put("password", "${db.password:fallback}"));

        JsonObject deploymentConfig =
                buildDeploymentConfig(badAddress, ROOT_TOKEN, treePayload, pathEntry("secret/app", "db."));

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> BootstrapConfigLoader.load(deploymentConfig),
                "Load must throw when Vault is unreachable");

        // The exception must not contain the fallback value — load failed before resolution
        assertFalse(
                containsAnywhere(ex, "fallback"),
                "Exception message chain must NOT contain the fallback value; was: " + ex.getMessage());
    }

    /**
     * Bad token: a 403 response causes {@code create()} to fail fast without echoing the token.
     *
     * <p>The gateway inspects the HTTP 403 response and throws {@link VaultReadException}, which
     * {@link VaultPropertySourceFactory#create} wraps in a
     * {@link ConfigPropertySourceException}. This test verifies:
     * <ol>
     *   <li>The {@code create()} call throws a {@link ConfigPropertySourceException} (fail-closed).</li>
     *   <li>The exception names the source.</li>
     *   <li>The bad token value does not appear anywhere in the exception chain.</li>
     * </ol>
     *
     * <p>A sentinel-shaped token ({@code "tok-SENTINEL-do-not-log"}) is used so accidental
     * echoing is detectable.
     */
    @Test
    @DisplayName("Bad token: create() fails fast with HTTP 403; token not echoed in exception")
    void shouldFailOnBadTokenWithoutEchoing() {
        String address = vault.getHttpHostAddress();
        String badToken = "tok-SENTINEL-do-not-log";

        JsonObject factoryConfig = buildSourceConfig(address, badToken, pathEntry("secret/app", "db."));
        VaultPropertySourceFactory factory = new VaultPropertySourceFactory();
        ConfigPropertySourceException createEx = assertThrows(
                ConfigPropertySourceException.class,
                () -> factory.create("it-bad-token", factoryConfig),
                "create() must throw when Vault returns 403 for the declared path");

        assertEquals("it-bad-token", createEx.sourceName(), "exception must name the source");
        assertFalse(
                containsAnywhere(createEx, badToken),
                "create() exception must NOT contain the bad token; was: " + createEx.getMessage());
    }

    /**
     * Prefix routing: two paths declared in a single source, each with a distinct prefix.
     *
     * <p>Declares {@code secret/app} with prefix {@code "app."} and {@code secret/other} with
     * prefix {@code "other."}. Both namespaces must be resolvable via the same source instance.
     */
    @Test
    @DisplayName("Prefix routing: two paths with distinct prefixes both resolve")
    void shouldRouteTwoPathsWithDistinctPrefixes() {
        String address = vault.getHttpHostAddress();
        JsonObject config = buildSourceConfig(
                address, ROOT_TOKEN, pathEntry("secret/app", "app."), pathEntry("secret/other", "other."));

        VaultPropertySourceFactory factory = new VaultPropertySourceFactory();
        ConfigPropertySource source = factory.create("it-two-paths", config);

        assertEquals(
                PASSWORD_SENTINEL,
                source.lookup("app.password").orElse(null),
                "app.password must resolve from secret/app with prefix app.");
        assertEquals(
                OTHER_TOKEN_SENTINEL,
                source.lookup("other.token").orElse(null),
                "other.token must resolve from secret/other with prefix other.");
        assertFalse(source.lookup("app.token").isPresent(), "Cross-namespace key app.token must not be found");
    }
}
