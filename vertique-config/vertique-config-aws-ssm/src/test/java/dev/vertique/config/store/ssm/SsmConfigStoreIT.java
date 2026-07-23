// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.store.ssm;

import static dev.vertique.config.testing.ExceptionChainAssertions.containsAnywhere;
import static dev.vertique.config.testing.ExceptionChainAssertions.exceptionChainText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.config.bootstrap.BootstrapConfigException;
import dev.vertique.config.bootstrap.BootstrapConfigLoader;
import io.vertx.config.spi.ConfigStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

/**
 * Integration tests for {@link SsmConfigStoreFactory} and {@link SsmConfigStore} against a real
 * AWS SSM Parameter Store emulation running in LocalStack (image
 * {@code localstack/localstack:4.4}) via Testcontainers.
 *
 * <h2>Credentials strategy</h2>
 * <p>The production gateway builds its SDK client using the AWS SDK <em>default credentials
 * provider chain</em>. LocalStack accepts any well-formed credentials; the default chain reads Java
 * system properties first, so this test sets {@code aws.accessKeyId},
 * {@code aws.secretAccessKey}, and {@code aws.region} in {@link #startLocalStack()} from the
 * container's own values, and clears them in {@link #stopLocalStack()}. This technique requires no
 * changes to production code and no special credentials files.
 *
 * <h2>Store vs property source</h2>
 * <p>{@link SsmConfigStore} is a Vert.x config store (type {@code "aws-ssm"}), not a property
 * source. It merges whole parameter subtrees eagerly into the config tree during phase 2 of
 * {@link BootstrapConfigLoader}. This is distinct from property sources (e.g.
 * {@code aws-secrets}) which serve individual keys on demand during placeholder resolution.
 *
 * <h2>Empty-path semantics</h2>
 * <p>GetParametersByPath on a nonexistent path returns an empty list — not an error. The store
 * propagates this as a succeeded Future carrying an empty JsonObject. This is the documented
 * behavior and is tested as such (test e).
 *
 * <p>Covers:
 * <ul>
 *   <li>AC: store-level get() — parameter tree for /it/app/ built correctly, /other/x absent</li>
 *   <li>AC: prefix re-root — entire result nested under a dot-separated prefix</li>
 *   <li>AC: end-to-end through {@link BootstrapConfigLoader} with store + precedence proof</li>
 *   <li>AC: recursive=false — only direct children fetched, nested deeper param absent</li>
 *   <li>AC: empty path semantics — nonexistent path succeeds with empty tree</li>
 *   <li>AC: failing store (unroutable endpoint) → {@link BootstrapConfigException}</li>
 *   <li>AC: NFR-CONF-002 log redaction — sentinel absent from logs on successful load</li>
 * </ul>
 *
 * <p>Image: {@code localstack/localstack:4.4}, single shared container for all tests.
 * Run twice to verify stability.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
public class SsmConfigStoreIT {

    // --- Image and sentinel constants ---

    /** Docker image used for all tests. Pinned to a recent stable release. */
    private static final String LOCALSTACK_IMAGE = "localstack/localstack:4.4";

    /** Sentinel value for the SecureString password parameter. */
    private static final String PASSWORD_SENTINEL = "ssm-SENTINEL-1";

    /** Sentinel value for the String host parameter. */
    private static final String HOST_SENTINEL = "db-host";

    /** StringList value (raw comma-separated string as stored in SSM). */
    private static final String LIST_RAW = "a,b,c";

    // --- Parameter names ---

    private static final String PARAM_DB_PASSWORD = "/it/app/db/password";
    private static final String PARAM_DB_HOST = "/it/app/db/host";
    private static final String PARAM_FEATURES_LIST = "/it/app/features/list";
    private static final String PARAM_OUTSIDE = "/other/x";
    private static final String PARAM_DEEP = "/it/app/deep/nested/x";

    // --- Shared container and Vertx instance ---

    @SuppressWarnings("resource")
    static final LocalStackContainer localStack =
            new LocalStackContainer(DockerImageName.parse(LOCALSTACK_IMAGE)).withServices("ssm");

    /** Shared Vert.x instance for all tests; avoids one-per-test overhead. */
    private static Vertx vertx;

    // --- Lifecycle ---

    /**
     * Starts the shared LocalStack container, seeds all SSM parameters, and creates the shared
     * Vert.x instance. Sets Java system properties {@code aws.accessKeyId},
     * {@code aws.secretAccessKey}, and {@code aws.region} from the container's credentials so
     * the factory's default credential chain authenticates against LocalStack. All resources are
     * released in {@link #stopLocalStack()}.
     *
     * @throws Exception if the container fails to start, parameter seeding fails, or Vert.x
     *                   creation fails
     */
    @BeforeAll
    static void startLocalStack() throws Exception {
        localStack.start();

        // Set system properties so the SDK default credentials chain picks them up.
        // LocalStack accepts any creds; sys props are the highest-priority source in the default chain.
        System.setProperty("aws.accessKeyId", localStack.getAccessKey());
        System.setProperty("aws.secretAccessKey", localStack.getSecretKey());
        System.setProperty("aws.region", localStack.getRegion());

        seedParameters();

        vertx = Vertx.vertx();
    }

    /**
     * Stops the shared LocalStack container, closes the shared Vert.x instance, and removes the
     * AWS credential system properties that were set in {@link #startLocalStack()}.
     *
     * @throws Exception if Vert.x close fails
     */
    @AfterAll
    static void stopLocalStack() throws Exception {
        try {
            if (vertx != null) {
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        } finally {
            try {
                localStack.stop();
            } finally {
                System.clearProperty("aws.accessKeyId");
                System.clearProperty("aws.secretAccessKey");
                System.clearProperty("aws.region");
            }
        }
    }

    // --- Parameter seeding ---

    /**
     * Creates all SSM parameters needed by the test suite via a dedicated SDK client pointed
     * directly at the LocalStack endpoint with {@link StaticCredentialsProvider} (the seeding
     * client, not the factory's client).
     *
     * <p>Parameters created:
     * <ul>
     *   <li>{@value #PARAM_DB_PASSWORD} — SecureString, {@value #PASSWORD_SENTINEL}</li>
     *   <li>{@value #PARAM_DB_HOST} — String, {@value #HOST_SENTINEL}</li>
     *   <li>{@value #PARAM_FEATURES_LIST} — StringList, {@value #LIST_RAW}</li>
     *   <li>{@value #PARAM_OUTSIDE} — String, "outside-value" — outside the test path</li>
     *   <li>{@value #PARAM_DEEP} — String, "deep-val" — for recursive=false exclusion test</li>
     * </ul>
     */
    private static void seedParameters() {
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey()));

        try (SsmClient client = SsmClient.builder()
                .endpointOverride(localStack.getEndpoint())
                .region(Region.of(localStack.getRegion()))
                .credentialsProvider(creds)
                .build()) {

            putParam(client, PARAM_DB_PASSWORD, PASSWORD_SENTINEL, ParameterType.SECURE_STRING);
            putParam(client, PARAM_DB_HOST, HOST_SENTINEL, ParameterType.STRING);
            putParam(client, PARAM_FEATURES_LIST, LIST_RAW, ParameterType.STRING_LIST);
            putParam(client, PARAM_OUTSIDE, "outside-value", ParameterType.STRING);
            putParam(client, PARAM_DEEP, "deep-val", ParameterType.STRING);
        }
    }

    /**
     * Puts a single parameter into SSM via the seeding client.
     *
     * @param client the SSM client to use
     * @param name   the parameter full path name
     * @param value  the parameter value
     * @param type   the parameter type
     */
    private static void putParam(SsmClient client, String name, String value, ParameterType type) {
        client.putParameter(PutParameterRequest.builder()
                .name(name)
                .value(value)
                .type(type)
                .overwrite(true)
                .build());
    }

    // --- Helper factories ---

    /**
     * Builds a store config {@link JsonObject} for the SSM store factory, pointing at the shared
     * LocalStack container.
     *
     * @param path      the SSM path prefix (e.g. {@code "/it/app/"})
     * @param recursive whether to fetch parameters recursively
     * @return store config ready for {@code new SsmConfigStoreFactory().create(vertx, config)}
     */
    private static JsonObject buildStoreConfig(String path, boolean recursive) {
        return new JsonObject()
                .put("path", path)
                .put("region", localStack.getRegion())
                .put("endpointOverride", localStack.getEndpoint().toString())
                .put("recursive", recursive);
    }

    /**
     * Returns the parameter tree by calling {@link SsmConfigStore#get()} synchronously using
     * the shared {@link #vertx} instance. Closes the store in a {@code finally} block to
     * ensure the SDK client is released even when an assertion fails.
     *
     * @param config the store config JSON object
     * @return the parsed {@link JsonObject} result
     * @throws Exception if the store call fails or times out
     */
    private static JsonObject getStoreResult(JsonObject config) throws Exception {
        ConfigStore store = new SsmConfigStoreFactory().create(vertx, config);
        try {
            Future<Buffer> future = store.get();
            // Block until completed (we are on a non-Vert.x thread)
            Buffer buf = future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            return new JsonObject(buf);
        } finally {
            store.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Builds a {@link BootstrapConfigLoader}-compatible deployment config containing one
     * {@code config.stores} entry of type {@code "aws-ssm"} and an optional payload overlay.
     *
     * @param storeConfig the SSM-specific config object passed to the store factory
     * @param payload     additional top-level keys merged into the deployment config
     * @return the deployment config JsonObject
     */
    private static JsonObject buildDeploymentConfig(JsonObject storeConfig, JsonObject payload) {
        JsonObject storeEntry = new JsonObject().put("type", "aws-ssm").put("config", storeConfig);
        JsonObject config = new JsonObject()
                .put(
                        BootstrapConfigLoader.CONFIG_SECTION,
                        new JsonObject().put(BootstrapConfigLoader.STORES_KEY, new JsonArray().add(storeEntry)));
        if (payload != null) {
            config = config.mergeIn(payload, true);
        }
        return config;
    }

    // --- Tests ---

    /**
     * Store-level test: get() over path "/it/app/" returns the expected parameter tree and does
     * NOT include a parameter outside that path.
     *
     * <p>Expected result:
     * <pre>{@code
     * {
     *   "db": {"password": "ssm-SENTINEL-1", "host": "db-host"},
     *   "features": {"list": ["a", "b", "c"]}
     * }
     * }</pre>
     *
     * <p>The {@code /other/x} parameter is absent because it is outside the store's path.
     */
    @Test
    @DisplayName("Store get(): builds nested parameter tree; /other/x absent")
    void shouldBuildParameterTreeForPath() throws Exception {
        JsonObject result = getStoreResult(buildStoreConfig("/it/app/", true));

        // db subtree
        JsonObject db = result.getJsonObject("db");
        assertNotNull(db, "db subtree must be present");
        assertEquals(PASSWORD_SENTINEL, db.getString("password"), "SecureString sentinel must be decrypted");
        assertEquals(HOST_SENTINEL, db.getString("host"), "String host must be present");

        // features.list as JsonArray
        JsonObject features = result.getJsonObject("features");
        assertNotNull(features, "features subtree must be present");
        JsonArray list = features.getJsonArray("list");
        assertNotNull(list, "features.list must be a JsonArray");
        assertEquals(3, list.size(), "StringList must split into 3 elements");
        assertEquals("a", list.getString(0));
        assertEquals("b", list.getString(1));
        assertEquals("c", list.getString(2));

        // /other/x is outside the path — must be absent
        assertNull(result.getValue("other"), "/other/x must not appear in the result tree");
    }

    /**
     * Prefix re-root: building the store with {@code prefix = "app"} nests the entire result
     * under the {@code "app"} key.
     */
    @Test
    @DisplayName("Store get() with prefix 'app': result nested under {app:{...}}")
    void shouldApplyPrefixRerooting() throws Exception {
        JsonObject config = buildStoreConfig("/it/app/", true).put("prefix", "app");
        JsonObject result = getStoreResult(config);

        JsonObject app = result.getJsonObject("app");
        assertNotNull(app, "'app' prefix must wrap the result");
        JsonObject db = app.getJsonObject("db");
        assertNotNull(db, "db subtree must be nested under app");
        assertEquals(PASSWORD_SENTINEL, db.getString("password"), "sentinel must survive prefix re-root");
    }

    /**
     * End-to-end through {@link BootstrapConfigLoader}: declares the aws-ssm store and verifies:
     * <ol>
     *   <li>Store-provided {@code db.host} is present in the resolved tree.</li>
     *   <li>A key provided in the {@code --conf} overlay wins over the store value on collision.</li>
     *   <li>The SecureString {@code db.password} sentinel is present in the resolved tree (SSM
     *       decrypts it into the config tree; that is the expected behavior).</li>
     * </ol>
     */
    @Test
    @DisplayName("End-to-end: BootstrapConfigLoader merges store; --conf overlay wins on collision")
    void shouldLoadEndToEndThroughBootstrapLoader() {
        // The --conf overlay wins on collision. We use "db.host" as the colliding key:
        // the store provides HOST_SENTINEL; the overlay provides "override-host".
        JsonObject storeConfig = buildStoreConfig("/it/app/", true);
        JsonObject payload = new JsonObject().put("db", new JsonObject().put("host", "override-host"));
        JsonObject deploymentConfig = buildDeploymentConfig(storeConfig, payload);

        BootstrapConfigLoader.BootstrapResult result = BootstrapConfigLoader.load(deploymentConfig);

        // The overlay's db.host wins over the store's db-host value
        String host = result.config().getJsonObject("db").getString("host");
        assertEquals("override-host", host, "--conf overlay must win over store value on key collision");

        // The store-provided password is present (SecureString decrypted into the tree)
        String password = result.config().getJsonObject("db").getString("password");
        assertEquals(PASSWORD_SENTINEL, password, "SecureString sentinel must be present in the resolved tree");
    }

    /**
     * Recursive=false: only direct children of {@code /it/app/} are fetched; the deeply nested
     * parameter {@code /it/app/deep/nested/x} is absent from the result.
     *
     * <p>Direct children under {@code /it/app/} are: {@code db/password}, {@code db/host}, and
     * {@code features/list}. With {@code recursive=false} these are also absent (they are one level
     * deeper than the path). A direct child would be a parameter named exactly {@code /it/app/x}.
     * Since we have no such parameter, the result is empty when recursive is false — confirming
     * that non-direct-child parameters are excluded.
     */
    @Test
    @DisplayName("recursive=false: /it/app/deep/nested/x absent; only direct children returned")
    void shouldExcludeNestedParametersWhenRecursiveFalse() throws Exception {
        JsonObject result = getStoreResult(buildStoreConfig("/it/app/", false));

        // With recursive=false and no parameters directly under /it/app/ (all are nested),
        // the result must be empty.
        assertTrue(result.isEmpty(), "recursive=false with no direct children must yield empty tree");
    }

    /**
     * Empty path semantics: GetParametersByPath on a nonexistent path returns an empty list (not
     * an error). The store returns a succeeded Future carrying an empty JsonObject.
     */
    @Test
    @DisplayName("Empty path semantics: nonexistent path returns empty JsonObject (not an error)")
    void shouldReturnEmptyObjectForNonexistentPath() throws Exception {
        JsonObject result = getStoreResult(buildStoreConfig("/nonexistent/path/", true));

        assertTrue(result.isEmpty(), "A nonexistent path must return empty JsonObject, not fail");
    }

    /**
     * Failing store: a store declared with an unroutable endpoint causes
     * {@link BootstrapConfigLoader#load(JsonObject)} to fail with a
     * {@link BootstrapConfigException}. The SSM sentinel must not appear anywhere in the
     * exception chain.
     */
    @Test
    @DisplayName("Failing store (unroutable endpoint): BootstrapConfigException; sentinel absent")
    void shouldFailWithBootstrapConfigExceptionOnUnroutableEndpoint() {
        JsonObject storeConfig = new JsonObject()
                .put("path", "/it/app/")
                .put("region", "us-east-1")
                // Use port 1 — unroutable on any host, causes SDK connection failure
                .put("endpointOverride", "http://localhost:1")
                .put("connectTimeoutMs", 1000)
                .put("readTimeoutMs", 1000);
        JsonObject deploymentConfig = buildDeploymentConfig(storeConfig, null);

        BootstrapConfigException ex = assertThrows(
                BootstrapConfigException.class,
                () -> BootstrapConfigLoader.load(deploymentConfig),
                "Unroutable endpoint must cause BootstrapConfigException");

        // Sentinel must not appear in any part of the exception chain
        assertFalse(
                containsAnywhere(ex, PASSWORD_SENTINEL),
                "Exception chain must NOT contain the SSM password sentinel; was: " + exceptionChainText(ex));
    }

    /**
     * NFR-CONF-002 log redaction: captures log output from {@link SsmConfigStore} during a
     * successful load and asserts that the SSM sentinel value is absent from all log messages.
     * Restores the logger's original level in a {@code finally} block so other tests are not
     * affected.
     */
    @Test
    @DisplayName("NFR-CONF-002: SSM sentinel value absent from logs on successful store get()")
    void shouldNotLogParameterValues() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SsmConfigStore.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);

        try {
            getStoreResult(buildStoreConfig("/it/app/", true));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }

        List<String> logMessages = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            logMessages.add(event.getFormattedMessage());
        }

        for (String message : logMessages) {
            assertFalse(
                    message.contains(PASSWORD_SENTINEL),
                    "Log message must not contain SSM parameter value; found in: " + message);
        }
    }

    /**
     * Recursive=true with deep parameter: {@code /it/app/deep/nested/x} is included when
     * recursive is true (seeded in {@link #seedParameters()}), confirming the recursive flag
     * actually changes the result.
     */
    @Test
    @DisplayName("recursive=true: /it/app/deep/nested/x present in result tree")
    void shouldIncludeDeepParameterWhenRecursiveTrue() throws Exception {
        JsonObject result = getStoreResult(buildStoreConfig("/it/app/", true));

        JsonObject deep = result.getJsonObject("deep");
        assertNotNull(deep, "deep subtree must be present with recursive=true");
        JsonObject nested = deep.getJsonObject("nested");
        assertNotNull(nested, "deep.nested subtree must be present");
        assertEquals("deep-val", nested.getString("x"), "deep/nested/x must be present with recursive=true");
    }
}
