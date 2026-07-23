// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

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
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;

/**
 * Integration tests for {@link AwsSecretsPropertySourceFactory} and
 * {@link AwsSecretsPropertySource} against a real AWS Secrets Manager emulation running in
 * LocalStack (image {@code localstack/localstack:4.4}) via Testcontainers.
 *
 * <h2>Credentials strategy</h2>
 * <p>The production factory builds its SDK client using the AWS SDK <em>default credentials
 * provider chain</em> — it never accepts inline credentials. LocalStack accepts any well-formed
 * credentials; the default chain reads Java system properties first, so this test sets
 * {@code aws.accessKeyId}, {@code aws.secretAccessKey}, and {@code aws.region} in
 * {@link #startLocalStack()} from the container's own values, and clears them in
 * {@link #stopLocalStack()}. This technique requires no changes to production code and no special
 * credentials files.
 *
 * <h2>Fail-closed contract</h2>
 * <p>{@link SdkSecretsGateway} inspects the SDK response and throws
 * {@link ConfigPropertySourceException} for binary secrets, not-found, access-denied, and all SDK
 * errors. {@link AwsSecretsPropertySource} therefore fails at construction time, causing
 * {@link BootstrapConfigLoader#load(JsonObject)} to throw a
 * {@link dev.vertique.config.bootstrap.BootstrapConfigException} before placeholder resolution.
 *
 * <p>Covers:
 * <ul>
 *   <li>AC: prefix mode end-to-end — JSON blob flattened, nested dot-path, factory-level lookup</li>
 *   <li>AC: key mode — plain string stored as-is under declared key</li>
 *   <li>AC: end-to-end through {@link BootstrapConfigLoader} with placeholder resolution</li>
 *   <li>AC: missing secret → {@code create()} fails closed; secret ID named; sentinel absent</li>
 *   <li>AC: binary secret → {@code create()} fails closed; secret ID named</li>
 *   <li>AC: non-JSON string in prefix mode → {@code create()} fails closed; content not echoed</li>
 * </ul>
 *
 * <p>Image: {@code localstack/localstack:4.4}, single shared container for all tests.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
public class AwsSecretsPropertySourceIT {

    // --- Image and sentinel constants ---

    /** Docker image used for all tests. Pinned to a recent stable release. */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:4.4";

    /** JSON blob secret ID seeded in {@link #startLocalStack()}. */
    private static final String DB_SECRET_ID = "it/db";

    /** Plain string secret ID seeded in {@link #startLocalStack()}. */
    private static final String API_KEY_SECRET_ID = "it/api-key";

    /** Plain string secret for non-JSON prefix-mode failure test. */
    private static final String PLAIN_PREFIX_SECRET_ID = "it/plain-prefix";

    /** Sentinel value stored in the JSON blob at {@code "password"}. */
    private static final String PASSWORD_SENTINEL = "s3cret-IT-SENTINEL";

    /** Sentinel value stored in the JSON blob at {@code "username"}. */
    private static final String USERNAME_SENTINEL = "app";

    /** Sentinel value stored in the JSON blob at nested path {@code "nested.host"}. */
    private static final String NESTED_HOST_SENTINEL = "db-host";

    /** Sentinel value stored as the plain-string secret. */
    private static final String API_KEY_SENTINEL = "plain-key-SENTINEL-2";

    // --- Shared container ---

    @SuppressWarnings("resource")
    static final LocalStackContainer localStack =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE)).withServices("secretsmanager");

    // --- Lifecycle ---

    /**
     * Starts the shared LocalStack container and seeds all secrets via the AWS SDK. Sets Java
     * system properties {@code aws.accessKeyId}, {@code aws.secretAccessKey}, and
     * {@code aws.region} from the container's credentials so the factory's default credential chain
     * authenticates against LocalStack. These properties are cleared in {@link #stopLocalStack()}.
     *
     * @throws Exception if the container fails to start or secret seeding fails
     */
    @BeforeAll
    static void startLocalStack() throws Exception {
        localStack.start();

        // Set system properties so the SDK default credentials chain picks them up.
        // LocalStack accepts any creds; sys props are the highest-priority source in the default chain.
        System.setProperty("aws.accessKeyId", localStack.getAccessKey());
        System.setProperty("aws.secretAccessKey", localStack.getSecretKey());
        System.setProperty("aws.region", localStack.getRegion());

        seedSecrets();
    }

    /**
     * Stops the shared LocalStack container and removes the AWS credential system properties
     * that were set in {@link #startLocalStack()}.
     */
    @AfterAll
    static void stopLocalStack() {
        try {
            localStack.stop();
        } finally {
            System.clearProperty("aws.accessKeyId");
            System.clearProperty("aws.secretAccessKey");
            System.clearProperty("aws.region");
        }
    }

    // --- Secret seeding ---

    /**
     * Creates all secrets needed by the test suite via a dedicated SDK client pointed directly at
     * the LocalStack endpoint with {@link StaticCredentialsProvider} (this is the seeding client,
     * not the factory's client).
     *
     * <p>Secrets created:
     * <ul>
     *   <li>{@value #DB_SECRET_ID} — JSON blob with {@code password}, {@code username}, and
     *       nested {@code nested.host}</li>
     *   <li>{@value #API_KEY_SECRET_ID} — plain string</li>
     *   <li>{@code it/binary} — binary secret (used for fail-closed test)</li>
     *   <li>{@value #PLAIN_PREFIX_SECRET_ID} — plain string declared in prefix mode (fail-closed)</li>
     * </ul>
     */
    private static void seedSecrets() {
        URI endpoint = localStack.getEndpoint();
        Region region = Region.of(localStack.getRegion());
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey()));

        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .endpointOverride(endpoint)
                .region(region)
                .credentialsProvider(creds)
                .build()) {

            // JSON blob: password, username, nested object
            String jsonBlob = new JsonObject()
                    .put("password", PASSWORD_SENTINEL)
                    .put("username", USERNAME_SENTINEL)
                    .put("nested", new JsonObject().put("host", NESTED_HOST_SENTINEL))
                    .encode();
            client.createSecret(CreateSecretRequest.builder()
                    .name(DB_SECRET_ID)
                    .secretString(jsonBlob)
                    .build());

            // Plain string
            client.createSecret(CreateSecretRequest.builder()
                    .name(API_KEY_SECRET_ID)
                    .secretString(API_KEY_SENTINEL)
                    .build());

            // Binary secret (fail-closed test)
            client.createSecret(CreateSecretRequest.builder()
                    .name("it/binary")
                    .secretBinary(SdkBytes.fromByteArray(new byte[] {0x01, 0x02, 0x03}))
                    .build());

            // Plain string to be used in prefix mode (fail-closed test)
            client.createSecret(CreateSecretRequest.builder()
                    .name(PLAIN_PREFIX_SECRET_ID)
                    .secretString(API_KEY_SENTINEL)
                    .build());
        }
    }

    // --- Helper factories ---

    /**
     * Builds a source config JsonObject for use with {@link AwsSecretsPropertySourceFactory}.
     *
     * <p>Reads the endpoint and region directly from the shared {@link #localStack} container so
     * call sites do not need to repeat the lookup.
     *
     * @param secretEntries one or more secret entry JsonObjects
     * @return source config JsonObject ready for {@code factory.create("name", config)}
     */
    private static JsonObject buildSourceConfig(JsonObject... secretEntries) {
        JsonArray secrets = new JsonArray();
        for (JsonObject entry : secretEntries) {
            secrets.add(entry);
        }
        return new JsonObject()
                .put("type", "aws-secrets")
                .put("region", localStack.getRegion())
                .put("endpointOverride", localStack.getEndpoint().toString())
                .put("secrets", secrets);
    }

    /**
     * Returns a secret entry in prefix mode.
     *
     * @param secretId the AWS Secrets Manager secret ID
     * @param prefix   the prefix to prepend to all flattened keys
     * @return a secret entry JsonObject
     */
    private static JsonObject prefixEntry(String secretId, String prefix) {
        return new JsonObject().put("secretId", secretId).put("prefix", prefix);
    }

    /**
     * Returns a secret entry in key mode.
     *
     * @param secretId the AWS Secrets Manager secret ID
     * @param key      the single key under which the secret string is exposed
     * @return a secret entry JsonObject
     */
    private static JsonObject keyEntry(String secretId, String key) {
        return new JsonObject().put("secretId", secretId).put("key", key);
    }

    /**
     * Builds a deployment config for use with {@link BootstrapConfigLoader#load(JsonObject)},
     * wrapping a single {@code aws-secrets} property source plus an arbitrary tree payload.
     *
     * <p>The endpoint and region are read from the shared {@link #localStack} container via
     * {@link #buildSourceConfig(JsonObject...)}.
     *
     * @param treePayload   tree keys to merge (e.g. placeholders to resolve)
     * @param secretEntries secret entries for the source
     * @return deployment config JsonObject
     */
    private static JsonObject buildDeploymentConfig(JsonObject treePayload, JsonObject... secretEntries) {
        JsonObject sourceEntry = buildSourceConfig(secretEntries).put("name", "aws-it");
        JsonObject config = new JsonObject()
                .put(
                        BootstrapConfigLoader.CONFIG_SECTION,
                        new JsonObject()
                                .put(BootstrapConfigLoader.PROPERTY_SOURCES_KEY, new JsonArray().add(sourceEntry)));
        return config.mergeIn(treePayload, true);
    }

    // --- Tests ---

    /**
     * Prefix mode end-to-end: factory creates source for a JSON blob secret with prefix {@code
     * "db."}; verifies password sentinel, nested dot-path {@code db.nested.host}, and username.
     *
     * <p>The nested JSON object is flattened by {@link dev.vertique.config.source.SecretDataFlattener}
     * using dot-separator, yielding {@code db.nested.host}.
     */
    @Test
    @DisplayName("Prefix mode: JSON blob flattened under prefix — password, nested.host, username all resolve")
    void shouldLookupJsonBlobWithPrefix() {
        JsonObject config = buildSourceConfig(prefixEntry(DB_SECRET_ID, "db."));

        AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory();
        ConfigPropertySource source = assertDoesNotThrow(() -> factory.create("it-prefix", config));

        assertEquals(
                PASSWORD_SENTINEL,
                source.lookup("db.password").orElse(null),
                "db.password must resolve to the password sentinel");
        assertEquals(
                NESTED_HOST_SENTINEL,
                source.lookup("db.nested.host").orElse(null),
                "db.nested.host must resolve via dot-flattening of nested JSON object");
        assertEquals(
                USERNAME_SENTINEL,
                source.lookup("db.username").orElse(null),
                "db.username must resolve to the username sentinel");
    }

    /**
     * Key mode: factory creates source for a plain string secret exposed under a single key;
     * verifies the sentinel is returned as-is.
     */
    @Test
    @DisplayName("Key mode: plain string secret stored under declared key")
    void shouldLookupPlainStringWithKey() {
        JsonObject config = buildSourceConfig(keyEntry(API_KEY_SECRET_ID, "api.key"));

        AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory();
        ConfigPropertySource source = assertDoesNotThrow(() -> factory.create("it-key", config));

        assertEquals(
                API_KEY_SENTINEL,
                source.lookup("api.key").orElse(null),
                "api.key must resolve to the plain API key sentinel");
    }

    /**
     * End-to-end through {@link BootstrapConfigLoader}: declares both a prefix-mode and a key-mode
     * entry in a single property source, places self-referencing placeholders for the JSON blob
     * values and a plain placeholder for the API key, and verifies resolution.
     *
     * <p>The self-reference idiom ({@code "${db.password}"} as the value of {@code db.password})
     * causes the placeholder resolver to skip the tree (key is on the stack) and fall through to
     * the property source.
     */
    @Test
    @DisplayName("End-to-end: BootstrapConfigLoader resolves both prefix-mode and key-mode placeholders")
    void shouldResolveAllPlaceholdersThroughBootstrapLoader() {
        // Self-reference placeholders: resolver skips tree for keys on the stack
        JsonObject treePayload = new JsonObject()
                .put("db", new JsonObject().put("password", "${db.password}"))
                .put("api", new JsonObject().put("key", "${api.key:fallback}"));

        JsonObject deploymentConfig = buildDeploymentConfig(
                treePayload, prefixEntry(DB_SECRET_ID, "db."), keyEntry(API_KEY_SECRET_ID, "api.key"));

        BootstrapConfigLoader.BootstrapResult result =
                assertDoesNotThrow(() -> BootstrapConfigLoader.load(deploymentConfig));

        String resolvedPassword = result.config().getJsonObject("db").getString("password");
        assertEquals(PASSWORD_SENTINEL, resolvedPassword, "db.password must resolve to the password sentinel");

        String resolvedApiKey = result.config().getJsonObject("api").getString("key");
        assertEquals(API_KEY_SENTINEL, resolvedApiKey, "api.key must resolve to the API key sentinel");
    }

    /**
     * Missing secret fails closed: declaring a non-existent secret ID causes {@code create()} to
     * throw a {@link ConfigPropertySourceException} naming the secret ID. Neither sentinel is in
     * the exception chain.
     */
    @Test
    @DisplayName("Missing secret: create() fails closed; secret ID named; sentinels absent from exception")
    void shouldFailClosedForMissingSecret() {
        JsonObject config = buildSourceConfig(prefixEntry("it/nope", "x."));

        AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory();
        ConfigPropertySourceException ex = assertThrows(
                ConfigPropertySourceException.class,
                () -> factory.create("it-missing", config),
                "create() must throw when the declared secret does not exist");

        assertTrue(
                containsAnywhere(ex, "it/nope"),
                "Exception chain must name the missing secret ID; was: " + ex.getMessage());
        assertFalse(
                containsAnywhere(ex, PASSWORD_SENTINEL),
                "Exception chain must NOT contain the password sentinel; was: " + ex.getMessage());
        assertFalse(
                containsAnywhere(ex, API_KEY_SENTINEL),
                "Exception chain must NOT contain the API key sentinel; was: " + ex.getMessage());
    }

    /**
     * Binary secret fails closed: a secret created with {@code SecretBinary} (not
     * {@code SecretString}) causes {@code create()} to throw, naming the secret ID.
     */
    @Test
    @DisplayName("Binary secret: create() fails closed; secret ID named in exception")
    void shouldFailClosedForBinarySecret() {
        JsonObject config = buildSourceConfig(prefixEntry("it/binary", "bin."));

        AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory();
        ConfigPropertySourceException ex = assertThrows(
                ConfigPropertySourceException.class,
                () -> factory.create("it-binary", config),
                "create() must throw for binary secrets");

        assertTrue(
                containsAnywhere(ex, "it/binary"),
                "Exception chain must name the binary secret ID; was: " + ex.getMessage());
    }

    /**
     * Non-JSON string in prefix mode fails closed: a plain string secret declared with
     * {@code prefix} (expecting JSON) causes {@code create()} to throw, naming the secret ID but
     * never echoing the secret content.
     */
    @Test
    @DisplayName("Non-JSON with prefix: create() fails closed; content not in exception; secret ID named")
    void shouldFailClosedForNonJsonInPrefixMode() {
        JsonObject config = buildSourceConfig(prefixEntry(PLAIN_PREFIX_SECRET_ID, "plain."));

        AwsSecretsPropertySourceFactory factory = new AwsSecretsPropertySourceFactory();
        ConfigPropertySourceException ex = assertThrows(
                ConfigPropertySourceException.class,
                () -> factory.create("it-plain-prefix", config),
                "create() must throw when a prefix-mode secret is not a JSON object");

        assertTrue(
                containsAnywhere(ex, PLAIN_PREFIX_SECRET_ID),
                "Exception chain must name the secret ID; was: " + ex.getMessage());
        assertFalse(
                containsAnywhere(ex, API_KEY_SENTINEL),
                "Exception chain must NOT contain the secret content; was: " + ex.getMessage());
    }
}
