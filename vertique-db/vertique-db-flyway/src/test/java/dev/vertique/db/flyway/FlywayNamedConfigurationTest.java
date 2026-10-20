// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import dev.vertique.db.MigrationResult;
import dev.vertique.db.flyway.MigrationRunException.Stage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * Proves how named Flyway instances are bound from configuration, resolved against the declared
 * migrations and the neutral schema placement, and rejected when they are invalid. Nothing here needs a
 * database: resolution never opens a connection or reads a migration resource, which the tests observe
 * through a listening socket that counts every connection made to it.
 *
 * <p>One test factory carries the table of labelled cases, grouped by concern. A configuration that
 * cannot be bound fails earlier, in the framework's config parser, with a {@link ConfigurationException}
 * whose message names the instance and key where it can and never contains a configuration value. A
 * resolution rejection is a {@link MigrationRunException} with {@link Stage#CONFIGURATION}, no report and
 * no secret anywhere in it.
 */
class FlywayNamedConfigurationTest {

    private static final ConfigParser PARSER = new DefaultConfigParser(DefaultConfigMapper.lenient());

    // --- Locations: resolution only compares them, so no resource has to exist ---

    private static final String ALPHA = "classpath:db/alpha";
    private static final String ALPHA_V2 = "classpath:db/alpha-v2";
    private static final String BETA = "classpath:db/beta";
    private static final String GAMMA = "classpath:db/gamma";
    private static final String FALLBACK = "classpath:db/fallback";

    // --- Secret sentinels: every one carries the same marker so a single scan finds any leak ---

    private static final String SECRET_MARK = "s3cr3t";
    private static final String POOL_PASSWORD = "s3cr3t-pool-pw";
    private static final String ROOT_PASSWORD = "s3cr3t-root-pw";
    private static final String INSTANCE_PASSWORD = "s3cr3t-inst-pw";
    private static final String ROOT_PLACEHOLDER = "s3cr3t-root-ph";
    private static final String INSTANCE_PLACEHOLDER = "s3cr3t-inst-ph";

    private static final DbPoolConfig POOL = DbPoolConfig.builder()
            .host("127.0.0.1")
            .port(1)
            .database("app")
            .user("pool_user")
            .password(POOL_PASSWORD)
            .build();

    private static final String EXPLICIT_URL = "jdbc:postgresql://db.example:5432/app";

    /** Root policy with every inheritable field set to a non-default value. */
    private static final String ROOT_POLICY = "'mode': 'VALIDATE', 'locations': ['" + FALLBACK + "'],"
            + " 'target': '42', 'schemas': ['shared', 'audit'],"
            + " 'placeholders': {'region': '" + ROOT_PLACEHOLDER + "', 'tier': '" + ROOT_PLACEHOLDER + "'},"
            + " 'outOfOrder': true, 'baselineOnMigrate': true, 'baselineVersion': '5',"
            + " 'validateOnMigrate': false, 'user': 'root_ddl', 'password': '" + ROOT_PASSWORD + "',"
            + " 'lockRetryCount': 7";

    private static final String ALL_NULL_OVERRIDES = "'mode': null, 'locations': null, 'schemas': null,"
            + " 'table': null, 'target': null, 'outOfOrder': null, 'baselineOnMigrate': null,"
            + " 'baselineVersion': null, 'validateOnMigrate': null, 'placeholders': null,"
            + " 'user': null, 'password': null, 'lockRetryCount': null";

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @TestFactory
    Stream<DynamicNode> resolvesOverridesAndRejectsInvalidDeclarations() {
        return Stream.of(
                dynamicContainer("raw instances key and parsed shape", rawInstancesKeyAndParsedShape()),
                dynamicContainer("unknown and malformed per-instance fields", malformedInstanceFields()),
                dynamicContainer("effective values: inheritance versus replacement", effectiveValues()),
                dynamicContainer("credentials as one atomic pair", credentials()),
                dynamicContainer("db.schemas agreement with Flyway lists", schemaAgreement()),
                dynamicContainer("declaration graph, names and ordering", declarationGraph()),
                dynamicContainer("history identity collisions", historyCollisions()),
                dynamicContainer("root mode ceiling", modeCeiling()),
                dynamicContainer("classpath locations across instances", classpathNesting()),
                dynamicContainer("lock retry count", lockRetryCount()),
                dynamicContainer("secret hygiene", secretHygiene()));
    }

    // =====================================================================================
    // Raw instances key and parsed shape
    // =====================================================================================

    private List<DynamicTest> rawInstancesKeyAndParsedShape() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("no flyway section at all binds the single-history defaults", () -> {
            FlywayConfig config = DbFlywayModule.flywayConfig(new JsonObject(), PARSER);

            assertFalse(config.named(), "named mode is opt-in");
            assertNull(config.instances(), "no overrides are carried");
            assertEquals(FlywayMode.MIGRATE, config.mode());
            assertNull(config.lockRetryCount(), "unset keeps the Flyway default");
        }));

        tests.add(dynamicTest("named absent binds false and keeps the other root fields", () -> {
            FlywayConfig config = new Scenario("{ 'schemas': ['other'] }").bind();

            assertFalse(config.named());
            assertNull(config.instances());
            assertEquals(List.of("other"), config.schemas());
        }));

        tests.add(dynamicTest("named true binds true, and false binds false", () -> {
            assertTrue(new Scenario("{ 'named': true }").bind().named());
            assertFalse(new Scenario("{ 'named': false }").bind().named());
        }));

        tests.add(dynamicTest("named true with no instances binds no overrides", () -> {
            FlywayConfig config = new Scenario("{ 'named': true }").bind();

            assertTrue(config.named());
            assertNull(config.instances(), "absent overrides stay absent");
        }));

        tests.add(dynamicTest("instances bind without named: binding itself never rejects overrides", () -> {
            FlywayConfig config = new Scenario("{ 'instances': { 'job': { 'mode': 'VALIDATE' } } }").bind();

            assertFalse(config.named());
            assertEquals(1, config.instances().size());
            assertEquals("job", config.instances().get(0).name());
            assertEquals(FlywayMode.VALIDATE, config.instances().get(0).mode());
        }));

        tests.add(dynamicTest("instances {} does not select named mode", () -> {
            FlywayConfig config = new Scenario("{ 'instances': {} }").bind();

            assertFalse(config.named(), "an empty overrides object is not a mode switch");
            assertTrue(config.instances() == null || config.instances().isEmpty());
        }));

        tests.add(dynamicTest("instances JSON null does not select named mode", () -> {
            FlywayConfig config = new Scenario("{ 'instances': null }").bind();

            assertFalse(config.named());
            assertNull(config.instances());
        }));

        tests.add(dynamicTest("named true with instances {} binds named with an empty override list", () -> {
            FlywayConfig config = new Scenario("{ 'named': true, 'instances': {} }").bind();

            assertTrue(config.named());
            assertTrue(config.instances() == null || config.instances().isEmpty());
        }));

        tests.add(dynamicTest("a misspelled root key is ignored and selects nothing", () -> {
            FlywayConfig config = new Scenario("{ 'instance': { 'job': {} }, 'name': true }").bind();

            assertFalse(config.named());
            assertNull(config.instances());
        }));

        tests.add(dynamicTest("unknown root keys stay ignored with and without named mode", () -> {
            FlywayConfig single = new Scenario("{ 'unknownRootKey': { 'a': 1 } }").bind();
            FlywayConfig named =
                    new Scenario("{ 'unknownRootKey': 7, 'named': true, 'instances': { 'job': {} } }").bind();

            assertFalse(single.named());
            assertNull(single.instances());
            assertTrue(named.named());
            assertEquals(1, named.instances().size());
        }));

        for (String nonObject : List.of("'" + SECRET_MARK + "-text'", "[]", "7", "true", "[{'name': 'job'}]")) {
            tests.add(dynamicTest("instances value " + nonObject.replace(SECRET_MARK, "x") + " is invalid", () -> {
                Scenario scenario = new Scenario("{ 'instances': " + nonObject + " }");

                ConfigurationException ex = assertThrows(ConfigurationException.class, scenario::bind);

                assertBindingMessageSafe(ex);
            }));
        }

        tests.add(dynamicTest("binding keeps every root field and attaches the parsed instances", () -> {
            String root = ROOT_POLICY + ", 'jdbcUrl': '" + EXPLICIT_URL + "', 'cleanDisabled': false,"
                    + " 'unknownRootKey': 'ignored'";
            FlywayConfig single = new Scenario("{ " + root + " }").bind();
            FlywayConfig named = new Scenario(flyway(root, "{ 'job': { 'mode': 'DISABLED' }, 'app': {} }")).bind();

            for (FlywayConfig config : List.of(single, named)) {
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
                assertEquals(Integer.valueOf(7), config.lockRetryCount());
            }
            assertFalse(single.named());
            assertTrue(named.named());
            assertNull(single.instances());
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
                                    + "   'placeholders': {}, 'user': '', 'password': '', 'lockRetryCount': -1 } }"))
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
                assertNull(absent.lockRetryCount());
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
            assertEquals(Integer.valueOf(-1), explicit.lockRetryCount());
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
                            + " 'placeholders': {'k': 'v'}, 'user': 'u', 'password': 'p', 'lockRetryCount': 3 } }"));

            assertEquals("job_history", only(scenario.views()).table());
        }));

        for (String unknown : List.of("schema", "jdbcUrl", "cleanDisabled", "dependsOn", "location")) {
            tests.add(dynamicTest("unknown per-instance key '" + unknown + "' is rejected, never aliased", () -> {
                Scenario scenario = new Scenario(flyway(
                        "",
                        "{ 'job': { '" + unknown + "': '" + SECRET_MARK + "-value', 'password': '" + INSTANCE_PASSWORD
                                + "' } }"));

                ConfigurationException ex = assertThrows(ConfigurationException.class, scenario::bind);

                String message = String.valueOf(ex.getMessage());
                assertTrue(message.contains("flyway.instances.job"), "names the offending instance: " + message);
                assertTrue(message.contains("'" + unknown + "'"), "names the offending key: " + message);
                assertTrue(message.contains("schemas"), "lists the supported fields: " + message);
                assertBindingMessageSafe(ex);
            }));
        }

        tests.add(dynamicTest("one unknown key among valid ones still rejects the whole object", () -> {
            Scenario scenario =
                    new Scenario(flyway("", "{ 'job': { 'mode': 'VALIDATE', 'table': 't', 'schemaa': 'x' } }"));

            ConfigurationException ex = assertThrows(ConfigurationException.class, scenario::bind);

            assertTrue(String.valueOf(ex.getMessage()).contains("flyway.instances.job"), ex.getMessage());
            assertTrue(String.valueOf(ex.getMessage()).contains("'schemaa'"), ex.getMessage());
        }));

        tests.add(dynamicTest("the supported fields in the message are the override components, never name", () -> {
            Scenario scenario = new Scenario(flyway("", "{ 'job': { 'bogus': 1 } }"));

            String message =
                    assertThrows(ConfigurationException.class, scenario::bind).getMessage();

            List<String> overrides = Stream.of(FlywayInstanceConfig.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .filter(component -> !component.equals("name"))
                    .toList();
            assertEquals(13, overrides.size(), "the documented override fields");
            for (String field : overrides) {
                assertTrue(message.contains(field), field + " missing from: " + message);
            }
            assertFalse(message.contains("[name") || message.contains(" name,"), "the identity is not an override");
        }));

        tests.add(
                dynamicTest("the key is the identity: an entry that repeats it is accepted, a conflict is not", () -> {
                    FlywayConfig same =
                            new Scenario(flyway("", "{ 'job': { 'name': 'job', 'mode': 'VALIDATE' } }")).bind();
                    Scenario conflicting = new Scenario(flyway(
                            "",
                            "{ 'job': { 'name': '" + SECRET_MARK + "-other', 'password': '" + INSTANCE_PASSWORD
                                    + "' } }"));

                    ConfigurationException ex = assertThrows(ConfigurationException.class, conflicting::bind);

                    assertEquals("job", same.instances().get(0).name());
                    assertTrue(String.valueOf(ex.getMessage()).contains("job"), "names the entry: " + ex.getMessage());
                    assertBindingMessageSafe(ex);
                }));

        tests.add(dynamicTest("a blank instance key is rejected", () -> {
            Scenario scenario = new Scenario(flyway("", "{ ' ': {} }"));

            assertBindingMessageSafe(assertThrows(ConfigurationException.class, scenario::bind));
        }));

        for (String badValue : List.of("'" + SECRET_MARK + "-text'", "7", "[]")) {
            tests.add(dynamicTest("instance entry value " + badValue.replace(SECRET_MARK, "x") + " is invalid", () -> {
                Scenario scenario = new Scenario(flyway("", "{ 'job': " + badValue + " }"));

                ConfigurationException ex = assertThrows(ConfigurationException.class, scenario::bind);

                assertBindingMessageSafe(ex);
            }));
        }

        String instancePlaceholders = "'placeholders': {'k': '" + INSTANCE_PLACEHOLDER + "'}";
        Map<String, String> bindingFailures = new LinkedHashMap<>();
        bindingFailures.put(
                "enum value that is itself a secret", "'mode': '" + SECRET_MARK + "-mode', " + instancePlaceholders);
        bindingFailures.put("boolean that cannot be read", "'outOfOrder': 'nope', " + instancePlaceholders);
        bindingFailures.put("placeholders of the wrong shape", "'placeholders': ['x']");
        bindingFailures.put("schemas of the wrong shape", "'schemas': {'a': 1}, " + instancePlaceholders);
        bindingFailures.forEach((label, fields) -> tests.add(dynamicTest(
                "binding failure with a password in the object: " + label + " has a value-free message", () -> {
                    Scenario scenario = new Scenario(
                            flyway("", "{ 'job': { " + fields + ", 'password': '" + INSTANCE_PASSWORD + "' } }"));

                    ConfigurationException ex = assertThrows(ConfigurationException.class, scenario::bind);

                    assertBindingMessageSafe(ex);
                })));

        tests.add(dynamicTest("single-history binding failures keep their original configuration exception", () -> {
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
                "built-in defaults apply when neither root nor instance says anything",
                new Scenario(flyway("", "{ 'job': {} }")),
                views -> {
                    MigrationInstanceView view = only(views);
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
                    assertEquals(Set.of(), view.placeholderKeys());
                }));
        tests.add(effective(
                "omitted overrides inherit every root value, except locations",
                new Scenario(flyway(ROOT_POLICY, "{ 'job': {} }")),
                views -> {
                    MigrationInstanceView view = only(views);
                    assertEquals(FlywayMode.VALIDATE, view.mode());
                    assertEquals(List.of(ALPHA), view.locations(), "root locations are never inherited");
                    assertEquals(List.of("shared", "audit"), view.schemas());
                    assertEquals("42", view.target());
                    assertTrue(view.outOfOrder());
                    assertTrue(view.baselineOnMigrate());
                    assertEquals("5", view.baselineVersion());
                    assertFalse(view.validateOnMigrate());
                    assertEquals(Set.of("region", "tier"), view.placeholderKeys());
                    assertEquals("root_ddl", view.user());
                }));
        tests.add(dynamicTest("JSON null overrides resolve exactly like omitted ones", () -> {
            assertEquals(
                    new Scenario(flyway(ROOT_POLICY, "{ 'job': {} }")).views(),
                    new Scenario(flyway(ROOT_POLICY, "{ 'job': { " + ALL_NULL_OVERRIDES + " } }")).views());
        }));
        tests.add(effective(
                "explicit false, empty list and empty map replace root values instead of inheriting",
                new Scenario(flyway(
                        ROOT_POLICY,
                        "{ 'job': { 'outOfOrder': false, 'baselineOnMigrate': false,"
                                + " 'validateOnMigrate': true, 'schemas': [], 'placeholders': {}, 'target': 'latest',"
                                + " 'baselineVersion': '0', 'table': 'custom_history',"
                                + " 'locations': ['" + BETA + "', '" + GAMMA + "'] } }")),
                views -> {
                    MigrationInstanceView view = only(views);
                    assertFalse(view.outOfOrder());
                    assertFalse(view.baselineOnMigrate());
                    assertTrue(view.validateOnMigrate());
                    assertEquals(List.of(), view.schemas(), "[] clears the root list");
                    assertEquals(Set.of(), view.placeholderKeys(), "{} clears the root map");
                    assertEquals("latest", view.target());
                    assertEquals("0", view.baselineVersion());
                    assertEquals("custom_history", view.table());
                    assertEquals(List.of(BETA, GAMMA), view.locations(), "order is preserved");
                }));
        tests.add(effective(
                "a hyphenated declaration gets an underscore history table by default",
                new Scenario(flyway("", "{}")).definitions(def("inbox-outbox", ALPHA)),
                views -> assertEquals(
                        "flyway_schema_history_inbox_outbox", only(views).table())));
        tests.add(effective(
                "overrides of one instance never leak into its neighbour",
                new Scenario(flyway("'outOfOrder': true", "{ 'app': { 'outOfOrder': false, 'table': 'app_history' } }"))
                        .definitions(job(), def("app", BETA)),
                views -> {
                    assertFalse(view(views, "app").outOfOrder());
                    assertEquals("app_history", view(views, "app").table());
                    assertTrue(view(views, "job").outOfOrder());
                    assertEquals("flyway_schema_history_job", view(views, "job").table());
                }));
        return tests;
    }

    // =====================================================================================
    // Credentials
    // =====================================================================================

    private List<DynamicTest> credentials() {
        List<DynamicTest> tests = new ArrayList<>();
        String instancePair = "'user': 'inst_ddl', 'password': '" + INSTANCE_PASSWORD + "'";
        String rootPair = "'user': 'root_ddl', 'password': '" + ROOT_PASSWORD + "'";

        // label, root fields, instance fields, expected user, expected password
        String[][] precedence = {
            {"no Flyway credentials anywhere: the pool pair", "", "", "pool_user", POOL_PASSWORD},
            {"a complete root pair beats the pool pair", rootPair, "", "root_ddl", ROOT_PASSWORD},
            {"a complete instance pair beats the root pair", rootPair, instancePair, "inst_ddl", INSTANCE_PASSWORD},
            {"a complete instance pair beats the pool pair", "", instancePair, "inst_ddl", INSTANCE_PASSWORD},
            {"empty user and password are explicit values", rootPair, "'user': '', 'password': ''", "", ""},
            {
                "null user and password inherit the root pair",
                rootPair,
                "'user': null, 'password': null",
                "root_ddl",
                ROOT_PASSWORD
            },
        };
        for (String[] row : precedence) {
            tests.add(dynamicTest(row[0], () -> {
                NamedInstanceResolver.Instance resolved =
                        only(new Scenario(flyway(row[1], "{ 'job': { " + row[2] + " } }")).resolved());

                assertEquals(row[3], resolved.view().user());
                assertEquals(row[4], resolved.password());
            }));
        }

        tests.add(rejects("instance user without password", flyway("", "{ 'job': { 'user': 'inst_ddl' } }"), "job"));
        tests.add(rejects(
                "instance password without user",
                flyway("", "{ 'job': { 'password': '" + INSTANCE_PASSWORD + "' } }"),
                "job"));
        tests.add(rejects(
                "instance user with JSON null password",
                flyway("", "{ 'job': { 'user': 'inst_ddl', 'password': null } }"),
                "job"));
        tests.add(rejects(
                "a partial instance pair is never completed from the root pair",
                flyway(rootPair, "{ 'job': { 'user': 'inst_ddl' } }"),
                "job"));
        tests.add(rejects(
                "root user alone, not mixed with the pool password",
                flyway("'user': 'root_ddl'", "{}"),
                "flyway.user"));
        tests.add(rejects(
                "root password alone, not mixed with the pool user",
                flyway("'password': '" + ROOT_PASSWORD + "'", "{}"),
                "flyway.password"));
        tests.add(rejects(
                "a partial root pair even when every instance carries a complete pair",
                flyway("'user': 'root_ddl'", "{ 'job': { " + instancePair + " }, 'app': { " + instancePair + " } }"),
                "together",
                job(),
                def("app", BETA)));
        return tests;
    }

    // =====================================================================================
    // db.schemas agreement
    // =====================================================================================

    private List<DynamicTest> schemaAgreement() {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, String> jobsEntry = Map.of("job", "jobs");
        String inherit = "{ 'job': {} }";

        // label, root fields, instances, placement, expected managed list (the first entry is the schema)
        tests.add(schemas("entry and no Flyway lists: the entry is the whole list", "", inherit, jobsEntry, "jobs"));
        tests.add(schemas(
                "entry plus inherited root list: the entry first, the root entries as extras",
                "'schemas': ['shared', 'audit']",
                inherit,
                jobsEntry,
                "jobs",
                "shared",
                "audit"));
        tests.add(schemas(
                "an entry inside the inherited root list moves to the front without duplication",
                "'schemas': ['shared', 'jobs', 'audit']",
                inherit,
                jobsEntry,
                "jobs",
                "shared",
                "audit"));
        tests.add(schemas(
                "entry plus an explicit list that starts with it: the explicit list is kept",
                "'schemas': ['ignored']",
                "{ 'job': { 'schemas': ['jobs', 'archive'] } }",
                jobsEntry,
                "jobs",
                "archive"));
        tests.add(schemas("no entry and no lists: no implicit default schema", "", inherit, Map.of()));
        tests.add(schemas(
                "no entry, explicit list: its first entry is the default",
                "",
                "{ 'job': { 'schemas': ['x', 'y'] } }",
                Map.of(),
                "x",
                "y"));
        tests.add(schemas(
                "no entry, inherited root list: its first entry is the default",
                "'schemas': ['x', 'y']",
                inherit,
                Map.of(),
                "x",
                "y"));
        tests.add(schemas(
                "no entry, explicit empty list clears the root list",
                "'schemas': ['x', 'y']",
                "{ 'job': { 'schemas': [] } }",
                Map.of()));
        tests.add(schemas(
                "an entry for an undeclared instance is ignored",
                "",
                inherit,
                Map.of("job", "jobs", "ghost", "x"),
                "jobs"));
        tests.add(effective(
                "each instance resolves its own entry; one without an entry stays unqualified",
                new Scenario(flyway("'schemas': ['shared']", "{}"))
                        .definitions(job(), def("app", BETA), def("plain", GAMMA))
                        .placement(Map.of("job", "jobs", "app", "app_schema")),
                views -> {
                    assertEquals(
                            List.of("app_schema", "shared"), view(views, "app").schemas());
                    assertEquals("jobs", view(views, "job").schema());
                    assertEquals("shared", view(views, "plain").schema());
                }));
        tests.add(effective(
                "a hyphenated declaration finds its db.schemas entry by name",
                new Scenario(flyway("'schemas': ['audit']", "{}"))
                        .definitions(def("inbox-outbox", ALPHA))
                        .placement(Map.of("inbox-outbox", "messaging")),
                views -> assertEquals(List.of("messaging", "audit"), only(views).schemas())));

        tests.add(rejects(
                "explicit list naming another schema than the entry",
                flyway("", "{ 'job': { 'schemas': ['other'] } }"),
                "job",
                jobsEntry));
        tests.add(rejects(
                "explicit empty list next to an entry", flyway("", "{ 'job': { 'schemas': [] } }"), "job", jobsEntry));
        tests.add(rejects(
                "entry that is not the first entry of an explicit list",
                flyway("", "{ 'job': { 'schemas': ['archive', 'jobs'] } }"),
                "job",
                jobsEntry));
        tests.add(rejects(
                "duplicate entries inside an explicit list",
                flyway("", "{ 'job': { 'schemas': ['jobs', 'archive', 'archive'] } }"),
                "job",
                jobsEntry));
        tests.add(rejects(
                "duplicate entries in an inherited root list without an entry",
                flyway("'schemas': ['x', 'y', 'x']", "{}"),
                "job"));
        tests.add(rejects(
                "root DISABLED still enforces the agreement",
                flyway("'mode': 'DISABLED'", "{ 'job': { 'schemas': ['other'] } }"),
                "job",
                jobsEntry));
        return tests;
    }

    private static DynamicTest schemas(
            String label, String root, String instances, Map<String, String> placement, String... expected) {
        return effective(label, new Scenario(flyway(root, instances)).placement(placement), views -> {
            assertEquals(List.of(expected), only(views).schemas());
            assertEquals(expected.length == 0 ? null : expected[0], only(views).schema());
        });
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
                    def("kiwi", ALPHA_V2, "mango"));
            List<MigrationDefinition> reversed = new ArrayList<>(declared);
            Collections.reverse(reversed);

            for (List<MigrationDefinition> input : List.of(declared, reversed)) {
                assertEquals(
                        List.of("mango", "kiwi", "zebra", "apple"),
                        names(new Scenario(flyway("", "{}")).definitions(input).views()));
            }
        }));
        tests.add(dynamicTest("a diamond orders the root, then siblings by name, then the top", () -> {
            List<MigrationInstanceView> views = new Scenario(flyway("", "{}"))
                    .definitions(
                            def("top", ALPHA, "right", "left"),
                            def("right", BETA, "base"),
                            def("left", GAMMA, "base"),
                            def("base", ALPHA_V2))
                    .views();

            assertEquals(List.of("base", "left", "right", "top"), names(views));
        }));
        tests.add(dynamicTest("a disabled dependency is still ordered ahead of its dependent", () -> {
            List<MigrationInstanceView> views = new Scenario(flyway("", "{ 'zeta-base': { 'mode': 'DISABLED' } }"))
                    .definitions(def("alpha-feature", ALPHA, "zeta-base"), def("zeta-base", BETA))
                    .views();

            assertEquals(List.of("zeta-base", "alpha-feature"), names(views));
            assertEquals(FlywayMode.DISABLED, view(views, "zeta-base").mode());
        }));
        tests.add(dynamicTest("identifiers of one to thirty-two characters are accepted", () -> {
            String longest = "a" + "b".repeat(31);

            assertEquals(
                    Set.of("a", "a0-9", longest),
                    Set.copyOf(names(new Scenario(flyway("", "{}"))
                            .definitions(def("a", ALPHA), def("a0-9", BETA), def(longest, GAMMA))
                            .views())));
        }));
        tests.add(dynamicTest("root DISABLED with no declarations resolves to an empty list", () -> {
            assertEquals(
                    List.of(),
                    new Scenario(flyway("'mode': 'DISABLED'", "{}"))
                            .definitions()
                            .views());
        }));

        tests.add(rejects(
                "dependency on an undeclared name",
                "{ 'named': true, 'instances': {} }",
                "job",
                def("job", ALPHA, "missing")));
        tests.add(rejects("self dependency", "{ 'named': true, 'instances': {} }", "job", def("job", ALPHA, "job")));
        tests.add(rejects(
                "two-step dependency cycle",
                "{ 'named': true, 'instances': {} }",
                "cycle",
                def("job", ALPHA, "app"),
                def("app", BETA, "job")));
        tests.add(rejects(
                "two declarations sharing one name",
                "{ 'named': true, 'instances': {} }",
                "job",
                def("job", ALPHA),
                def("job", BETA)));
        tests.add(rejects(
                "an override for a name no declaration carries",
                "{ 'named': true, 'instances': { 'ghost': {} } }",
                "ghost"));
        tests.add(rejects(
                "an unwired override next to a valid one",
                "{ 'named': true, 'instances': { 'job': {}, 'ghost': { 'mode': 'VALIDATE' } } }",
                "ghost"));
        tests.add(rejects(
                "named mode with no declarations and a MIGRATE root",
                "{ 'named': true, 'instances': {} }",
                "declaration",
                NO_DECLARATIONS));
        tests.add(rejects(
                "named mode with no declarations and a VALIDATE root",
                "{ 'mode': 'VALIDATE', 'named': true, 'instances': {} }",
                "declaration",
                NO_DECLARATIONS));
        for (String invalid : List.of("Job", "1job", "-job", "job_x", "job.x", "job x", "a" + "b".repeat(32))) {
            tests.add(rejects(
                    "declaration name '" + invalid + "' breaks the identifier pattern",
                    "{ 'named': true, 'instances': {} }",
                    "name must match",
                    def(invalid, ALPHA)));
        }
        tests.add(rejects(
                "declaration named single is reserved",
                "{ 'named': true, 'instances': {} }",
                "reserved",
                def("single", ALPHA)));
        tests.add(rejects(
                "override key Job breaks the identifier pattern",
                "{ 'named': true, 'instances': { 'Job': {} } }",
                "name must match"));
        tests.add(rejects(
                "override key single is reserved", "{ 'named': true, 'instances': { 'single': {} } }", "reserved"));
        tests.add(effective(
                "a declaration named legacy is an ordinary name",
                new Scenario(flyway("", "{}")).definitions(def("legacy", ALPHA)),
                views -> {
                    assertEquals("legacy", only(views).name());
                    assertEquals("flyway_schema_history_legacy", only(views).table());
                }));
        tests.add(effective(
                "an override named legacy is accepted for the declaration of that name",
                new Scenario(flyway("", "{ 'legacy': { 'mode': 'VALIDATE' } }")).definitions(def("legacy", ALPHA)),
                views -> {
                    assertEquals("legacy", only(views).name());
                    assertEquals(FlywayMode.VALIDATE, only(views).mode());
                }));
        return tests;
    }

    // =====================================================================================
    // History identity collisions
    // =====================================================================================

    private List<DynamicTest> historyCollisions() {
        List<DynamicTest> tests = new ArrayList<>();
        MigrationDefinition[] two = {job(), def("app", BETA)};
        String sameTable = flyway("", "{ 'job': { 'table': 'history' }, 'app': { 'table': 'history' } }");

        tests.add(effective(
                "a shared schema is fine while the history tables differ",
                new Scenario(flyway("", "{}")).definitions(two).placement(Map.of("job", "shared", "app", "shared")),
                views -> assertEquals(2, views.size())));
        tests.add(effective(
                "the same table in different schemas is fine",
                new Scenario(sameTable).definitions(two).placement(Map.of("job", "jobs", "app", "other")),
                views -> assertEquals(2, views.size())));
        tests.add(effective(
                "the same table with one known and one unset schema is not a collision",
                new Scenario(sameTable).definitions(two).placement(Map.of("job", "jobs")),
                views -> assertEquals(2, views.size())));

        String collision = "same history table";
        tests.add(rejects(
                "the same table in the same entry schema",
                sameTable,
                collision,
                Map.of("job", "shared", "app", "shared"),
                two));
        tests.add(rejects(
                "the same table in the same default schema taken from explicit lists",
                flyway(
                        "",
                        "{ 'job': { 'table': 'history', 'schemas': ['x'] },"
                                + " 'app': { 'table': 'history', 'schemas': ['x', 'y'] } }"),
                collision,
                Map.of(),
                two));
        tests.add(rejects(
                "an entry schema and an explicit-list default naming the same schema",
                flyway("", "{ 'job': { 'table': 'history' }, 'app': { 'table': 'history', 'schemas': ['shared'] } }"),
                collision,
                Map.of("job", "shared"),
                two));
        tests.add(rejects(
                "two instances without any schema sharing one explicit table", sameTable, collision, Map.of(), two));
        return tests;
    }

    // =====================================================================================
    // Root mode ceiling
    // =====================================================================================

    private List<DynamicTest> modeCeiling() {
        List<DynamicTest> tests = new ArrayList<>();
        List<String> all = List.of("MIGRATE", "VALIDATE", "DISABLED");
        // root mode (null = key omitted, so the MIGRATE default applies) -> the instance modes it permits
        Map<String, List<String>> permitted = new LinkedHashMap<>();
        permitted.put("MIGRATE", all);
        permitted.put("VALIDATE", List.of("VALIDATE", "DISABLED"));
        permitted.put("DISABLED", List.of("DISABLED"));
        permitted.put(null, all);

        permitted.forEach((root, allowed) -> {
            String rootField = root == null ? "" : "'mode': '" + root + "'";
            String rootLabel = root == null ? "omitted root mode" : "root " + root;
            tests.add(effective(
                    rootLabel + ": an omitted instance mode inherits the root mode",
                    new Scenario(flyway(rootField, "{ 'job': {} }")),
                    views -> assertEquals(
                            FlywayMode.valueOf(root == null ? "MIGRATE" : root),
                            only(views).mode())));
            for (String instance : all) {
                String instances = "{ 'job': { 'mode': '" + instance + "' } }";
                tests.add(
                        allowed.contains(instance)
                                ? effective(
                                        rootLabel + ": instance " + instance + " is permitted",
                                        new Scenario(flyway(rootField, instances)),
                                        views -> assertEquals(
                                                FlywayMode.valueOf(instance),
                                                only(views).mode()))
                                : rejects(
                                        rootLabel + ": instance " + instance + " exceeds the ceiling",
                                        flyway(rootField, instances),
                                        "job"));
            }
        });
        return tests;
    }

    // =====================================================================================
    // Classpath locations across instances
    // =====================================================================================

    private List<DynamicTest> classpathNesting() {
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(effective(
                "sibling prefixes that merely share leading characters do not overlap",
                new Scenario(flyway("", "{}")).definitions(def("job", ALPHA), def("app", ALPHA_V2)),
                views -> assertEquals(2, views.size())));
        tests.add(rejects(
                "a parent directory and a child directory of two instances",
                "{ 'named': true, 'instances': {} }",
                "overlap",
                def("app", "classpath:db/migration"),
                def("job", "classpath:db/migration/job")));
        tests.add(rejects(
                "the same location claimed by two instances",
                "{ 'named': true, 'instances': {} }",
                "overlap",
                def("job", ALPHA),
                def("app", ALPHA)));
        tests.add(rejects(
                "leading and trailing slashes do not hide a nesting",
                flyway("", "{ 'app': { 'locations': ['classpath:/db/migration/'] } }"),
                "overlap",
                def("app", BETA),
                def("job", "classpath:db/migration/job")));
        tests.add(rejects("an empty locations override", flyway("", "{ 'job': { 'locations': [] } }"), "job"));
        return tests;
    }

    // =====================================================================================
    // Lock retry count
    // =====================================================================================

    private List<DynamicTest> lockRetryCount() {
        List<DynamicTest> tests = new ArrayList<>();

        // label, root fields, instance fields, expected effective count
        Object[][] accepted = {
            {"unset everywhere leaves Flyway's own default", "", "", null},
            {"-1 means no bound and is accepted", "'lockRetryCount': -1", "", -1},
            {"zero is accepted", "'lockRetryCount': 0", "", 0},
            {"a positive count is accepted", "'lockRetryCount': 120", "", 120},
            {"an instance inherits the root count", "'lockRetryCount': 7", "", 7},
            {"an explicit instance count wins over the root count", "'lockRetryCount': 7", "'lockRetryCount': 3", 3},
            {"an instance can ask for no bound under a bounded root", "'lockRetryCount': 7", "'lockRetryCount': -1", -1
            },
        };
        for (Object[] row : accepted) {
            tests.add(dynamicTest((String) row[0], () -> {
                NamedInstanceResolver.Instance resolved =
                        only(new Scenario(flyway((String) row[1], "{ 'job': { " + row[2] + " } }")).resolved());

                assertEquals(row[3], resolved.lockRetryCount());
            }));
        }
        tests.add(dynamicTest("an instance override leaves its neighbour on the root count", () -> {
            Map<String, Integer> counts = new Scenario(
                            flyway("'lockRetryCount': 7", "{ 'app': { 'lockRetryCount': 3 } }"))
                    .definitions(job(), def("app", BETA)).resolved().stream()
                            .collect(Collectors.toMap(
                                    i -> i.view().name(), NamedInstanceResolver.Instance::lockRetryCount));

            assertEquals(Map.of("app", 3, "job", 7), counts);
        }));

        for (int invalid : new int[] {-2, Integer.MIN_VALUE}) {
            tests.add(dynamicTest("root count " + invalid + " is rejected naming the setting, not the value", () -> {
                MigrationRunException ex = rejection(new Scenario(flyway("'lockRetryCount': " + invalid, "{}")));

                assertTrue(ex.getMessage().contains("flyway.lockRetryCount"), ex.getMessage());
                assertFalse(ex.getMessage().contains(String.valueOf(invalid)), ex.getMessage());
            }));
            tests.add(dynamicTest("instance count " + invalid + " is rejected naming the instance setting", () -> {
                MigrationRunException ex =
                        rejection(new Scenario(flyway("", "{ 'job': { 'lockRetryCount': " + invalid + " } }")));

                assertTrue(ex.getMessage().contains("flyway.instances.job.lockRetryCount"), ex.getMessage());
                assertFalse(ex.getMessage().contains(String.valueOf(invalid)), ex.getMessage());
            }));
        }
        tests.add(rejects(
                "a root count below -1 is rejected even when every instance sets a valid one",
                flyway("'lockRetryCount': -2", "{ 'job': { 'lockRetryCount': 3 } }"),
                "flyway.lockRetryCount"));
        tests.add(rejects(
                "root DISABLED still validates the count",
                flyway("'mode': 'DISABLED', 'lockRetryCount': -2", "{}"),
                "flyway.lockRetryCount"));
        return tests;
    }

    // =====================================================================================
    // Secret hygiene
    // =====================================================================================

    private List<DynamicTest> secretHygiene() {
        List<DynamicTest> tests = new ArrayList<>();
        Map<String, ObjectMapper> mappers = new LinkedHashMap<>();
        mappers.put("plain", new ObjectMapper());
        mappers.put("framework", DefaultConfigMapper.lenient());

        tests.add(dynamicTest("toString of both configuration types and the builder hides every secret", () -> {
            String instance = secretInstance().toString();
            String config = secretConfig().toString();

            assertFalse(instance.contains(SECRET_MARK), instance);
            assertFalse(config.contains(SECRET_MARK), config);
            assertTrue(instance.contains("lockRetryCount=4"), instance);
            assertTrue(config.contains("lockRetryCount=9"), config);
            assertFalse(FlywayConfig.builder()
                    .password(ROOT_PASSWORD)
                    .placeholders(Map.of("region", ROOT_PLACEHOLDER))
                    .instances(List.of(secretInstance()))
                    .toString()
                    .contains(SECRET_MARK));
        }));
        mappers.forEach((kind, mapper) -> {
            tests.add(dynamicTest("Jackson never serializes either type's secrets (" + kind + " mapper)", () -> {
                String instanceJson = mapper.writeValueAsString(secretInstance());
                String configJson = mapper.writeValueAsString(secretConfig());

                assertFalse(instanceJson.contains(SECRET_MARK), instanceJson);
                assertFalse(configJson.contains(SECRET_MARK), configJson);
                assertTrue(instanceJson.contains("\"user\":\"inst_ddl\""), "non-secret fields still serialize");
                Set<String> components = Stream.of(FlywayInstanceConfig.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .collect(Collectors.toSet());
                assertTrue(
                        components.containsAll(new JsonObject(instanceJson).fieldNames()),
                        "only record components serialize, never the unknown-key hook: " + instanceJson);
            }));
            tests.add(dynamicTest("Jackson still accepts secret input for both types (" + kind + " mapper)", () -> {
                FlywayInstanceConfig instance = mapper.readValue(
                        "{\"name\":\"job\",\"user\":\"inst_ddl\",\"password\":\"" + INSTANCE_PASSWORD + "\","
                                + "\"placeholders\":{\"zone\":\"" + INSTANCE_PLACEHOLDER + "\"}}",
                        FlywayInstanceConfig.class);
                FlywayConfig config = mapper.readValue("{\"password\":\"" + ROOT_PASSWORD + "\"}", FlywayConfig.class);

                assertEquals(INSTANCE_PASSWORD, instance.password());
                assertEquals(Map.of("zone", INSTANCE_PLACEHOLDER), instance.placeholders());
                assertEquals(ROOT_PASSWORD, config.password());
            }));
        });
        tests.add(dynamicTest("views and resolved instances never print a password or a placeholder value", () -> {
            Scenario scenario = new Scenario(flyway(
                            "'user': 'root_ddl', 'password': '" + ROOT_PASSWORD + "', 'placeholders': {'region': '"
                                    + ROOT_PLACEHOLDER + "'}",
                            "{ 'job': { 'user': 'inst_ddl', 'password': '" + INSTANCE_PASSWORD
                                    + "', 'placeholders': {'zone': '" + INSTANCE_PLACEHOLDER + "'} } }"))
                    .definitions(job(), def("app", BETA));

            List<NamedInstanceResolver.Instance> resolved = scenario.resolved();

            assertEquals(INSTANCE_PASSWORD, resolved.get(1).password(), "the run still receives the secret");
            assertEquals(Set.of("zone"), view(scenario.views(), "job").placeholderKeys());
            assertEquals(Set.of("region"), view(scenario.views(), "app").placeholderKeys());
            for (NamedInstanceResolver.Instance instance : resolved) {
                assertFalse(instance.toString().contains(SECRET_MARK), instance.toString());
                assertFalse(String.valueOf(instance.view()).contains(SECRET_MARK), String.valueOf(instance.view()));
            }
        }));
        return tests;
    }

    private static FlywayConfig secretConfig() {
        return FlywayConfig.builder()
                .user("root_ddl")
                .password(ROOT_PASSWORD)
                .placeholders(Map.of("region", ROOT_PLACEHOLDER))
                .lockRetryCount(9)
                .named(true)
                .instances(List.of(secretInstance()))
                .build();
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
                INSTANCE_PASSWORD,
                4);
    }

    // =====================================================================================
    // Execution that needs no database
    // =====================================================================================

    @Test
    void resolutionOpensNoConnectionInNamedAndSingleMode() throws Exception {
        // Given a JDBC destination that counts every connection, a named runner and a single-history runner
        try (Listener destination = new Listener()) {
            String root = "'jdbcUrl': '" + destination.jdbcUrl() + "'";
            FlywayMigrationRunner named = new Scenario(flyway(root, "{}"))
                    .definitions(job(), def("app", BETA, "job"))
                    .runner();
            FlywayMigrationRunner single = new Scenario("{ " + root + ", 'schemas': ['other'] }")
                    .placement(Map.of("job", "jobs"))
                    .runner();

            // When the instances are resolved, twice
            List<MigrationInstanceView> first = named.instances();

            // Then the views are repeatable, single mode resolves none, and nothing connected
            assertEquals(List.of("job", "app"), names(first));
            assertEquals(first, named.instances());
            assertEquals(List.of(), single.instances());
            assertEquals(0, destination.strayConnections());
        }
    }

    private static final String OVERRIDES_WITHOUT_NAMED_MESSAGE =
            "Named migration configuration failed: flyway.instances overrides require flyway.named";

    /** Override sections that name no mode: each carries overrides and a secret, and none sets named. */
    private static List<String> overridesWithoutNamed(String destination) {
        String secrets = "'user': 'inst_ddl', 'password': '" + INSTANCE_PASSWORD + "'";
        String jdbc = "'jdbcUrl': '" + destination + "'";
        return List.of(
                "{ " + jdbc + ", 'instances': { 'job': { " + secrets + " } } }",
                "{ " + jdbc + ", 'named': false, 'instances': { 'job': {} } }",
                "{ " + jdbc + ", 'instances': { 'ghost': { 'mode': 'VALIDATE' } } }",
                "{ " + jdbc + ", 'mode': 'DISABLED', 'instances': { 'job': {} } }");
    }

    private static void assertOverridesWithoutNamedFailure(Throwable failure) {
        MigrationRunException ex = assertInstanceOf(MigrationRunException.class, failure);
        assertEquals(Stage.CONFIGURATION, ex.stage());
        assertEquals(OVERRIDES_WITHOUT_NAMED_MESSAGE, ex.getMessage());
        assertNull(ex.report(), "no report exists before a run");
        assertNull(ex.getCause(), "the framework rejected the input itself");
        assertSafe(ex);
    }

    @Test
    void instancesRejectsOverridesWithoutNamedAndOpensNoConnection() throws Exception {
        // Given a JDBC destination that counts every connection and overrides configured without named
        try (Listener destination = new Listener()) {
            for (String section : overridesWithoutNamed(destination.jdbcUrl())) {
                FlywayMigrationRunner runner = new Scenario(section).runner();

                // When the instances are resolved
                MigrationRunException ex = assertThrows(MigrationRunException.class, runner::instances);

                // Then it is the configuration failure that asks for named, with no report and no value
                assertOverridesWithoutNamedFailure(ex);
            }
            assertEquals(0, destination.strayConnections());
        }
    }

    @Test
    void runFailsTheFutureForOverridesWithoutNamedAndOpensNoConnection() throws Exception {
        // Given a JDBC destination that counts every connection and overrides configured without named
        try (Listener destination = new Listener()) {
            for (String section : overridesWithoutNamed(destination.jdbcUrl())) {
                FlywayMigrationRunner runner = new Scenario(section).runner();

                // When it runs
                Throwable failure = failureOf(runner.run(vertx));

                // Then the future fails with the same configuration failure
                assertOverridesWithoutNamedFailure(failure);
            }
            assertEquals(0, destination.strayConnections());
        }
    }

    @Test
    void migrateFailsTheFutureForOverridesWithoutNamedAndOpensNoConnection() throws Exception {
        // Given a JDBC destination that counts every connection and overrides configured without named
        try (Listener destination = new Listener()) {
            for (String section : overridesWithoutNamed(destination.jdbcUrl())) {
                FlywayMigrationRunner runner = new Scenario(section).runner();

                // When it migrates
                Throwable failure = failureOf(runner.migrate(vertx));

                // Then the future fails with the same configuration failure
                assertOverridesWithoutNamedFailure(failure);
            }
            assertEquals(0, destination.strayConnections());
        }
    }

    @Test
    void emptyOrAbsentOverridesWithoutNamedAreNotRejected() throws Exception {
        // Given a JDBC destination that counts every connection and sections that configure no override
        try (Listener destination = new Listener()) {
            String jdbc = "'jdbcUrl': '" + destination.jdbcUrl() + "'";
            for (String section : List.of(
                    "{ " + jdbc + " }",
                    "{ " + jdbc + ", 'instances': {} }",
                    "{ " + jdbc + ", 'instances': null }",
                    "{ " + jdbc + ", 'named': false, 'instances': {} }")) {

                // When the instances are resolved
                List<MigrationInstanceView> views =
                        new Scenario(section).runner().instances();

                // Then single-history mode resolves no instance and nothing is rejected or connected
                assertEquals(List.of(), views, section);
            }
            assertEquals(0, destination.strayConnections());
        }
    }

    @Test
    void namedWithoutOverridesResolvesEveryDeclarationWithRootSettings() {
        // Given named mode, no instances key at all, and two declarations
        Scenario scenario =
                new Scenario("{ 'named': true, " + ROOT_POLICY + " }").definitions(job(), def("app", BETA, "job"));

        // When the instances are resolved
        List<MigrationInstanceView> views = scenario.views();

        // Then every declaration is an instance in dependency order, inheriting the root settings
        assertEquals(List.of("job", "app"), names(views));
        for (MigrationInstanceView view : views) {
            assertEquals(FlywayMode.VALIDATE, view.mode());
            assertEquals("42", view.target());
            assertEquals(List.of("shared", "audit"), view.schemas());
            assertEquals("root_ddl", view.user());
        }
        assertEquals(List.of(ALPHA), view(views, "job").locations(), "root locations are never inherited");
        assertEquals(List.of(BETA), view(views, "app").locations());
    }

    @Test
    void rootDisabledRunReportsEveryInstanceDisabledWithoutAnyDestination() throws Exception {
        // Given a root DISABLED named configuration and a pool that names no database at all
        Scenario scenario = new Scenario(flyway("'mode': 'DISABLED'", "{}"))
                .definitions(job(), def("app", BETA, "job"))
                .pool(DbPoolConfig.builder().build());

        // When it runs and migrates
        MigrationReport report = succeeded(scenario.runner().run(vertx));
        MigrationResult migrated = succeeded(scenario.runner().migrate(vertx));

        // Then every instance is DISABLED, nothing is applied and no destination was needed
        assertEquals(
                List.of("job", "app"),
                report.instances().stream().map(r -> r.instance().name()).toList());
        for (MigrationInstanceResult result : report.instances()) {
            assertEquals(MigrationStatus.DISABLED, result.status());
            assertEquals(0, result.migrationsApplied());
            assertNull(result.currentVersion());
        }
        assertEquals(new MigrationResult(0, null), migrated);
    }

    @Test
    void namedRunWithoutAJdbcDestinationFailsAsConfigurationWithEveryInstanceNotRun() {
        // Given a valid named configuration whose pool names no host and no database, and no jdbcUrl
        Scenario scenario = new Scenario(flyway("", "{}"))
                .definitions(job(), def("app", BETA, "job"))
                .pool(DbPoolConfig.builder().build());

        // When it runs and migrates
        for (Future<?> future :
                List.of(scenario.runner().run(vertx), scenario.runner().migrate(vertx))) {
            MigrationRunException failed = assertInstanceOf(MigrationRunException.class, failureOf(future));

            // Then the failure is a configuration failure with a report that lists every instance as not run
            assertEquals(Stage.CONFIGURATION, failed.stage());
            assertTrue(failed.getMessage().contains("flyway.jdbcUrl"), failed.getMessage());
            assertNull(failed.getCause());
            assertEquals(
                    List.of("job", "app"),
                    failed.report().instances().stream()
                            .map(r -> r.instance().name())
                            .toList());
            assertTrue(failed.report().instances().stream().allMatch(r -> r.status() == MigrationStatus.NOT_RUN));
            assertSafe(failed);
        }
    }

    @Test
    void invalidNamedConfigurationFailsTheRunBeforeAnyReportExists() {
        // Given a configuration that is invalid at resolution; any connection attempt to the pool would fail otherwise
        Scenario scenario = new Scenario(flyway("", "{ 'ghost': {} }"));

        // When it runs and migrates
        for (Future<?> future :
                List.of(scenario.runner().run(vertx), scenario.runner().migrate(vertx))) {
            MigrationRunException failed = assertInstanceOf(MigrationRunException.class, failureOf(future));

            // Then the failure is a configuration failure without a report
            assertEquals(Stage.CONFIGURATION, failed.stage());
            assertNull(failed.report());
            assertTrue(failed.getMessage().contains("ghost"), failed.getMessage());
        }
    }

    @Test
    void rejectsNullRootModeInNamedMode() {
        // Given a named configuration whose root mode is an explicit JSON null: the canonical parser binds it
        // to null rather than to the MIGRATE default, which would be the most permissive ceiling
        Scenario scenario = new Scenario(flyway("'mode': null", "{ 'job': {} }"));
        assertNull(scenario.bind().mode(), "the canonical parser keeps an explicit null root mode");

        // When the instances are resolved, and when the same configuration runs
        MigrationRunException resolved = rejection(scenario);
        Throwable ran = failureOf(scenario.runner().run(vertx));

        // Then the null mode is rejected naming flyway.mode instead of acting as the MIGRATE ceiling
        assertTrue(resolved.getMessage().contains("flyway.mode"), resolved.getMessage());
        assertEquals(
                Stage.CONFIGURATION,
                assertInstanceOf(MigrationRunException.class, ran).stage());
    }

    @Test
    void singleRunnerRejectsALockRetryCountBelowMinusOne() {
        // Given a single-Flyway configuration with an invalid lock retry count
        Scenario scenario = new Scenario("{ 'locations': ['" + ALPHA + "'], 'lockRetryCount': -2 }");

        // When it migrates
        ConfigurationException rejected = assertInstanceOf(
                ConfigurationException.class, failureOf(scenario.runner().migrate(vertx)));

        // Then the setting is named and the value is not echoed
        assertTrue(rejected.getMessage().contains("flyway.lockRetryCount"), rejected.getMessage());
        assertFalse(rejected.getMessage().contains("-2"), rejected.getMessage());
    }

    @Test
    void unreadableRootSectionFailsWithAValueFreeMessageInBothModes() {
        // Given unreadable root sections, each carrying a secret sentinel in a value that the native
        // binding error would echo, in single and in named mode alike
        Map<String, String> unreadableRoots = new LinkedHashMap<>();
        unreadableRoots.put("password given as an array", "'password': ['" + ROOT_PASSWORD + "']");
        unreadableRoots.put(
                "placeholder value given as an object", "'placeholders': { 'k': { 'x': '" + ROOT_PLACEHOLDER + "' } }");
        unreadableRoots.put("lock retry count that is not a number", "'lockRetryCount': '" + SECRET_MARK + "-text'");
        unreadableRoots.put("mode that is not a mode", "'mode': '" + SECRET_MARK + "-mode'");
        unreadableRoots.put("wrong-typed flag", "'outOfOrder': ['" + SECRET_MARK + "-flag']");

        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<String, String> root : unreadableRoots.entrySet()) {
            for (String section : List.of(
                    "{ " + root.getValue() + " }", "{ 'named': true, 'instances': {}, " + root.getValue() + " }")) {
                checks.add(() -> {
                    // When the section is bound
                    Throwable failure = bindFailure(section);

                    // Then the failure is a configuration failure whose message echoes no value
                    ConfigurationException ex = assertInstanceOf(
                            ConfigurationException.class, failure, root.getKey() + ": observed " + failure);
                    assertBindingMessageSafe(ex);
                });
            }
        }

        // Then every case holds
        assertAll(checks);
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

    /**
     * Builds a named-mode flyway section from root fields (may be empty) and a raw overrides value; quotes
     * may be single.
     */
    private static String flyway(String rootFields, String instances) {
        String root = rootFields.isBlank() ? "" : rootFields + ", ";
        return "{ " + root + "'named': true, 'instances': " + instances + " }";
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
            assertThrows(UnsupportedOperationException.class, () -> views.add(null), "the view list is immutable");
            for (MigrationInstanceView view : views) {
                assertThrows(UnsupportedOperationException.class, () -> view.schemas()
                        .add("x"));
                assertThrows(UnsupportedOperationException.class, () -> view.locations()
                        .add("x"));
                assertThrows(UnsupportedOperationException.class, () -> view.placeholderKeys()
                        .add("x"));
                assertFalse(String.valueOf(view).contains(SECRET_MARK), "views never carry a secret: " + view);
            }
            assertEquals(Map.copyOf(scenario.placement), scenario.schemas().mappings(), "db.schemas is never altered");
        });
    }

    /** A binding failure must carry a non-empty message that contains no secret sentinel. */
    private static void assertBindingMessageSafe(Throwable failure) {
        String message = failure.getMessage();
        assertTrue(message != null && !message.isBlank(), "a binding failure explains itself");
        assertFalse(message.contains(SECRET_MARK), "a configuration value leaked into the message: " + message);
        assertFalse(message.contains("nope"), "a configuration value leaked into the message: " + message);
    }

    /** A framework-authored failure: no secret sentinel anywhere in its message, trace or report. */
    private static void assertSafe(MigrationRunException failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(SECRET_MARK), "a secret leaked into the failure chain");
        assertFalse(String.valueOf(failure.report()).contains(SECRET_MARK), "a secret leaked into the report");
    }

    /** Resolution must reject the scenario as a configuration failure that has no report. */
    private static MigrationRunException rejection(Scenario scenario) {
        MigrationRunException ex = assertThrows(
                MigrationRunException.class, () -> scenario.runner().instances());
        assertEquals(Stage.CONFIGURATION, ex.stage());
        assertNull(ex.report(), "no report exists before a run");
        assertNull(ex.getCause(), "the framework rejected the input itself");
        assertSafe(ex);
        return ex;
    }

    private static final List<MigrationDefinition> NO_DECLARATIONS = List.of();

    /**
     * A rejection case: resolution fails as a configuration failure that mentions {@code mention}. The extras
     * are the declarations ({@link MigrationDefinition}, or an array or list of them) and the db.schemas placement
     * ({@link Map}) the scenario uses instead of its defaults.
     */
    @SuppressWarnings("unchecked")
    private static DynamicTest rejects(String label, String flywayJson, String mention, Object... extras) {
        Scenario scenario = new Scenario(flywayJson);
        List<MigrationDefinition> definitions = new ArrayList<>();
        boolean declared = false;
        for (Object extra : extras) {
            if (extra instanceof Map<?, ?> placement) {
                scenario.placement((Map<String, String>) placement);
            } else if (extra instanceof MigrationDefinition[] many) {
                definitions.addAll(List.of(many));
                declared = true;
            } else if (extra instanceof List<?> many) {
                many.forEach(definition -> definitions.add((MigrationDefinition) definition));
                declared = true;
            } else {
                definitions.add((MigrationDefinition) extra);
                declared = true;
            }
        }
        if (declared) {
            scenario.definitions(definitions);
        }
        return dynamicTest(label, () -> {
            MigrationRunException ex = rejection(scenario);
            assertTrue(
                    ex.getMessage().contains(mention), "message does not mention " + mention + ": " + ex.getMessage());
        });
    }

    /** One configuration and its declarations; binds through the canonical parser. */
    private static final class Scenario {
        private final String flywayJson;
        private List<MigrationDefinition> definitions = List.of(job());
        private Map<String, String> placement = Map.of();
        private DbPoolConfig pool = POOL;
        private DbSchemas schemas;

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

        Scenario pool(DbPoolConfig pool) {
            this.pool = pool;
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
            return new FlywayMigrationRunner(bind(), pool, new LinkedHashSet<>(definitions), schemas());
        }

        List<MigrationInstanceView> views() {
            return runner().instances();
        }

        List<NamedInstanceResolver.Instance> resolved() {
            return new NamedInstanceResolver(bind(), pool, definitions, schemas()).resolve();
        }
    }

    /** A loopback destination that accepts and counts every connection made to it. */
    private static final class Listener implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<Integer> clientPorts = new CopyOnWriteArrayList<>();

        Listener() throws IOException {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        try (Socket client = server.accept()) {
                            clientPorts.add(client.getPort());
                        }
                    }
                } catch (IOException closed) {
                    // the test ended
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String jdbcUrl() {
            return "jdbc:postgresql://127.0.0.1:" + server.getLocalPort() + "/app";
        }

        /**
         * Connections made by anything but this probe. The probe connects last and connections are accepted
         * in order, so once the probe is seen every earlier connection has been counted.
         */
        int strayConnections() throws Exception {
            int probePort;
            try (Socket probe = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                probePort = probe.getLocalPort();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!clientPorts.contains(probePort)) {
                assertTrue(System.nanoTime() < deadline, "the probe connection was never accepted");
                Thread.sleep(5);
            }
            return clientPorts.size() - 1;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
