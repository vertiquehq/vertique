// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.MigrationException;
import dev.vertique.db.MigrationResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves how named Flyway instances are bound from configuration, resolved against the declared
 * migrations and the neutral schema placement, and rejected when they are invalid. Nothing here
 * needs a database: the runner is built through its package-private constructor with a recording
 * database-support double and a counting connector that fails every attempt.
 *
 * <p>One test factory carries the whole table of labelled cases, grouped by concern. Every
 * rejection case checks the same safety properties: the failure is a {@link MigrationRunException}
 * with a stable code and operation, it retains no cause or suppressed throwable, no secret sentinel
 * appears anywhere in it, and the runner has not touched a connection.
 */
class FlywayNamedConfigurationTest {

    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    // --- Location fixtures (classpath directories under src/test/resources/db/named-config) ---

    private static final String ALPHA = "classpath:db/named-config/alpha";
    private static final String ALPHA_V2 = "classpath:db/named-config/alpha-v2";
    private static final String BETA = "classpath:db/named-config/beta";
    private static final String GAMMA = "classpath:db/named-config/gamma";
    private static final String FALLBACK = "classpath:db/named-config/fallback";
    private static final String SHARED = "classpath:db/named-config/shared";
    private static final String SHARED_NESTED = "classpath:db/named-config/shared/nested";
    private static final String ABSENT = "classpath:db/named-config/absent";

    // --- Secret sentinels: every one carries the same marker so a single scan finds any leak ---

    private static final String SECRET_MARK = "s3cr3t";
    private static final String POOL_PASSWORD = "s3cr3t-pool-pw";
    private static final String ROOT_PASSWORD = "s3cr3t-root-pw";
    private static final String INSTANCE_PASSWORD = "s3cr3t-inst-pw";
    private static final String ROOT_PLACEHOLDER = "s3cr3t-root-ph";
    private static final String INSTANCE_PLACEHOLDER = "s3cr3t-inst-ph";

    private static final DbPoolConfig POOL = DbPoolConfig.builder()
            .host("localhost")
            .port(5432)
            .database("app")
            .user("pool_user")
            .password(POOL_PASSWORD)
            .build();

    private static final String EXPLICIT_URL = "jdbc:postgresql://db.example:5432/app";

    private static final Set<String> FAILURE_CODES =
            Set.of("CONFIGURATION", "RESOURCE", "COORDINATION", "PREFLIGHT", "MIGRATION", "VALIDATION");

    /** Root policy with every inheritable field set to a non-default value. */
    private static final String ROOT_POLICY = "'mode': 'VALIDATE', 'locations': ['" + FALLBACK + "'],"
            + " 'target': '42', 'schemas': ['shared', 'audit'],"
            + " 'placeholders': {'region': '" + ROOT_PLACEHOLDER + "', 'tier': '" + ROOT_PLACEHOLDER + "'},"
            + " 'outOfOrder': true, 'baselineOnMigrate': true, 'baselineVersion': '5',"
            + " 'validateOnMigrate': false, 'user': 'root_ddl', 'password': '" + ROOT_PASSWORD + "'";

    private static final String ALL_NULL_OVERRIDES = "'mode': null, 'locations': null, 'schemas': null,"
            + " 'table': null, 'target': null, 'outOfOrder': null, 'baselineOnMigrate': null,"
            + " 'baselineVersion': null, 'validateOnMigrate': null, 'placeholders': null,"
            + " 'user': null, 'password': null";

    private static Vertx vertx;

    @TempDir
    Path tmp;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @TestFactory
    Stream<DynamicNode> resolvesOverridesAndRejectsInvalidDeclarations() throws IOException {
        Path base = tmp.toRealPath();
        return Stream.of(
                dynamicContainer("raw instances key and parsed shape", rawInstancesKeyAndParsedShape()),
                dynamicContainer("unknown and malformed per-instance fields", malformedInstanceFields()),
                dynamicContainer("effective values: inheritance versus replacement", effectiveValues()),
                dynamicContainer("credentials as one atomic pair", credentials()),
                dynamicContainer("db.schemas agreement with Flyway lists", schemaAgreement()),
                dynamicContainer("declaration graph, names and ordering", declarationGraph()),
                dynamicContainer("history identity collisions", historyCollisions()),
                dynamicContainer("root mode ceiling", modeCeiling()),
                dynamicContainer("coordination lock timeout bounds", lockTimeout()),
                dynamicContainer("location rules", locationRules(base)),
                dynamicContainer("backend hook and connection-free resolution", backendHook()),
                dynamicContainer("run, migrate and legacy execution", execution()),
                dynamicContainer("secret redaction", secretRedaction()),
                dynamicContainer("failure shape", failureShape()));
    }

    // =====================================================================================
    // Raw instances key and parsed shape
    // =====================================================================================

    private List<DynamicTest> rawInstancesKeyAndParsedShape() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("no flyway section at all binds legacy defaults", () -> {
            FlywayConfig config = DbFlywayModule.flywayConfig(new JsonObject(), PARSER);

            assertNull(config.instances(), "legacy mode carries no instance list");
            assertEquals(FlywayMode.MIGRATE, config.mode());
            assertEquals(30_000L, config.coordinationLockTimeoutMs(), "documented default");
        }));

        tests.add(dynamicTest("instances absent selects legacy and keeps the legacy list", () -> {
            FlywayConfig config = new Scenario("{ 'schemas': ['other'] }").bind();

            assertNull(config.instances());
            assertEquals(List.of("other"), config.schemas());
        }));

        tests.add(dynamicTest("instances JSON null selects legacy exactly like absence", () -> {
            FlywayConfig config = new Scenario("{ 'instances': null }").bind();

            assertNull(config.instances());
        }));

        tests.add(dynamicTest("instances {} selects named mode: an empty but present list", () -> {
            FlywayConfig config = new Scenario("{ 'instances': {} }").bind();

            assertNotNull(config.instances(), "named mode must be distinguishable from legacy");
            assertTrue(config.instances().isEmpty());
        }));

        tests.add(dynamicTest("a misspelled root key is ignored and selects legacy", () -> {
            FlywayConfig config = new Scenario("{ 'instance': { 'job': {} } }").bind();

            assertNull(config.instances());
        }));

        tests.add(dynamicTest("unknown root keys stay ignored in legacy and named mode alike", () -> {
            FlywayConfig legacy = new Scenario("{ 'unknownRootKey': { 'a': 1 } }").bind();
            FlywayConfig named = new Scenario("{ 'unknownRootKey': 7, 'instances': { 'job': {} } }").bind();

            assertNull(legacy.instances());
            assertEquals(1, named.instances().size());
        }));

        for (String nonObject : List.of("'" + SECRET_MARK + "-text'", "[]", "7", "true", "[{'name': 'job'}]")) {
            tests.add(dynamicTest("instances value " + nonObject.replace(SECRET_MARK, "x") + " is invalid", () -> {
                Scenario scenario = new Scenario("{ 'instances': " + nonObject + " }");

                MigrationRunException ex = assertThrows(MigrationRunException.class, scenario::bind);

                assertEquals("CONFIGURATION", ex.failureCode());
                assertEquals("configuration", ex.operation());
                assertNull(ex.report(), "binding failures raise before any report exists");
                assertSafe(ex);
            }));
        }

        tests.add(dynamicTest("binding keeps every root field and attaches the parsed instances", () -> {
            String root = ROOT_POLICY + ", 'jdbcUrl': '" + EXPLICIT_URL + "', 'cleanDisabled': false,"
                    + " 'coordinationLockTimeoutMs': 1234, 'unknownRootKey': 'ignored'";
            FlywayConfig legacy = new Scenario("{ " + root + " }").bind();
            FlywayConfig named = new Scenario(flyway(root, "{ 'job': { 'mode': 'DISABLED' }, 'app': {} }")).bind();

            for (FlywayConfig config : List.of(legacy, named)) {
                assertEquals(FlywayMode.VALIDATE, config.mode());
                assertEquals(EXPLICIT_URL, config.jdbcUrl());
                assertEquals("root_ddl", config.user());
                assertEquals(ROOT_PASSWORD, config.password());
                assertEquals(List.of(FALLBACK), config.locations());
                assertEquals("42", config.target());
                assertEquals(List.of("shared", "audit"), config.schemas());
                assertEquals(Map.of("region", ROOT_PLACEHOLDER, "tier", ROOT_PLACEHOLDER), config.placeholders());
                assertTrue(config.outOfOrder());
                assertFalse(config.cleanDisabled());
                assertTrue(config.baselineOnMigrate());
                assertEquals("5", config.baselineVersion());
                assertFalse(config.validateOnMigrate());
                assertEquals(1234L, config.coordinationLockTimeoutMs());
            }
            assertNull(legacy.instances());
            assertEquals(
                    List.of("job", "app"),
                    named.instances().stream().map(FlywayInstanceConfig::name).toList());
            assertEquals(FlywayMode.DISABLED, named.instances().get(0).mode());
        }));

        tests.add(dynamicTest("instance fields keep omitted and null as absent, false and empty as values", () -> {
            FlywayConfig config = new Scenario(flyway(
                            "",
                            "{"
                                    + " 'bare': {},"
                                    + " 'nulls': {" + ALL_NULL_OVERRIDES + "},"
                                    + " 'explicit': { 'mode': 'VALIDATE', 'locations': ['" + BETA + "'], 'schemas': [],"
                                    + "   'table': 'custom_history', 'target': 'latest', 'outOfOrder': false,"
                                    + "   'baselineOnMigrate': false, 'baselineVersion': '0', 'validateOnMigrate': false,"
                                    + "   'placeholders': {}, 'user': '', 'password': '' } }"))
                    .bind();

            FlywayInstanceConfig bare = config.instances().get(0);
            FlywayInstanceConfig nulls = config.instances().get(1);
            FlywayInstanceConfig explicit = config.instances().get(2);

            for (FlywayInstanceConfig absent : List.of(bare, nulls)) {
                assertNull(absent.mode());
                assertNull(absent.locations());
                assertNull(absent.schemas());
                assertNull(absent.table());
                assertNull(absent.target());
                assertNull(absent.outOfOrder());
                assertNull(absent.baselineOnMigrate());
                assertNull(absent.baselineVersion());
                assertNull(absent.validateOnMigrate());
                assertNull(absent.placeholders());
                assertNull(absent.user());
                assertNull(absent.password());
            }
            assertEquals("bare", bare.name(), "the key becomes the identity");
            assertEquals(FlywayMode.VALIDATE, explicit.mode());
            assertEquals(List.of(BETA), explicit.locations());
            assertEquals(List.of(), explicit.schemas());
            assertEquals("custom_history", explicit.table());
            assertEquals("latest", explicit.target());
            assertEquals(Boolean.FALSE, explicit.outOfOrder());
            assertEquals(Boolean.FALSE, explicit.baselineOnMigrate());
            assertEquals("0", explicit.baselineVersion());
            assertEquals(Boolean.FALSE, explicit.validateOnMigrate());
            assertEquals(Map.of(), explicit.placeholders());
            assertEquals("", explicit.user());
            assertEquals("", explicit.password());
        }));

        return tests;
    }

    // =====================================================================================
    // Unknown and malformed per-instance fields
    // =====================================================================================

    private List<DynamicTest> malformedInstanceFields() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("every documented override field is accepted together", () -> {
            Scenario scenario = new Scenario(flyway(
                    "",
                    "{ 'job': { 'mode': 'MIGRATE', 'locations': ['" + ALPHA + "'], 'schemas': ['jobs'],"
                            + " 'table': 'job_history', 'target': '3', 'outOfOrder': true,"
                            + " 'baselineOnMigrate': true, 'baselineVersion': '0', 'validateOnMigrate': false,"
                            + " 'placeholders': {'k': 'v'}, 'user': 'u', 'password': 'p' } }"));

            assertEquals("job_history", only(scenario.views()).table());
        }));

        for (String unknown : List.of("schema", "jdbcUrl", "cleanDisabled", "dependsOn", "location")) {
            tests.add(dynamicTest("unknown per-instance key '" + unknown + "' is rejected, never aliased", () -> {
                Scenario scenario = new Scenario(flyway(
                        "",
                        "{ 'job': { '" + unknown + "': '" + SECRET_MARK + "-value', 'password': '" + INSTANCE_PASSWORD
                                + "' } }"));

                MigrationRunException ex = assertThrows(MigrationRunException.class, scenario::bind);

                assertEquals("CONFIGURATION", ex.failureCode());
                assertEquals("configuration", ex.operation());
                assertNull(ex.report());
                assertTrue(String.valueOf(ex.getMessage()).contains("job"), "names the offending instance");
                assertSafe(ex);
            }));
        }

        tests.add(dynamicTest("one unknown key among valid ones still rejects the whole object", () -> {
            Scenario scenario =
                    new Scenario(flyway("", "{ 'job': { 'mode': 'VALIDATE', 'table': 't', 'schemaa': 'x' } }"));

            MigrationRunException ex = assertThrows(MigrationRunException.class, scenario::bind);

            assertEquals("configuration", ex.operation());
        }));

        for (String badValue : List.of("'" + SECRET_MARK + "-text'", "7", "[]")) {
            tests.add(dynamicTest("instance entry value " + badValue.replace(SECRET_MARK, "x") + " is invalid", () -> {
                Scenario scenario = new Scenario(flyway("", "{ 'job': " + badValue + " }"));

                MigrationRunException ex = assertThrows(MigrationRunException.class, scenario::bind);

                assertEquals("CONFIGURATION", ex.failureCode());
                assertEquals("configuration", ex.operation());
                assertSafe(ex);
            }));
        }

        String instancePlaceholders = "'placeholders': {'k': '" + INSTANCE_PLACEHOLDER + "'}";
        Map<String, String> bindingFailures = new LinkedHashMap<>();
        bindingFailures.put(
                "enum value that is itself a secret", "'mode': '" + SECRET_MARK + "-mode', " + instancePlaceholders);
        bindingFailures.put("boolean that cannot be read", "'outOfOrder': 'nope', " + instancePlaceholders);
        bindingFailures.put("placeholders of the wrong shape", "'placeholders': ['x']");
        bindingFailures.put("schemas of the wrong shape", "'schemas': {'a': 1}, " + instancePlaceholders);
        bindingFailures.forEach((label, fields) -> tests.add(
                dynamicTest("binding failure with a password in the object: " + label + " leaves no cause", () -> {
                    Scenario scenario = new Scenario(
                            flyway("", "{ 'job': { " + fields + ", 'password': '" + INSTANCE_PASSWORD + "' } }"));

                    MigrationRunException ex = assertThrows(MigrationRunException.class, scenario::bind);

                    assertEquals("CONFIGURATION", ex.failureCode());
                    assertEquals("configuration", ex.operation());
                    assertNull(ex.getCause(), "no Jackson cause may be retained");
                    assertEquals(0, ex.getSuppressed().length);
                    assertNull(ex.report());
                    assertSafe(ex);
                })));

        tests.add(dynamicTest("legacy binding failures keep their original configuration exception", () -> {
            Scenario scenario = new Scenario("{ 'outOfOrder': 'nope' }");

            assertThrows(ConfigurationException.class, scenario::bind);
        }));

        return tests;
    }

    // =====================================================================================
    // Effective values
    // =====================================================================================

    private List<DynamicTest> effectiveValues() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(effective(
                "built-in defaults apply when neither root nor instance says anything", defaultsScenario(), views -> {
                    MigrationInstanceView view = only(views);
                    assertEquals("job", view.name());
                    assertEquals(FlywayMode.MIGRATE, view.mode());
                    assertEquals(List.of(ALPHA), view.locations());
                    assertEquals(List.of(), view.schemas());
                    assertNull(view.schema(), "no entry and no list: connection default, unqualified");
                    assertEquals("flyway_schema_history_job", view.table());
                    assertNull(view.target());
                    assertFalse(view.outOfOrder());
                    assertFalse(view.baselineOnMigrate());
                    assertEquals("1", view.baselineVersion());
                    assertTrue(view.validateOnMigrate());
                    assertEquals("pool_user", view.user());
                    assertEquals(CredentialSource.DB_POOL, view.credentialSource());
                    assertEquals(Set.of(), view.placeholderKeys());
                }));

        tests.add(effective(
                "omitted overrides inherit every root value, except locations", rootPolicy("{ 'job': {} }"), views -> {
                    MigrationInstanceView view = only(views);
                    assertEquals(FlywayMode.VALIDATE, view.mode());
                    assertEquals(List.of(ALPHA), view.locations(), "root locations are never inherited");
                    assertEquals(List.of("shared", "audit"), view.schemas());
                    assertEquals("shared", view.schema(), "first inherited entry is the default schema");
                    assertEquals("42", view.target());
                    assertTrue(view.outOfOrder());
                    assertTrue(view.baselineOnMigrate());
                    assertEquals("5", view.baselineVersion());
                    assertFalse(view.validateOnMigrate());
                    assertEquals(Set.of("region", "tier"), view.placeholderKeys());
                    assertEquals("root_ddl", view.user());
                    assertEquals(CredentialSource.ROOT_FLYWAY, view.credentialSource());
                }));

        tests.add(dynamicTest("JSON null overrides resolve exactly like omitted ones", () -> {
            List<MigrationInstanceView> omitted = rootPolicy("{ 'job': {} }").views();
            List<MigrationInstanceView> nulls =
                    rootPolicy("{ 'job': { " + ALL_NULL_OVERRIDES + " } }").views();

            assertEquals(omitted, nulls);
            assertEquals("42", only(nulls).target(), "null target inherits the root target");
        }));

        tests.add(effective(
                "explicit false, empty list and empty map replace root values instead of inheriting",
                rootPolicy("{ 'job': { 'outOfOrder': false, 'baselineOnMigrate': false, 'validateOnMigrate': true,"
                        + " 'schemas': [], 'placeholders': {}, 'target': 'latest', 'baselineVersion': '0',"
                        + " 'table': 'custom_history', 'locations': ['" + BETA + "'],"
                        + " 'user': 'inst_ddl', 'password': '" + INSTANCE_PASSWORD + "' } }"),
                views -> {
                    MigrationInstanceView view = only(views);
                    assertFalse(view.outOfOrder());
                    assertFalse(view.baselineOnMigrate());
                    assertTrue(view.validateOnMigrate());
                    assertEquals(List.of(), view.schemas(), "[] clears the root list");
                    assertNull(view.schema());
                    assertEquals(Set.of(), view.placeholderKeys(), "{} clears the root map");
                    assertEquals("latest", view.target(), "an explicit value replaces the root target");
                    assertEquals("0", view.baselineVersion());
                    assertEquals("custom_history", view.table());
                    assertEquals(List.of(BETA), view.locations());
                    assertEquals("inst_ddl", view.user());
                    assertEquals(CredentialSource.INSTANCE, view.credentialSource());
                }));

        tests.add(effective(
                "non-empty lists and maps replace the root ones in full, never merge",
                rootPolicy("{ 'job': { 'schemas': ['extra'], 'placeholders': {'zone': '" + INSTANCE_PLACEHOLDER
                        + "'}, 'locations': ['" + BETA + "', '" + GAMMA + "'] } }"),
                views -> {
                    MigrationInstanceView view = only(views);
                    assertEquals(List.of("extra"), view.schemas());
                    assertEquals("extra", view.schema());
                    assertEquals(Set.of("zone"), view.placeholderKeys());
                    assertEquals(List.of(BETA, GAMMA), view.locations(), "order is preserved");
                }));

        tests.add(effective(
                "explicit false defeats a built-in true default, and true defeats a root false",
                new Scenario(flyway(
                        "'outOfOrder': false, 'baselineOnMigrate': false, 'validateOnMigrate': false",
                        "{ 'job': { 'outOfOrder': true, 'baselineOnMigrate': true, 'validateOnMigrate': true } }")),
                views -> {
                    MigrationInstanceView view = only(views);
                    assertTrue(view.outOfOrder());
                    assertTrue(view.baselineOnMigrate());
                    assertTrue(view.validateOnMigrate());
                }));

        tests.add(effective(
                "explicit validateOnMigrate=false defeats the built-in default true",
                new Scenario(flyway("", "{ 'job': { 'validateOnMigrate': false } }")),
                views -> assertFalse(only(views).validateOnMigrate())));

        tests.add(effective(
                "explicit baseline version beats the root and the built-in default",
                new Scenario(flyway("'baselineVersion': '5'", "{ 'job': { 'baselineVersion': '0' } }")),
                views -> assertEquals("0", only(views).baselineVersion())));

        tests.add(effective(
                "instance target beats root target, which beats the latest default",
                new Scenario(flyway("'target': '42'", "{ 'job': { 'target': '7' } }")),
                views -> assertEquals("7", only(views).target())));

        tests.add(effective(
                "a hyphenated declaration gets an underscore history table by default",
                new Scenario(flyway("", "{ 'inbox-outbox': {} }")).definitions(def("inbox-outbox", ALPHA)),
                views -> assertEquals(
                        "flyway_schema_history_inbox_outbox", only(views).table())));

        tests.add(effective(
                "overrides of one instance never leak into its neighbour",
                new Scenario(flyway(
                                "'outOfOrder': true",
                                "{ 'app': { 'outOfOrder': false, 'table': 'app_history', 'target': '9' } }"))
                        .definitions(job(), def("app", BETA)),
                views -> {
                    MigrationInstanceView app = view(views, "app");
                    MigrationInstanceView job = view(views, "job");
                    assertFalse(app.outOfOrder());
                    assertEquals("app_history", app.table());
                    assertEquals("9", app.target());
                    assertTrue(job.outOfOrder());
                    assertEquals("flyway_schema_history_job", job.table());
                    assertNull(job.target());
                }));

        tests.add(effective(
                "an instance locations override replaces the declared locations in order",
                new Scenario(flyway(
                        "'locations': ['" + FALLBACK + "']",
                        "{ 'job': { 'locations': ['" + GAMMA + "', '" + BETA + "'] } }")),
                views -> assertEquals(List.of(GAMMA, BETA), only(views).locations())));

        return tests;
    }

    private static Scenario defaultsScenario() {
        return new Scenario(flyway("", "{ 'job': {} }"));
    }

    private static Scenario rootPolicy(String instances) {
        return new Scenario(flyway(ROOT_POLICY, instances));
    }

    // =====================================================================================
    // Credentials
    // =====================================================================================

    private List<DynamicTest> credentials() {
        List<DynamicTest> tests = new ArrayList<>();
        String instancePair = "'user': 'inst_ddl', 'password': '" + INSTANCE_PASSWORD + "'";
        String rootPair = "'user': 'root_ddl', 'password': '" + ROOT_PASSWORD + "'";

        tests.add(effective(
                "no Flyway credentials anywhere: the pool pair is used",
                new Scenario(flyway("", "{ 'job': {} }")),
                views -> {
                    assertEquals("pool_user", only(views).user());
                    assertEquals(CredentialSource.DB_POOL, only(views).credentialSource());
                }));
        tests.add(effective(
                "a complete root pair beats the pool pair", new Scenario(flyway(rootPair, "{ 'job': {} }")), views -> {
                    assertEquals("root_ddl", only(views).user());
                    assertEquals(CredentialSource.ROOT_FLYWAY, only(views).credentialSource());
                }));
        tests.add(effective(
                "a complete instance pair beats the root pair",
                new Scenario(flyway(rootPair, "{ 'job': { " + instancePair + " } }")),
                views -> {
                    assertEquals("inst_ddl", only(views).user());
                    assertEquals(CredentialSource.INSTANCE, only(views).credentialSource());
                }));
        tests.add(effective(
                "a complete instance pair beats the pool pair",
                new Scenario(flyway("", "{ 'job': { " + instancePair + " } }")),
                views -> {
                    assertEquals("inst_ddl", only(views).user());
                    assertEquals(CredentialSource.INSTANCE, only(views).credentialSource());
                }));
        tests.add(effective(
                "empty user and password are explicit values when both are present",
                new Scenario(flyway(rootPair, "{ 'job': { 'user': '', 'password': '' } }")),
                views -> {
                    assertEquals("", only(views).user());
                    assertEquals(CredentialSource.INSTANCE, only(views).credentialSource());
                }));
        tests.add(effective(
                "null user and password inherit the root pair",
                new Scenario(flyway(rootPair, "{ 'job': { 'user': null, 'password': null } }")),
                views -> {
                    assertEquals("root_ddl", only(views).user());
                    assertEquals(CredentialSource.ROOT_FLYWAY, only(views).credentialSource());
                }));

        tests.add(reject(
                        "instance user without password is a partial pair",
                        "{ 'instances': { 'job': { 'user': 'inst_ddl' } } }")
                .mentions("job")
                .test());
        tests.add(reject(
                        "instance password without user is a partial pair",
                        "{ 'instances': { 'job': { 'password': '" + INSTANCE_PASSWORD + "' } } }")
                .mentions("job")
                .test());
        tests.add(reject(
                        "instance user with JSON null password is a partial pair",
                        "{ 'instances': { 'job': { 'user': 'inst_ddl', 'password': null } } }")
                .mentions("job")
                .test());
        tests.add(reject(
                        "a partial instance pair is never completed from the root pair",
                        flyway(rootPair, "{ 'job': { 'user': 'inst_ddl' } }"))
                .mentions("job")
                .test());
        tests.add(reject(
                        "root user alone is a partial pair, not mixed with the pool password",
                        "{ 'user': 'root_ddl', 'instances': { 'job': {} } }")
                .test());
        tests.add(reject(
                        "root password alone is a partial pair, not mixed with the pool user",
                        "{ 'password': '" + ROOT_PASSWORD + "', 'instances': { 'job': {} } }")
                .test());

        return tests;
    }

    // =====================================================================================
    // db.schemas agreement
    // =====================================================================================

    private List<DynamicTest> schemaAgreement() {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, String> jobsEntry = Map.of("job", "jobs");

        tests.add(dynamicTest("legacy mode never checks the Flyway list against a db.schemas entry", () -> {
            Scenario scenario = new Scenario("{ 'schemas': ['other'] }").placement(jobsEntry);

            assertEquals(List.of(), scenario.runner().instances(), "legacy mode resolves no instance views");
            assertEquals(List.of("other"), scenario.bind().schemas(), "the legacy list passes unchanged");
            assertEquals(jobsEntry, scenario.schemas().mappings());
            assertNoDatabaseTouch(scenario);
            assertTrue(scenario.support.validatedBatches().isEmpty(), "legacy mode receives no new validation");
        }));

        tests.add(effective(
                "entry and no Flyway lists: the entry is the whole managed list",
                new Scenario(flyway("", "{ 'job': {} }")).placement(jobsEntry),
                views -> {
                    assertEquals(List.of("jobs"), only(views).schemas());
                    assertEquals("jobs", only(views).schema());
                }));
        tests.add(effective(
                "entry plus inherited root list: the entry first, the root entries as extras",
                new Scenario(flyway("'schemas': ['shared', 'audit']", "{ 'job': {} }")).placement(jobsEntry),
                views -> {
                    assertEquals(List.of("jobs", "shared", "audit"), only(views).schemas());
                    assertEquals("jobs", only(views).schema());
                }));
        tests.add(effective(
                "an entry already inside the inherited root list moves to the front without duplication",
                new Scenario(flyway("'schemas': ['shared', 'jobs', 'audit']", "{ 'job': {} }")).placement(jobsEntry),
                views -> assertEquals(
                        List.of("jobs", "shared", "audit"), only(views).schemas())));
        tests.add(effective(
                "entry plus an explicit list that agrees: the full list reaches Flyway",
                new Scenario(flyway("'schemas': ['ignored']", "{ 'job': { 'schemas': ['jobs', 'archive'] } }"))
                        .placement(jobsEntry),
                views -> {
                    assertEquals(List.of("jobs", "archive"), only(views).schemas());
                    assertEquals("jobs", only(views).schema());
                }));
        tests.add(effective(
                "entry plus an explicit list holding only the entry is kept as given",
                new Scenario(flyway("", "{ 'job': { 'schemas': ['jobs'] } }")).placement(jobsEntry),
                views -> assertEquals(List.of("jobs"), only(views).schemas())));
        tests.add(effective(
                "a hyphenated declaration finds its db.schemas entry by name",
                new Scenario(flyway("'schemas': ['audit']", "{ 'inbox-outbox': {} }"))
                        .definitions(def("inbox-outbox", ALPHA))
                        .placement(Map.of("inbox-outbox", "messaging")),
                views -> {
                    assertEquals(List.of("messaging", "audit"), only(views).schemas());
                    assertEquals("messaging", only(views).schema());
                }));
        tests.add(effective(
                "a db.schemas key without a Flyway instance is accepted and left untouched",
                new Scenario(flyway("", "{ 'job': {} }"))
                        .placement(Map.of("job", "jobs", "app", "app_schema", "ghost", "x")),
                views -> {
                    assertEquals(1, views.size());
                    assertEquals(List.of("jobs"), only(views).schemas());
                }));
        tests.add(effective(
                "each instance resolves its own entry and shares the root extras",
                new Scenario(flyway("'schemas': ['shared']", "{}"))
                        .definitions(job(), def("app", BETA))
                        .placement(Map.of("job", "jobs", "app", "app_schema")),
                views -> {
                    assertEquals(
                            List.of("app_schema", "shared"), view(views, "app").schemas());
                    assertEquals(List.of("jobs", "shared"), view(views, "job").schemas());
                }));
        tests.add(effective(
                "an instance without an entry stays unqualified next to one that has an entry",
                new Scenario(flyway("", "{}"))
                        .definitions(job(), def("app", BETA))
                        .placement(jobsEntry),
                views -> {
                    assertEquals(List.of(), view(views, "app").schemas());
                    assertNull(view(views, "app").schema());
                    assertEquals("jobs", view(views, "job").schema());
                }));

        tests.add(effective(
                "no entry and no lists: no implicit public default",
                new Scenario(flyway("", "{ 'job': {} }")),
                views -> {
                    assertEquals(List.of(), only(views).schemas());
                    assertNull(only(views).schema());
                }));
        tests.add(effective(
                "no entry, explicit list: its first entry is the default, no placement",
                new Scenario(flyway("", "{ 'job': { 'schemas': ['x', 'y'] } }")),
                views -> {
                    assertEquals(List.of("x", "y"), only(views).schemas());
                    assertEquals("x", only(views).schema());
                }));
        tests.add(effective(
                "no entry, inherited root list: its first entry is the default",
                new Scenario(flyway("'schemas': ['x', 'y']", "{ 'job': {} }")),
                views -> {
                    assertEquals(List.of("x", "y"), only(views).schemas());
                    assertEquals("x", only(views).schema());
                }));
        tests.add(effective(
                "no entry, explicit empty list clears the root list",
                new Scenario(flyway("'schemas': ['x', 'y']", "{ 'job': { 'schemas': [] } }")),
                views -> {
                    assertEquals(List.of(), only(views).schemas());
                    assertNull(only(views).schema());
                }));

        tests.add(reject(
                        "entry with an explicit list naming another schema",
                        "{ 'instances': { 'job': { 'schemas': ['other'] } } }")
                .placement(jobsEntry)
                .mentions("job")
                .test());
        tests.add(reject("entry with an explicit empty list", "{ 'instances': { 'job': { 'schemas': [] } } }")
                .placement(jobsEntry)
                .mentions("job")
                .test());
        tests.add(reject(
                        "entry that is not the first entry of an explicit list",
                        "{ 'instances': { 'job': { 'schemas': ['archive', 'jobs'] } } }")
                .placement(jobsEntry)
                .mentions("job")
                .test());
        tests.add(reject(
                        "duplicate entries inside an explicit list that starts with the entry",
                        "{ 'instances': { 'job': { 'schemas': ['jobs', 'archive', 'archive'] } } }")
                .placement(jobsEntry)
                .mentions("job")
                .test());
        tests.add(reject(
                        "duplicate entries in an inherited root list next to an entry",
                        "{ 'schemas': ['shared', 'shared'], 'instances': { 'job': {} } }")
                .placement(jobsEntry)
                .mentions("job")
                .test());
        tests.add(reject(
                        "duplicate entries inside an explicit list without an entry",
                        "{ 'instances': { 'job': { 'schemas': ['x', 'x'] } } }")
                .mentions("job")
                .test());
        tests.add(reject(
                        "duplicate entries in an inherited root list without an entry",
                        "{ 'schemas': ['x', 'y', 'x'], 'instances': { 'job': {} } }")
                .mentions("job")
                .test());

        return tests;
    }

    // =====================================================================================
    // Declaration graph, names and ordering
    // =====================================================================================

    private List<DynamicTest> declarationGraph() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("order is topological with lexicographic tie-break, whatever the input order", () -> {
            List<MigrationDefinition> declared = List.of(
                    def("zebra", ALPHA),
                    def("apple", BETA, "zebra"),
                    def("mango", GAMMA),
                    def("kiwi", FALLBACK, "mango"));
            List<MigrationDefinition> reversed = new ArrayList<>(declared);
            Collections.reverse(reversed);

            for (List<MigrationDefinition> input : List.of(declared, reversed)) {
                List<MigrationInstanceView> views =
                        new Scenario(flyway("", "{}")).definitions(input).views();

                assertEquals(List.of("mango", "kiwi", "zebra", "apple"), names(views));
            }
        }));

        tests.add(dynamicTest("a diamond dependency orders root, then siblings by name, then the top", () -> {
            List<MigrationInstanceView> views = new Scenario(flyway("", "{}"))
                    .definitions(
                            def("top", ALPHA, "right", "left"),
                            def("right", BETA, "base"),
                            def("left", GAMMA, "base"),
                            def("base", FALLBACK))
                    .views();

            assertEquals(List.of("base", "left", "right", "top"), names(views));
        }));

        tests.add(dynamicTest("a disabled dependency is still ordered ahead of its dependent", () -> {
            List<MigrationInstanceView> views = new Scenario(flyway("", "{ 'zeta-base': { 'mode': 'DISABLED' } }"))
                    .definitions(def("alpha-feature", ALPHA, "zeta-base"), def("zeta-base", BETA))
                    .views();

            assertEquals(List.of("zeta-base", "alpha-feature"), names(views));
            assertEquals(FlywayMode.DISABLED, view(views, "zeta-base").mode());
            assertEquals(FlywayMode.MIGRATE, view(views, "alpha-feature").mode());
        }));

        tests.add(dynamicTest("valid identifiers of one to thirty-two characters are accepted", () -> {
            String longest = "a" + "b".repeat(31);
            List<MigrationInstanceView> views = new Scenario(flyway("", "{}"))
                    .definitions(def("a", ALPHA), def("a0-9", BETA), def("inbox-outbox", GAMMA), def(longest, FALLBACK))
                    .views();

            assertEquals(Set.of("a", "a0-9", "inbox-outbox", longest), Set.copyOf(names(views)));
        }));

        tests.add(reject("dependency on an undeclared name", "{ 'instances': {} }")
                .definitions(def("job", ALPHA, "missing"))
                .mentions("job")
                .test());
        tests.add(reject("self dependency", "{ 'instances': {} }")
                .definitions(def("job", ALPHA, "job"))
                .mentions("job")
                .test());
        tests.add(reject("two-step dependency cycle", "{ 'instances': {} }")
                .definitions(def("job", ALPHA, "app"), def("app", BETA, "job"))
                .test());
        tests.add(reject("three-step dependency cycle", "{ 'instances': {} }")
                .definitions(def("a", ALPHA, "b"), def("b", BETA, "c"), def("c", GAMMA, "a"))
                .test());
        tests.add(reject("two declarations sharing one name", "{ 'instances': {} }")
                .definitions(def("job", ALPHA), def("job", BETA))
                .test());
        tests.add(reject("an override for a name no declaration carries", "{ 'instances': { 'ghost': {} } }")
                .mentions("ghost")
                .test());
        tests.add(reject(
                        "an unwired override next to a valid one",
                        "{ 'instances': { 'job': {}, 'ghost': { 'mode': 'VALIDATE' } } }")
                .mentions("ghost")
                .test());
        tests.add(reject("named mode with no declarations and a MIGRATE root", "{ 'instances': {} }")
                .definitions()
                .test());
        tests.add(
                reject("named mode with no declarations and a VALIDATE root", "{ 'mode': 'VALIDATE', 'instances': {} }")
                        .definitions()
                        .test());

        for (String invalid : List.of("Job", "1job", "-job", "job_x", "job.x", "job x", "a" + "b".repeat(32))) {
            tests.add(reject("declaration name '" + invalid + "' breaks the identifier pattern", "{ 'instances': {} }")
                    .definitions(def(invalid, ALPHA))
                    .codes("CONFIGURATION")
                    .at("configuration", "instances")
                    .test());
        }
        tests.add(reject("declaration named legacy is reserved", "{ 'instances': {} }")
                .definitions(def("legacy", ALPHA))
                .at("configuration", "instances")
                .test());
        tests.add(reject("override key Job breaks the identifier pattern", "{ 'instances': { 'Job': {} } }")
                .at("configuration", "instances")
                .test());
        tests.add(reject("override key legacy is reserved", "{ 'instances': { 'legacy': {} } }")
                .at("configuration", "instances")
                .test());

        return tests;
    }

    // =====================================================================================
    // History identity collisions
    // =====================================================================================

    private List<DynamicTest> historyCollisions() {
        List<DynamicTest> tests = new ArrayList<>();
        List<MigrationDefinition> two = List.of(job(), def("app", BETA));

        tests.add(effective(
                "a shared schema is fine while the history tables differ",
                new Scenario(flyway("", "{}")).definitions(two).placement(Map.of("job", "shared", "app", "shared")),
                views -> {
                    assertEquals("shared", view(views, "job").schema());
                    assertEquals("shared", view(views, "app").schema());
                    assertNotEquals(
                            view(views, "job").table(), view(views, "app").table());
                }));
        tests.add(effective(
                "the same table in different schemas is fine",
                new Scenario(flyway("", "{ 'job': { 'table': 'history' }, 'app': { 'table': 'history' } }"))
                        .definitions(two)
                        .placement(Map.of("job", "jobs", "app", "app_schema")),
                views -> assertEquals(2, views.size())));
        tests.add(effective(
                "the same table with unknown database defaults is deferred to preflight",
                new Scenario(flyway("", "{ 'job': { 'table': 'history' }, 'app': { 'table': 'history' } }"))
                        .definitions(two),
                views -> assertEquals(2, views.size())));
        tests.add(effective(
                "the same table with one known and one unknown schema cannot be proven equal",
                new Scenario(flyway("", "{ 'job': { 'table': 'history' }, 'app': { 'table': 'history' } }"))
                        .definitions(two)
                        .placement(Map.of("job", "jobs")),
                views -> assertEquals(2, views.size())));

        tests.add(reject(
                        "the same table in the same entry schema",
                        "{ 'instances': { 'job': { 'table': 'history' }, 'app': { 'table': 'history' } } }")
                .definitions(two)
                .placement(Map.of("job", "shared", "app", "shared"))
                .test());
        tests.add(reject(
                        "the same table in the same default schema taken from explicit lists",
                        "{ 'instances': { 'job': { 'table': 'history', 'schemas': ['x'] },"
                                + " 'app': { 'table': 'history', 'schemas': ['x', 'y'] } } }")
                .definitions(two)
                .test());
        tests.add(reject(
                        "an entry schema and an explicit-list default naming the same schema",
                        "{ 'instances': { 'job': { 'table': 'history' },"
                                + " 'app': { 'table': 'history', 'schemas': ['shared'] } } }")
                .definitions(two)
                .placement(Map.of("job", "shared"))
                .test());

        return tests;
    }

    // =====================================================================================
    // Root mode ceiling
    // =====================================================================================

    private List<DynamicTest> modeCeiling() {
        List<DynamicTest> tests = new ArrayList<>();
        // root mode (null = key omitted, so the MIGRATE default applies) -> instance modes it permits
        Map<String, Set<String>> permitted = new LinkedHashMap<>();
        permitted.put("MIGRATE", Set.of("MIGRATE", "VALIDATE", "DISABLED"));
        permitted.put("VALIDATE", Set.of("VALIDATE", "DISABLED"));
        permitted.put("DISABLED", Set.of("DISABLED"));
        permitted.put(null, Set.of("MIGRATE", "VALIDATE", "DISABLED"));

        for (Map.Entry<String, Set<String>> root : permitted.entrySet()) {
            String rootField = root.getKey() == null ? "" : "'mode': '" + root.getKey() + "'";
            String rootLabel = root.getKey() == null ? "omitted root mode (MIGRATE)" : "root " + root.getKey();
            FlywayMode effectiveRoot = FlywayMode.valueOf(root.getKey() == null ? "MIGRATE" : root.getKey());

            tests.add(effective(
                    rootLabel + ": an omitted instance mode inherits the root mode",
                    ceilingScenario(rootField, effectiveRoot, "{ 'job': {} }"),
                    views -> assertEquals(effectiveRoot, only(views).mode())));

            for (String instance : List.of("MIGRATE", "VALIDATE", "DISABLED")) {
                if (root.getValue().contains(instance)) {
                    tests.add(effective(
                            rootLabel + ": instance " + instance + " is permitted",
                            ceilingScenario(rootField, effectiveRoot, "{ 'job': { 'mode': '" + instance + "' } }"),
                            views -> assertEquals(
                                    FlywayMode.valueOf(instance), only(views).mode())));
                } else {
                    // The location is missing too: the ceiling must fail first, as a configuration error.
                    tests.add(reject(
                                    rootLabel + ": instance " + instance
                                            + " exceeds the ceiling before any resource probe",
                                    flyway(
                                            rootField,
                                            "{ 'job': { 'mode': '" + instance + "', 'locations': ['" + ABSENT
                                                    + "'] } }"))
                            .mentions("job")
                            .test());
                }
            }
        }
        return tests;
    }

    private static Scenario ceilingScenario(String rootField, FlywayMode root, String instances) {
        Scenario scenario = new Scenario(flyway(rootField, instances));
        return root == FlywayMode.DISABLED ? scenario.skipBackendAssertion() : scenario;
    }

    // =====================================================================================
    // Coordination lock timeout
    // =====================================================================================

    private List<DynamicTest> lockTimeout() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("the timeout defaults to 30000 ms", () -> {
            assertEquals(
                    30_000L, new Scenario(flyway("", "{ 'job': {} }")).bind().coordinationLockTimeoutMs());
        }));
        for (long valid : new long[] {1L, 30_000L, 300_000L}) {
            tests.add(effective(
                    "timeout " + valid + " ms is valid",
                    new Scenario(flyway("'coordinationLockTimeoutMs': " + valid, "{ 'job': {} }")),
                    views -> assertEquals(1, views.size())));
        }
        for (long invalid : new long[] {0L, 300_001L, -1L, Long.MAX_VALUE}) {
            tests.add(dynamicTest("timeout " + invalid + " ms binds but is rejected on resolution", () -> {
                Scenario scenario = new Scenario(flyway("'coordinationLockTimeoutMs': " + invalid, "{ 'job': {} }"));

                assertEquals(invalid, scenario.bind().coordinationLockTimeoutMs(), "FlywayConfig itself stays passive");
                assertRejected(
                        scenario, Set.of("CONFIGURATION"), Set.of("instances"), "coordinationLockTimeoutMs", true);
            }));
        }
        return tests;
    }

    // =====================================================================================
    // Location rules
    // =====================================================================================

    private List<DynamicTest> locationRules(Path base) {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(effective(
                "classpath sibling prefixes alpha and alpha-v2 do not overlap",
                new Scenario(flyway("", "{}")).definitions(def("job", ALPHA), def("app", ALPHA_V2)),
                views -> {
                    assertEquals(List.of(ALPHA), view(views, "job").locations());
                    assertEquals(List.of(ALPHA_V2), view(views, "app").locations());
                }));

        tests.add(dynamicTest(
                "filesystem sibling directories db/migration/job and db/migration/job-v2 do not overlap", () -> {
                    Path job = directory(base, "siblings/db/migration/job");
                    Path jobV2 = directory(base, "siblings/db/migration/job-v2");
                    Scenario scenario = new Scenario(flyway(
                                    "",
                                    "{ 'job': { 'locations': ['filesystem:" + job + "'] },"
                                            + " 'app': { 'locations': ['filesystem:" + jobV2 + "'] } }"))
                            .definitions(job(), def("app", BETA));

                    List<MigrationInstanceView> views = scenario.views();

                    assertEquals(
                            job.toRealPath(), realPathOf(only(view(views, "job").locations())));
                    assertEquals(
                            jobV2.toRealPath(),
                            realPathOf(only(view(views, "app").locations())));
                    assertWellFormed(scenario, views);
                }));

        tests.add(dynamicTest("classpath and filesystem locations mix inside one instance", () -> {
            Path dir = directory(base, "mixed/one");
            Scenario scenario =
                    new Scenario(flyway("", "{ 'job': { 'locations': ['" + ALPHA + "', 'filesystem:" + dir + "'] } }"));

            List<String> locations = only(scenario.views()).locations();

            assertEquals(2, locations.size());
            assertEquals(ALPHA, locations.get(0));
            assertEquals(dir.toRealPath(), realPathOf(locations.get(1)));
        }));

        tests.add(reject("missing classpath directory", flyway("", "{ 'job': { 'locations': ['" + ABSENT + "'] } }"))
                .codes("RESOURCE")
                .mayReachBackend()
                .mentions("job")
                .test());
        tests.add(reject("missing classpath directory declared by the module itself", "{ 'instances': {} }")
                .definitions(def("job", ABSENT))
                .codes("RESOURCE")
                .mayReachBackend()
                .mentions("job")
                .test());
        tests.add(dynamicTest("missing filesystem directory", () -> {
            Path missing = base.resolve("never-created");
            assertRejected(
                    new Scenario(flyway("", "{ 'job': { 'locations': ['filesystem:" + missing + "'] } }")),
                    Set.of("RESOURCE"),
                    Set.of("instances"),
                    "job",
                    false);
        }));

        for (String wildcard : List.of(
                "classpath:db/named-config/*",
                "classpath:db/named-config/alph?",
                "classpath:db/**/alpha",
                "filesystem:/tmp/*")) {
            tests.add(reject(
                            "wildcard location " + wildcard,
                            flyway("", "{ 'job': { 'locations': ['" + wildcard + "'] } }"))
                    .mayReachBackend()
                    .mentions("job")
                    .test());
        }
        for (String dotted : List.of(
                "classpath:db/named-config/./alpha",
                "classpath:db/named-config/../named-config/alpha",
                "classpath:../named-config/alpha")) {
            tests.add(reject(
                            "dot-segment location " + dotted,
                            flyway("", "{ 'job': { 'locations': ['" + dotted + "'] } }"))
                    .mayReachBackend()
                    .mentions("job")
                    .test());
        }
        tests.add(reject(
                        "duplicate location inside one instance",
                        flyway("", "{ 'job': { 'locations': ['" + ALPHA + "', '" + ALPHA + "'] } }"))
                .mayReachBackend()
                .mentions("job")
                .test());
        tests.add(reject("empty locations override", "{ 'instances': { 'job': { 'locations': [] } } }")
                .mayReachBackend()
                .mentions("job")
                .test());
        tests.add(reject(
                        "unsupported location scheme",
                        flyway("", "{ 'job': { 'locations': ['s3:bucket/migrations'] } }"))
                .codes("CONFIGURATION", "RESOURCE")
                .mayReachBackend()
                .mentions("job")
                .test());

        tests.add(reject(
                        "ancestor and descendant inside one instance",
                        flyway("", "{ 'job': { 'locations': ['" + SHARED + "', '" + SHARED_NESTED + "'] } }"))
                .codes("CONFIGURATION", "RESOURCE")
                .mayReachBackend()
                .mentions("job")
                .test());
        tests.add(reject("ancestor and descendant across two instances", "{ 'instances': {} }")
                .definitions(def("job", SHARED), def("app", SHARED_NESTED))
                .codes("CONFIGURATION", "RESOURCE")
                .mayReachBackend()
                .test());
        tests.add(reject("the same location claimed by two instances", "{ 'instances': {} }")
                .definitions(def("job", ALPHA), def("app", ALPHA))
                .codes("CONFIGURATION", "RESOURCE")
                .mayReachBackend()
                .test());

        tests.add(dynamicTest("filesystem ancestor and descendant overlap", () -> {
            Path parent = directory(base, "overlap/parent");
            Path child = directory(base, "overlap/parent/child");
            assertRejected(
                    new Scenario(flyway(
                                    "",
                                    "{ 'job': { 'locations': ['filesystem:" + parent + "'] },"
                                            + " 'app': { 'locations': ['filesystem:" + child + "'] } }"))
                            .definitions(job(), def("app", BETA)),
                    Set.of("CONFIGURATION", "RESOURCE"),
                    Set.of("instances"),
                    null,
                    false);
        }));
        tests.add(dynamicTest("two spellings of one real directory overlap after normalization", () -> {
            Path real = directory(base, "alias/real");
            Path link = base.resolve("alias/link");
            try {
                Files.createSymbolicLink(link, real);
            } catch (IOException | UnsupportedOperationException ex) {
                assumeTrue(false, "symbolic links are not available here");
            }
            assertRejected(
                    new Scenario(flyway(
                                    "",
                                    "{ 'job': { 'locations': ['filesystem:" + real + "'] },"
                                            + " 'app': { 'locations': ['filesystem:" + link + "'] } }"))
                            .definitions(job(), def("app", BETA)),
                    Set.of("CONFIGURATION", "RESOURCE"),
                    Set.of("instances"),
                    null,
                    false);
        }));

        return tests;
    }

    // =====================================================================================
    // Backend hook and connection-free resolution
    // =====================================================================================

    private List<DynamicTest> backendHook() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("instances() hands the resolved views to the backend and opens nothing", () -> {
            Scenario scenario = new Scenario(flyway("'jdbcUrl': '" + EXPLICIT_URL + "'", "{}"))
                    .definitions(job(), def("app", BETA, "job"));

            List<MigrationInstanceView> views = scenario.views();

            assertEquals(1, scenario.support.validatedBatches().size(), "validated exactly once");
            assertEquals(views, scenario.support.validatedBatches().get(0));
            assertEquals(List.of("job", "app"), names(views));
            assertFalse(scenario.selector.selectedUrls().isEmpty());
            assertTrue(scenario.selector.selectedUrls().stream().allMatch(EXPLICIT_URL::equals));
            assertNoDatabaseTouch(scenario);
        }));

        tests.add(dynamicTest(
                "a backend rejection of the views surfaces from instances() as a configuration failure", () -> {
                    Scenario scenario = new Scenario(flyway("", "{ 'job': {} }"));
                    scenario.support.failValidationWith(new MigrationRunException(
                            "schema name rejected by the backend", "CONFIGURATION", "instances", null));

                    MigrationRunException ex = assertThrows(
                            MigrationRunException.class, () -> scenario.runner().instances());

                    assertEquals("CONFIGURATION", ex.failureCode());
                    assertEquals("instances", ex.operation());
                    assertNull(ex.report());
                    assertEquals(1, scenario.support.validatedBatches().size());
                    assertSafe(ex);
                    assertNoDatabaseTouch(scenario);
                }));

        tests.add(dynamicTest("resolution is repeatable and never alters the db.schemas it was given", () -> {
            Map<String, String> placement = new LinkedHashMap<>(Map.of("job", "jobs", "app", "app_schema"));
            Scenario scenario = new Scenario(flyway("'schemas': ['shared']", "{}"))
                    .definitions(job(), def("app", BETA))
                    .placement(placement);
            FlywayMigrationRunner runner = scenario.runner();

            List<MigrationInstanceView> first = runner.instances();
            List<MigrationInstanceView> second = runner.instances();

            assertEquals(first, second);
            assertEquals(Map.copyOf(placement), scenario.schemas().mappings());
            assertEquals(2, scenario.support.validatedBatches().size());
        }));

        tests.add(effective(
                "root DISABLED resolves names and views without any connection",
                new Scenario(flyway("'mode': 'DISABLED'", "{}"))
                        .definitions(job(), def("app", BETA, "job"))
                        .skipBackendAssertion(),
                views -> {
                    assertEquals(List.of("job", "app"), names(views));
                    assertTrue(views.stream().allMatch(v -> v.mode() == FlywayMode.DISABLED));
                }));
        tests.add(
                dynamicTest("root DISABLED with no declarations resolves to an empty list without a connection", () -> {
                    Scenario scenario = new Scenario(flyway("'mode': 'DISABLED'", "{}")).definitions();

                    assertEquals(List.of(), scenario.runner().instances());
                    assertNoDatabaseTouch(scenario);
                }));
        tests.add(reject(
                        "root DISABLED still enforces the db.schemas agreement",
                        "{ 'mode': 'DISABLED', 'instances': { 'job': { 'schemas': ['other'] } } }")
                .placement(Map.of("job", "jobs"))
                .mentions("job")
                .test());
        tests.add(reject("root DISABLED still rejects an unknown dependency", "{ 'mode': 'DISABLED', 'instances': {} }")
                .definitions(def("job", ALPHA, "missing"))
                .mentions("job")
                .test());

        return tests;
    }

    // =====================================================================================
    // Run, migrate and legacy execution
    // =====================================================================================

    private List<DynamicTest> execution() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("root DISABLED run reports every declaration as DISABLED and connects to nothing", () -> {
            Scenario scenario =
                    new Scenario(flyway("'mode': 'DISABLED'", "{}")).definitions(job(), def("app", BETA, "job"));

            MigrationReport report = succeeded(scenario.runner().run(vertx));

            assertEquals(
                    List.of("job", "app"),
                    report.instances().stream().map(r -> r.instance().name()).toList());
            for (MigrationInstanceResult result : report.instances()) {
                assertEquals(MigrationStatus.DISABLED, result.status());
                assertEquals(0, result.migrationsApplied());
                assertNull(result.currentVersion());
            }
            assertNoDatabaseTouch(scenario);
        }));

        tests.add(dynamicTest("root DISABLED run probes no resource: a missing location does not fail it", () -> {
            Scenario scenario = new Scenario(flyway("'mode': 'DISABLED'", "{}")).definitions(def("job", ABSENT));

            MigrationReport report = succeeded(scenario.runner().run(vertx));

            assertEquals(MigrationStatus.DISABLED, only(report.instances()).status());
            assertNoDatabaseTouch(scenario);
        }));

        tests.add(dynamicTest("root DISABLED migrate applies nothing and has no target version", () -> {
            Scenario scenario = new Scenario(flyway("'mode': 'DISABLED'", "{}")).definitions(job());

            MigrationResult result = succeeded(scenario.runner().migrate(vertx));

            assertEquals(0, result.migrationsApplied());
            assertNull(result.targetVersion());
            assertNoDatabaseTouch(scenario);
        }));

        tests.add(dynamicTest("run repeats the resource check on a worker and reports every entry NOT_RUN", () -> {
            Scenario scenario = new Scenario(flyway("", "{}")).definitions(job(), def("app", ABSENT, "job"));

            Throwable failure = failureOf(scenario.runner().run(vertx));

            MigrationRunException ex = assertInstanceOf(MigrationRunException.class, failure);
            assertEquals("RESOURCE", ex.failureCode());
            assertEquals("run", ex.operation());
            assertNotNull(ex.report(), "run failures carry the ordered report");
            assertEquals(
                    List.of("job", "app"),
                    ex.report().instances().stream()
                            .map(r -> r.instance().name())
                            .toList());
            assertTrue(ex.report().instances().stream().allMatch(r -> r.status() == MigrationStatus.NOT_RUN));
            assertSafe(ex);
            assertNoDatabaseTouch(scenario);
        }));

        tests.add(dynamicTest("run repeats the db.schemas agreement check before any connection", () -> {
            Scenario scenario =
                    new Scenario(flyway("", "{ 'job': { 'schemas': ['other'] } }")).placement(Map.of("job", "jobs"));

            Throwable failure = failureOf(scenario.runner().run(vertx));

            MigrationRunException ex = assertInstanceOf(MigrationRunException.class, failure);
            assertEquals("CONFIGURATION", ex.failureCode());
            assertEquals("run", ex.operation());
            assertSafe(ex);
            assertNoDatabaseTouch(scenario);
        }));

        tests.add(dynamicTest("named migrate fails the same way as run, with a report", () -> {
            Scenario scenario = new Scenario(flyway("", "{}")).definitions(def("job", ABSENT));

            Throwable failure = failureOf(scenario.runner().migrate(vertx));

            MigrationRunException ex = assertInstanceOf(MigrationRunException.class, failure);
            assertEquals("RESOURCE", ex.failureCode());
            assertNotNull(ex.report());
            assertSafe(ex);
            assertNoDatabaseTouch(scenario);
        }));

        tests.add(dynamicTest("a backend that cannot open leaves no cause, no secret and every entry NOT_RUN", () -> {
            String jdbcSecret = "could not connect to " + EXPLICIT_URL + "?password=" + SECRET_MARK + "-url as "
                    + SECRET_MARK + "-user";
            Scenario scenario = new Scenario(flyway(
                            "'jdbcUrl': '" + EXPLICIT_URL + "', 'user': 'root_ddl', 'password': '" + ROOT_PASSWORD
                                    + "'",
                            "{ 'job': { 'user': 'inst_ddl', 'password': '" + INSTANCE_PASSWORD + "' } }"))
                    .definitions(job(), def("app", BETA, "job"));
            scenario.support.failOpenWith(new SQLException(jdbcSecret, "08001"));

            Throwable failure = failureOf(scenario.runner().run(vertx));

            MigrationRunException ex = assertInstanceOf(MigrationRunException.class, failure);
            assertTrue(Set.of("COORDINATION", "PREFLIGHT").contains(ex.failureCode()), ex.failureCode());
            assertTrue(Set.of("run", "coordination").contains(ex.operation()), ex.operation());
            assertNotNull(ex.report());
            assertEquals(
                    List.of("job", "app"),
                    ex.report().instances().stream()
                            .map(r -> r.instance().name())
                            .toList());
            assertTrue(ex.report().instances().stream().allMatch(r -> r.status() == MigrationStatus.NOT_RUN));
            assertTrue(ex.report().instances().stream().allMatch(r -> r.migrationsApplied() == 0));
            assertSafe(ex);

            List<RecordingDatabaseSupport.Open> opens = scenario.support.opens();
            assertEquals(1, opens.size(), "one guarded session for the whole batch");
            assertEquals(EXPLICIT_URL, opens.get(0).jdbcUrl());
            assertEquals("root_ddl", opens.get(0).user(), "the guard uses the root pair, never an instance pair");
            assertEquals(ROOT_PASSWORD, opens.get(0).password());
            assertEquals(0, scenario.connector.attempts());
        }));

        tests.add(dynamicTest("an unsupported destination fails the named run before any connection", () -> {
            Scenario scenario =
                    new Scenario(flyway("'jdbcUrl': '" + EXPLICIT_URL + "'", "{ 'job': {} }")).unsupportedDestination();

            Throwable failure = failureOf(scenario.runner().run(vertx));

            MigrationRunException ex = assertInstanceOf(MigrationRunException.class, failure);
            assertTrue(FAILURE_CODES.contains(ex.failureCode()));
            assertSafe(ex);
            assertNoDatabaseTouch(scenario);
        }));

        tests.add(dynamicTest("a legacy runner resolves no views and probes nothing when disabled", () -> {
            Scenario scenario = new Scenario("{ 'mode': 'DISABLED', 'schemas': ['other'] }")
                    .definitions(def("job", ABSENT))
                    .placement(Map.of("job", "jobs"));

            FlywayMigrationRunner runner = scenario.runner();
            MigrationResult result = succeeded(runner.migrate(vertx));

            assertEquals(List.of(), runner.instances());
            assertEquals(0, result.migrationsApplied());
            assertNull(result.targetVersion());
            assertNoDatabaseTouch(scenario);
            assertTrue(scenario.support.validatedBatches().isEmpty());
        }));

        tests.add(dynamicTest(
                "a legacy run fails with the legacy exception and opens no guard or probe connection", () -> {
                    Scenario scenario = new Scenario(
                                    "{ 'jdbcUrl': 'jdbc:postgresql://127.0.0.1:1/none', 'locations': ['" + ALPHA
                                            + "'] }")
                            .definitions(job());

                    Throwable failure = failureOf(scenario.runner().migrate(vertx));

                    assertInstanceOf(MigrationException.class, failure);
                    assertFalse(failure instanceof MigrationRunException, "legacy exception behavior is unchanged");
                    assertNoDatabaseTouch(scenario);
                    assertTrue(scenario.support.validatedBatches().isEmpty());
                    assertTrue(scenario.support.preflights().isEmpty());
                }));

        return tests;
    }

    // =====================================================================================
    // Secret redaction
    // =====================================================================================

    private List<DynamicTest> secretRedaction() {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, ObjectMapper> mappers = new LinkedHashMap<>();
        mappers.put("plain", new ObjectMapper());
        mappers.put("framework", DefaultConfigMapper.lenient());

        tests.add(dynamicTest("FlywayInstanceConfig.toString hides the password and the placeholder values", () -> {
            String rendered = secretInstance().toString();

            assertFalse(rendered.contains(SECRET_MARK), rendered);
            assertTrue(rendered.contains("job"));
        }));

        tests.add(dynamicTest("FlywayConfig.toString and its builder stay safe with instances present", () -> {
            FlywayConfig config = FlywayConfig.builder()
                    .user("root_ddl")
                    .password(ROOT_PASSWORD)
                    .placeholders(Map.of("region", ROOT_PLACEHOLDER))
                    .instances(List.of(secretInstance()))
                    .build();

            assertFalse(config.toString().contains(SECRET_MARK), config.toString());
            assertFalse(FlywayConfig.builder()
                    .password(ROOT_PASSWORD)
                    .placeholders(Map.of("region", ROOT_PLACEHOLDER))
                    .instances(List.of(secretInstance()))
                    .toString()
                    .contains(SECRET_MARK));
        }));

        mappers.forEach((kind, mapper) -> {
            tests.add(dynamicTest(
                    "Jackson never serializes either configuration type's secrets (" + kind + " mapper)", () -> {
                        FlywayConfig config = FlywayConfig.builder()
                                .user("root_ddl")
                                .password(ROOT_PASSWORD)
                                .placeholders(Map.of("region", ROOT_PLACEHOLDER))
                                .instances(List.of(secretInstance()))
                                .build();

                        String instanceJson = mapper.writeValueAsString(secretInstance());
                        String configJson = mapper.writeValueAsString(config);

                        assertFalse(instanceJson.contains(SECRET_MARK), instanceJson);
                        assertFalse(configJson.contains(SECRET_MARK), configJson);
                        assertTrue(instanceJson.contains("\"name\":\"job\""), "non-secret fields still serialize");
                        assertTrue(instanceJson.contains("\"user\":\"inst_ddl\""));
                    }));

            tests.add(dynamicTest(
                    "Jackson still accepts secret input for both configuration types (" + kind + " mapper)", () -> {
                        FlywayInstanceConfig instance = mapper.readValue(
                                "{\"name\":\"job\",\"user\":\"inst_ddl\",\"password\":\"" + INSTANCE_PASSWORD + "\","
                                        + "\"placeholders\":{\"zone\":\"" + INSTANCE_PLACEHOLDER + "\"}}",
                                FlywayInstanceConfig.class);
                        FlywayConfig config =
                                mapper.readValue("{\"password\":\"" + ROOT_PASSWORD + "\"}", FlywayConfig.class);

                        assertEquals(INSTANCE_PASSWORD, instance.password());
                        assertEquals(Map.of("zone", INSTANCE_PLACEHOLDER), instance.placeholders());
                        assertEquals(ROOT_PASSWORD, config.password());
                    }));
        });

        tests.add(dynamicTest("the canonical parser keeps instance secrets readable for the runner", () -> {
            FlywayConfig config = new Scenario(flyway(
                            "",
                            "{ 'job': { 'password': '" + INSTANCE_PASSWORD + "', 'user': 'inst_ddl',"
                                    + " 'placeholders': {'zone': '" + INSTANCE_PLACEHOLDER + "'} } }"))
                    .bind();

            assertEquals(INSTANCE_PASSWORD, config.instances().get(0).password());
            assertEquals(
                    Map.of("zone", INSTANCE_PLACEHOLDER),
                    config.instances().get(0).placeholders());
        }));

        tests.add(dynamicTest("views carry the user, the credential source and placeholder keys, never a value", () -> {
            Scenario scenario = new Scenario(flyway(
                            "'user': 'root_ddl', 'password': '" + ROOT_PASSWORD + "', 'placeholders': {'region': '"
                                    + ROOT_PLACEHOLDER + "'}",
                            "{ 'job': { 'user': 'inst_ddl', 'password': '" + INSTANCE_PASSWORD
                                    + "', 'placeholders': {'zone': '" + INSTANCE_PLACEHOLDER + "'} },"
                                    + " 'app': {} }"))
                    .definitions(job(), def("app", BETA));

            List<MigrationInstanceView> views = scenario.views();

            assertEquals(Set.of("zone"), view(views, "job").placeholderKeys());
            assertEquals("inst_ddl", view(views, "job").user());
            assertEquals(CredentialSource.INSTANCE, view(views, "job").credentialSource());
            assertEquals(Set.of("region"), view(views, "app").placeholderKeys());
            assertEquals(CredentialSource.ROOT_FLYWAY, view(views, "app").credentialSource());
            for (MigrationInstanceView view : views) {
                assertFalse(String.valueOf(view).contains(SECRET_MARK), String.valueOf(view));
            }
        }));

        return tests;
    }

    private static FlywayInstanceConfig secretInstance() {
        return new FlywayInstanceConfig(
                "job",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("zone", INSTANCE_PLACEHOLDER),
                "inst_ddl",
                INSTANCE_PASSWORD);
    }

    // =====================================================================================
    // Failure shape
    // =====================================================================================

    private List<DynamicTest> failureShape() {
        List<DynamicTest> tests = new ArrayList<>();

        for (String code : FAILURE_CODES) {
            tests.add(dynamicTest("failure code " + code + " round-trips with its operation and report", () -> {
                MigrationReport report = new MigrationReport(List.of(new MigrationInstanceResult(
                        only(defaultsScenario().views()), MigrationStatus.NOT_RUN, 0, null)));

                MigrationRunException ex = new MigrationRunException("run failed for job", code, "run", report);

                assertEquals(code, ex.failureCode());
                assertEquals("run", ex.operation());
                assertEquals(report, ex.report());
                assertEquals("run failed for job", ex.getMessage());
                assertInstanceOf(MigrationException.class, ex);
                assertNull(ex.getCause(), "a named failure never retains a third-party cause");
                assertEquals(0, ex.getSuppressed().length);
            }));
        }

        tests.add(dynamicTest("a failure raised before any report exists reports null", () -> {
            MigrationRunException ex =
                    new MigrationRunException("resolution failed", "CONFIGURATION", "instances", null);

            assertNull(ex.report());
            assertEquals("instances", ex.operation());
        }));

        tests.add(dynamicTest("coordination failures name the coordination operation", () -> {
            MigrationRunException ex =
                    new MigrationRunException("lock not acquired", "COORDINATION", "coordination", null);

            assertEquals("COORDINATION", ex.failureCode());
            assertEquals("coordination", ex.operation());
        }));

        return tests;
    }

    // =====================================================================================
    // Trust boundaries of the named configuration
    // =====================================================================================

    @Test
    void rejectsPartialRootCredentialPairEvenWhenEveryInstanceOverridesIt() throws Exception {
        // Given a root flyway.user without flyway.password (and the mirror case), while every named
        // instance carries its own complete user and password pair
        String instances = "{ 'job': { 'user': 'job_ddl', 'password': '" + INSTANCE_PASSWORD + "' },"
                + " 'app': { 'user': 'app_ddl', 'password': '" + INSTANCE_PASSWORD + "' } }";
        Map<String, String> partialRootPairs = new LinkedHashMap<>();
        partialRootPairs.put("flyway.user without flyway.password", "'user': 'root_ddl'");
        partialRootPairs.put("flyway.password without flyway.user", "'password': '" + ROOT_PASSWORD + "'");

        for (Map.Entry<String, String> partial : partialRootPairs.entrySet()) {
            String label = partial.getKey();
            Supplier<Scenario> scenario =
                    () -> new Scenario(flyway(partial.getValue(), instances)).definitions(job(), def("app", BETA));

            // When the instances are resolved
            Scenario resolving = scenario.get();
            MigrationRunException resolved = assertThrows(
                    MigrationRunException.class, () -> resolving.runner().instances(), label);

            // Then the partial root pair is rejected before the backend is asked or any connection opens
            assertPartialRootPairRejected(label, resolved, "instances", resolving);

            // When the same configuration runs
            Scenario running = scenario.get();
            Throwable failure = failureOf(running.runner().run(vertx));

            // Then the run is rejected the same way, still without a connection
            assertPartialRootPairRejected(label, failure, "run", running);
        }

        // And a complete root pair, or no root pair at all, still resolves with the instance pairs
        String instanceOnly = flyway("", instances);
        String completeRoot = flyway("'user': 'root_ddl', 'password': '" + ROOT_PASSWORD + "'", instances);
        for (String json : List.of(instanceOnly, completeRoot)) {
            Scenario scenario = new Scenario(json).definitions(job(), def("app", BETA));

            List<MigrationInstanceView> views = scenario.views();

            assertEquals(List.of("app", "job"), names(views), "no dependencies: lexicographic order");
            assertEquals("job_ddl", view(views, "job").user());
            assertEquals("app_ddl", view(views, "app").user());
            assertTrue(views.stream().allMatch(v -> v.credentialSource() == CredentialSource.INSTANCE));
            assertWellFormed(scenario, views);
        }
    }

    private static void assertPartialRootPairRejected(
            String label, Throwable failure, String operation, Scenario scenario) {
        MigrationRunException ex = assertInstanceOf(
                MigrationRunException.class, failure, label + " (" + operation + "): observed " + failure);
        assertEquals("CONFIGURATION", ex.failureCode(), label);
        assertEquals(operation, ex.operation(), label);
        String message = String.valueOf(ex.getMessage());
        assertTrue(message.contains("flyway.user"), label + ": message must name flyway.user: " + message);
        assertTrue(message.contains("flyway.password"), label + ": message must name flyway.password: " + message);
        assertTrue(
                message.matches("(?is).*(together|both).*"),
                label + ": message must say the two are required together: " + message);
        assertSafe(ex);
        assertNoDatabaseTouch(scenario);
        assertTrue(
                scenario.support.validatedBatches().isEmpty(),
                label + ": the backend must not be asked about a configuration that is already invalid");
    }

    @Test
    void sealsTheDatabaseSupportTrustBoundary() throws Exception {
        // Given database support that breaks its contract by throwing a plain RuntimeException that
        // carries a secret-looking message and a cause, and a selector that throws one from select(url)
        String firstSecret = "s3cr3t-backend-first";
        String secondSecret = "s3cr3t-backend-second";
        Map<String, Function<RuntimeException, Scenario>> faults = new LinkedHashMap<>();
        faults.put("validateConfiguration throws", failure -> {
            Scenario scenario = new Scenario(flyway("", "{ 'job': {} }"));
            scenario.support.failValidationWithRuntime(failure);
            return scenario;
        });
        faults.put("select throws", failure -> {
            Scenario scenario = new Scenario(flyway("", "{ 'job': {} }"));
            scenario.selector = scenario.support.selector().failSelectWith(failure);
            return scenario;
        });

        for (Map.Entry<String, Function<RuntimeException, Scenario>> fault : faults.entrySet()) {
            String label = fault.getKey();
            RuntimeException first = new IllegalStateException(
                    "backend exploded with " + firstSecret,
                    new SQLException("jdbc:postgresql://db.example:5432/app?password=" + firstSecret));
            RuntimeException second =
                    new UnsupportedOperationException("a different failure with " + secondSecret, first);

            // When the instances are resolved
            MigrationRunException resolvedFirst =
                    assertSealed(label, resolveFailure(fault.getValue().apply(first)), "instances", false, firstSecret);
            MigrationRunException resolvedSecond = assertSealed(
                    label, resolveFailure(fault.getValue().apply(second)), "instances", false, secondSecret);

            // Then the public exception is the framework's fixed one, whatever the backend threw
            assertEquals(
                    resolvedFirst.getMessage(),
                    resolvedSecond.getMessage(),
                    label + ": the message must not depend on the backend failure");

            // When the same configuration runs
            MigrationRunException ranFirst =
                    assertSealed(label, runFailure(fault.getValue().apply(first)), "run", true, firstSecret);
            MigrationRunException ranSecond =
                    assertSealed(label, runFailure(fault.getValue().apply(second)), "run", true, secondSecret);

            // Then the run reports the same fixed message and every declared instance as not run
            assertEquals(
                    ranFirst.getMessage(),
                    ranSecond.getMessage(),
                    label + ": the run message must not depend on the backend failure");
        }

        // Given database support that rejects the views with its own exception carrying a suppressed one
        String suppressedSecret = "s3cr3t-backend-suppressed";
        for (String operation : List.of("instances", "run")) {
            MigrationRunException backend =
                    new MigrationRunException("the backend rejected an identifier", "CONFIGURATION", "instances", null);
            backend.addSuppressed(new SQLException("jdbc:postgresql://db.example/app?password=" + suppressedSecret));
            Scenario scenario = new Scenario(flyway("", "{ 'job': {} }"));
            scenario.support.failValidationWith(backend);

            // When the operation fails
            Throwable failure = operation.equals("run") ? runFailure(scenario) : resolveFailure(scenario);

            // Then the caller gets a rebuilt exception: same code, nothing suppressed, no leaked secret
            MigrationRunException publicFailure =
                    assertInstanceOf(MigrationRunException.class, failure, operation + ": observed " + failure);
            assertNotSame(backend, publicFailure, operation + ": the backend's own exception must not escape");
            assertEquals(backend.failureCode(), publicFailure.failureCode());
            assertEquals(operation, publicFailure.operation());
            assertEquals(0, publicFailure.getSuppressed().length, operation + ": suppressed throwable retained");
            assertSafe(publicFailure);
            assertEquals(1, scenario.support.validatedBatches().size(), operation);
            assertNoDatabaseTouch(scenario);
        }
    }

    private static MigrationRunException assertSealed(
            String label, Throwable failure, String operation, boolean withReport, String rawSecret) {
        MigrationRunException ex = assertInstanceOf(
                MigrationRunException.class, failure, label + " (" + operation + "): observed " + failure);
        assertEquals("CONFIGURATION", ex.failureCode(), label);
        assertEquals(operation, ex.operation(), label);
        String message = String.valueOf(ex.getMessage());
        assertTrue(
                message.startsWith("Named migration " + operation + " failed"),
                label + ": the message must be framework-authored and name the operation: " + message);
        assertFalse(message.contains(rawSecret), label + ": the raw backend secret leaked: " + message);
        assertFalse(message.contains("backend exploded"), label + ": the raw backend message leaked: " + message);
        assertFalse(message.contains("different failure"), label + ": the raw backend message leaked: " + message);
        assertFalse(message.contains("Exception"), label + ": a backend exception type leaked: " + message);
        assertSafe(ex);
        if (withReport) {
            MigrationReport report = ex.report();
            assertNotNull(report, label + ": the views were resolved, so the report must list them");
            assertEquals(
                    List.of("job"),
                    report.instances().stream().map(r -> r.instance().name()).toList());
            assertTrue(report.instances().stream().allMatch(r -> r.status() == MigrationStatus.NOT_RUN));
        } else {
            assertNull(ex.report(), label + ": no report exists before a run");
        }
        return ex;
    }

    private static Throwable resolveFailure(Scenario scenario) {
        return assertThrows(Throwable.class, () -> scenario.runner().instances(), "instances() must be rejected");
    }

    private static Throwable runFailure(Scenario scenario) {
        return failureOf(scenario.runner().run(vertx));
    }

    @Test
    void rejectsNullRootModeInNamedMode() throws Exception {
        // Given a named configuration whose root mode is an explicit JSON null: the canonical parser
        // binds it to null rather than to the MIGRATE default, which would be the most permissive ceiling
        FlywayConfig bound = new Scenario(flyway("'mode': null", "{}")).bind();
        assertNull(bound.mode(), "the canonical parser keeps an explicit null root mode");
        assertNotNull(bound.instances(), "the configuration is still named");

        // When the instances are resolved
        Scenario resolving = new Scenario(flyway("'mode': null", "{ 'job': {} }"));
        MigrationRunException resolved = assertThrows(
                MigrationRunException.class, () -> resolving.runner().instances());

        // Then the null mode is rejected, naming flyway.mode, instead of acting as the MIGRATE ceiling
        assertNullModeRejected(resolved, "instances", resolving);

        // When the same configuration runs
        Scenario running = new Scenario(flyway("'mode': null", "{ 'job': {} }"));
        Throwable failure = failureOf(running.runner().run(vertx));

        // Then the run is rejected the same way, before any connection
        assertNullModeRejected(failure, "run", running);
    }

    private static void assertNullModeRejected(Throwable failure, String operation, Scenario scenario) {
        MigrationRunException ex =
                assertInstanceOf(MigrationRunException.class, failure, operation + ": observed " + failure);
        assertEquals("CONFIGURATION", ex.failureCode(), operation);
        assertEquals(operation, ex.operation());
        assertTrue(
                String.valueOf(ex.getMessage()).contains("flyway.mode"),
                operation + ": message must name flyway.mode: " + ex.getMessage());
        assertSafe(ex);
        assertNoDatabaseTouch(scenario);
        assertTrue(
                scenario.support.validatedBatches().isEmpty(),
                operation + ": the backend must not be asked about a configuration that is already invalid");
    }

    @Test
    void namedRootParseFailureRetainsNoSecretAndNoJacksonCause() {
        // Given named configurations whose root section cannot be bound, each carrying a secret
        // sentinel in a value that the native binding error would echo or nest
        Map<String, String> unreadableRoots = new LinkedHashMap<>();
        unreadableRoots.put("password given as an array", "{ 'instances': {}, 'password': ['" + ROOT_PASSWORD + "'] }");
        unreadableRoots.put(
                "placeholder value given as an object",
                "{ 'instances': {}, 'placeholders': { 'k': { 'x': '" + ROOT_PLACEHOLDER + "' } } }");
        unreadableRoots.put(
                "lock timeout that is not a number",
                "{ 'instances': {}, 'coordinationLockTimeoutMs': '" + SECRET_MARK + "-text' }");
        unreadableRoots.put("mode that is not a mode", "{ 'instances': {}, 'mode': '" + SECRET_MARK + "-mode' }");
        unreadableRoots.put("wrong-typed flag", "{ 'instances': {}, 'outOfOrder': ['" + SECRET_MARK + "-flag'] }");

        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<String, String> root : unreadableRoots.entrySet()) {
            checks.add(() -> {
                // When the section is bound
                Throwable failure = bindFailure(root.getValue());

                // Then the failure is a configuration failure with no cause and no echoed value
                MigrationRunException ex = assertInstanceOf(
                        MigrationRunException.class,
                        failure,
                        root.getKey() + ": observed " + failure + " with cause " + failure.getCause());
                assertEquals("CONFIGURATION", ex.failureCode(), root.getKey());
                assertEquals("configuration", ex.operation(), root.getKey());
                assertNull(ex.report(), root.getKey());
                assertTrue(
                        String.valueOf(ex.getMessage()).contains("flyway"),
                        root.getKey() + ": message must name the flyway section: " + ex.getMessage());
                assertSafe(ex);
            });
        }

        // Then every case holds
        assertAll(checks);

        // And the legacy path keeps its existing exception behavior for the same unreadable value
        assertThrows(
                ConfigurationException.class, () -> new Scenario("{ 'password': ['" + ROOT_PASSWORD + "'] }").bind());
    }

    private static Throwable bindFailure(String flywayJson) {
        try {
            new Scenario(flywayJson).bind();
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("the unreadable root section was accepted: " + flywayJson.replace(SECRET_MARK, "x"));
    }

    // =====================================================================================
    // Fixtures and assertions
    // =====================================================================================

    private static MigrationDefinition def(String name, String location, String... dependsOn) {
        return new MigrationDefinition(name, List.of(location), List.of(dependsOn));
    }

    private static MigrationDefinition job() {
        return def("job", ALPHA);
    }

    /** Builds a flyway section from root fields (may be empty) and a raw instances value; quotes may be single. */
    private static String flyway(String rootFields, String instances) {
        String root = rootFields.isBlank() ? "" : rootFields + ", ";
        return "{ " + root + "'instances': " + instances + " }";
    }

    private static Path directory(Path base, String relative) throws IOException {
        Path dir = Files.createDirectories(base.resolve(relative));
        Files.writeString(dir.resolve("V1__fixture.sql"), "SELECT 1;\n");
        return dir;
    }

    private static Path realPathOf(String location) throws IOException {
        assertTrue(location.startsWith("filesystem:"), location);
        return Path.of(location.substring("filesystem:".length())).toRealPath();
    }

    private static <T> T only(List<T> list) {
        assertEquals(1, list.size(), String.valueOf(list));
        return list.get(0);
    }

    private static MigrationInstanceView view(List<MigrationInstanceView> views, String name) {
        return views.stream()
                .filter(v -> v.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no view named " + name + " in " + names(views)));
    }

    private static List<String> names(List<MigrationInstanceView> views) {
        return views.stream().map(MigrationInstanceView::name).toList();
    }

    private static <T> T succeeded(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) {
        ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS));
        return ex.getCause();
    }

    /** A resolved-and-valid case: the views are checked by {@code check}, then for the shared safety properties. */
    private static DynamicTest effective(String label, Scenario scenario, Consumer<List<MigrationInstanceView>> check) {
        return dynamicTest(label, () -> {
            List<MigrationInstanceView> views = scenario.views();
            check.accept(views);
            assertWellFormed(scenario, views);
        });
    }

    private static void assertWellFormed(Scenario scenario, List<MigrationInstanceView> views) {
        assertThrows(UnsupportedOperationException.class, () -> views.add(null), "the view list is immutable");
        for (MigrationInstanceView view : views) {
            assertThrows(
                    UnsupportedOperationException.class, () -> view.schemas().add("x"));
            assertThrows(
                    UnsupportedOperationException.class, () -> view.locations().add("x"));
            assertThrows(UnsupportedOperationException.class, () -> view.placeholderKeys()
                    .add("x"));
            assertFalse(String.valueOf(view).contains(SECRET_MARK), "views never carry a secret: " + view);
        }
        assertNoDatabaseTouch(scenario);
        assertEquals(Map.copyOf(scenario.placement), scenario.schemas().mappings(), "db.schemas is never altered");
        if (!scenario.skipBackendAssertion && !views.isEmpty()) {
            List<List<MigrationInstanceView>> batches = scenario.support.validatedBatches();
            assertFalse(batches.isEmpty(), "the backend must be asked to validate the views");
            assertEquals(views, batches.get(batches.size() - 1));
        }
    }

    private static void assertNoDatabaseTouch(Scenario scenario) {
        assertEquals(0, scenario.connector.attempts(), "the runner opened a JDBC connection of its own");
        assertTrue(scenario.support.opens().isEmpty(), "the backend must not open a session");
    }

    /** A named-mode failure must be safe: stable code, no cause, nothing suppressed, no secret anywhere. */
    private static void assertSafe(Throwable failure) {
        assertNull(failure.getCause(), "no third-party cause may be retained");
        assertEquals(0, failure.getSuppressed().length, "no suppressed throwable may be retained");
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SECRET_MARK), "a secret leaked into the failure chain");
        if (failure instanceof MigrationRunException run) {
            assertTrue(FAILURE_CODES.contains(run.failureCode()), "unknown failure code " + run.failureCode());
            assertNotNull(run.operation());
            assertFalse(String.valueOf(run.report()).contains(SECRET_MARK), "a secret leaked into the report");
        }
    }

    private static void assertRejected(
            Scenario scenario, Set<String> codes, Set<String> operations, String mentions, boolean beforeBackend) {
        MigrationRunException ex = assertThrows(
                MigrationRunException.class, () -> scenario.runner().instances());

        assertTrue(codes.contains(ex.failureCode()), "failure code " + ex.failureCode() + " not in " + codes);
        assertTrue(operations.contains(ex.operation()), "operation " + ex.operation() + " not in " + operations);
        assertNull(ex.report(), "no report exists before a run");
        if (mentions != null) {
            assertTrue(String.valueOf(ex.getMessage()).contains(mentions), "message does not identify " + mentions);
        }
        assertSafe(ex);
        assertNoDatabaseTouch(scenario);
        if (beforeBackend) {
            assertTrue(
                    scenario.support.validatedBatches().isEmpty(),
                    "an in-memory rejection must happen before the backend is asked");
        }
    }

    private static Reject reject(String label, String flywayJson) {
        return new Reject(label, new Scenario(flywayJson));
    }

    /** One rejection case; defaults to a configuration failure from {@code instances()} before the backend. */
    private static final class Reject {
        private final String label;
        private final Scenario scenario;
        private Set<String> codes = Set.of("CONFIGURATION");
        private Set<String> operations = Set.of("instances");
        private String mentions;
        private boolean beforeBackend = true;

        private Reject(String label, Scenario scenario) {
            this.label = label;
            this.scenario = scenario;
        }

        Reject definitions(MigrationDefinition... definitions) {
            scenario.definitions(definitions);
            return this;
        }

        Reject definitions(List<MigrationDefinition> definitions) {
            scenario.definitions(definitions);
            return this;
        }

        Reject placement(Map<String, String> placement) {
            scenario.placement(placement);
            return this;
        }

        Reject codes(String... codes) {
            this.codes = Set.of(codes);
            return this;
        }

        Reject at(String... operations) {
            this.operations = Set.of(operations);
            return this;
        }

        Reject mentions(String text) {
            this.mentions = text;
            return this;
        }

        Reject mayReachBackend() {
            this.beforeBackend = false;
            return this;
        }

        DynamicTest test() {
            return dynamicTest(label, () -> assertRejected(scenario, codes, operations, mentions, beforeBackend));
        }
    }

    /** One configuration, its declarations and its doubles; binds through the canonical parser. */
    private static final class Scenario {
        private final String flywayJson;
        private List<MigrationDefinition> definitions = List.of(job());
        private Map<String, String> placement = Map.of();
        private final RecordingDatabaseSupport support = new RecordingDatabaseSupport();
        private RecordingDatabaseSupport.Selector selector = support.selector();
        private final RecordingDatabaseSupport.CountingConnector connector =
                new RecordingDatabaseSupport.CountingConnector();
        private DbSchemas schemas;
        private boolean skipBackendAssertion;

        Scenario(String flywayJson) {
            this.flywayJson = flywayJson.replace('\'', '"');
        }

        Scenario definitions(MigrationDefinition... definitions) {
            this.definitions = List.of(definitions);
            return this;
        }

        Scenario definitions(List<MigrationDefinition> definitions) {
            this.definitions = List.copyOf(definitions);
            return this;
        }

        Scenario placement(Map<String, String> placement) {
            this.placement = placement;
            return this;
        }

        Scenario unsupportedDestination() {
            this.selector = RecordingDatabaseSupport.unsupportedDestination();
            return this;
        }

        /** For configurations where reaching the backend is not part of the contract (root DISABLED). */
        Scenario skipBackendAssertion() {
            this.skipBackendAssertion = true;
            return this;
        }

        DbSchemas schemas() {
            if (schemas == null) {
                schemas = new DbSchemas(placement);
            }
            return schemas;
        }

        FlywayConfig bind() {
            JsonObject app = new JsonObject().put("flyway", new JsonObject(flywayJson));
            return DbFlywayModule.flywayConfig(app, PARSER);
        }

        FlywayMigrationRunner runner() {
            return new FlywayMigrationRunner(
                    bind(), POOL, new LinkedHashSet<>(definitions), schemas(), selector, connector);
        }

        List<MigrationInstanceView> views() {
            return runner().instances();
        }
    }
}
