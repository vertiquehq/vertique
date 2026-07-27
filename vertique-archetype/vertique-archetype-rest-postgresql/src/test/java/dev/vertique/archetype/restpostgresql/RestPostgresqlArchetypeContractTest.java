// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype.restpostgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-template contract proof for the PostgreSQL-backed REST archetype.
 *
 * <p>Verifies that the archetype coordinate, the generated project's dependency contract, the
 * generated Dagger component's four-module set, the application-owned migration contract, the
 * generated integration test's Docker-backed container lifecycle, the framework-delegated request
 * validation and id conversion, the generated deployment identifiers/phases, the documented
 * generation/exec/Jib commands, the local-only PostgreSQL placeholders, and the completed archetype
 * family's aggregator catalog match the frozen PostgreSQL REST contracts. The templates are Velocity
 * sources rather than compilable Java, so every assertion is made against the template text.
 *
 * <p>Each parse is <em>exhaustive</em> rather than filtering: the full parsed dependency list, the
 * component module set, the migration file set, the deployment set, and the aggregator's module list
 * are compared against frozen expectations, and any token the parse cannot classify fails the proof
 * instead of being dropped. A declaration that a filtering parse would quietly skip — a
 * commented-out dependency, an extra scope, a second migration, a deployment built by other means, a
 * duplicated {@code -D} flag, a resurrected legacy coordinate — is therefore caught rather than
 * absorbed.
 *
 * <p>This class is deliberately kept textually parallel to the REST and services archetypes' contract
 * tests rather than sharing a common seam: the three proofs freeze different contracts, and
 * extracting the shared machinery is a separate, later concern.
 */
class RestPostgresqlArchetypeContractTest {

    // --- Template locations (relative to the archetype module basedir) ---

    private static final Path ARCHETYPE_POM = Path.of("pom.xml");
    private static final Path ARCHETYPE_README = Path.of("README.md");
    private static final Path ARCHETYPE_RESOURCES = Path.of("src", "main", "resources", "archetype-resources");
    private static final Path TEMPLATE_POM = ARCHETYPE_RESOURCES.resolve("pom.xml");
    private static final Path TEMPLATE_README = ARCHETYPE_RESOURCES.resolve("README.md");
    private static final Path TEMPLATE_COMPONENT =
            ARCHETYPE_RESOURCES.resolve(Path.of("src", "main", "java", "AppComponent.java"));
    private static final Path TEMPLATE_APP_MODULE =
            ARCHETYPE_RESOURCES.resolve(Path.of("src", "main", "java", "AppModule.java"));
    private static final Path TEMPLATE_APPLICATION_CONFIG =
            ARCHETYPE_RESOURCES.resolve(Path.of("src", "main", "resources", "config", "application.json"));
    private static final Path TEMPLATE_MIGRATIONS =
            ARCHETYPE_RESOURCES.resolve(Path.of("src", "main", "resources", "db", "migration"));
    private static final Path TEMPLATE_APPLICATION_IT =
            ARCHETYPE_RESOURCES.resolve(Path.of("src", "test", "java", "ApplicationIT.java"));
    private static final Path TEMPLATE_CREATE_REQUEST =
            ARCHETYPE_RESOURCES.resolve(Path.of("src", "main", "java", "model", "CreateItemRequest.java"));
    private static final Path TEMPLATE_UPDATE_REQUEST =
            ARCHETYPE_RESOURCES.resolve(Path.of("src", "main", "java", "model", "UpdateItemRequest.java"));
    private static final Path TEMPLATE_ITEM_RESOURCE =
            ARCHETYPE_RESOURCES.resolve(Path.of("src", "main", "java", "resource", "ItemResource.java"));

    /** The archetype family aggregator's POM, one directory above this module's basedir. */
    private static final Path AGGREGATOR_POM = Path.of("..", "pom.xml");

    /** The other completed archetype children's POMs, addressed relative to this module's basedir. */
    private static final List<Path> SIBLING_ARCHETYPE_POMS = List.of(
            Path.of("..", "vertique-archetype-rest", "pom.xml"),
            Path.of("..", "vertique-archetype-services", "pom.xml"));

    // --- Template parsing ---

    /**
     * Matches an XML comment. Stripped before any structural parse so a commented-out declaration
     * cannot be mistaken for a live one — the templates are Velocity sources (the POM carries a
     * leading {@code #set} directive), so they are parsed as text rather than as XML documents.
     */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /**
     * Matches a Java block or line comment. Stripped before any structural parse of a Java template
     * for the same reason {@link #XML_COMMENT} is: a commented-out declaration is not a live one.
     *
     * <p>This is a deliberately simple lexer — it does not track string or character literals, so a
     * literal containing Java comment delimiters would be mis-stripped. The PostgreSQL REST templates
     * carry only simple literals (SQL statements, {@code "management"}, {@code "http"}, {@code
     * "/items/"}, {@code "/items/not-a-uuid"}, the {@code @Pattern} regexp), none of which contain a
     * delimiter, so the simple form is exact here. A template that gains such a literal — a URL
     * carrying {@code //}, for instance — must move to a literal-aware scan.
     */
    private static final Pattern JAVA_COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n\\r]*", Pattern.DOTALL);

    /** Matches the {@code <dependencies>} block that is a direct child of {@code <project>}. */
    private static final Pattern PROJECT_DEPENDENCIES =
            Pattern.compile("\\R {4}<dependencies>\\R(.*?)\\R {4}</dependencies>", Pattern.DOTALL);

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern TYPE = Pattern.compile("<type>([^<]+)</type>");
    private static final Pattern SCOPE = Pattern.compile("<scope>([^<]+)</scope>");

    /** Matches the {@code modules = { … }} member of the generated component's {@code @Component}. */
    private static final Pattern COMPONENT_MODULES = Pattern.compile("modules\\s*=\\s*\\{(.*?)}", Pattern.DOTALL);

    /**
     * Matches a {@code return VerticleDeployment.of("id", …, LifecyclePhase.PHASE);} statement. This
     * is matched against a provider's <em>entire</em> trimmed body, so the construction is not merely
     * bound to a {@code return} — it must be the provider's whole implementation. A body with any
     * additional statement, branch, or nested return fails outright.
     */
    private static final Pattern RETURNED_DEPLOYMENT = Pattern.compile(
            "return\\s+VerticleDeployment\\.of\\(\\s*\"([^\"]+)\"\\s*,[^,]+,\\s*LifecyclePhase\\.([A-Z_]+)\\s*\\)\\s*;");

    /**
     * Matches a method declaration whose return type is {@code VerticleDeployment}, capturing the
     * method name. Discovery is keyed on the <em>return type</em>, not on an annotation prefix, so a
     * contributed deployment cannot hide from the proof by reordering or omitting annotations. The
     * leading {@code \b} keeps a longer type such as {@code CustomVerticleDeployment} from
     * suffix-matching.
     */
    private static final Pattern DEPLOYMENT_RETURNING_METHOD =
            Pattern.compile("\\bVerticleDeployment\\s+(\\w+)\\s*\\(");

    /** Matches one annotation name within a member's modifier prefix. */
    private static final Pattern ANNOTATION = Pattern.compile("@\\w+");

    /** Matches the {@code static} modifier as a whole word. */
    private static final Pattern MODIFIER_STATIC = Pattern.compile("\\bstatic\\b");

    /**
     * Matches the generated integration test's injected Flyway configuration. The whole
     * {@code .put("flyway", new JsonObject().put("mode", "MIGRATE"))} construction is pinned — not
     * merely the literal {@code MIGRATE} somewhere in the file — so an injected config that drops or
     * downgrades the migration mode fails the proof.
     */
    private static final Pattern INJECTED_FLYWAY_MODE =
            Pattern.compile("\\.put\\(\\s*\"flyway\"\\s*,\\s*new JsonObject\\(\\)\\.put\\(\\s*\"mode\"\\s*,"
                    + "\\s*\"([A-Z_]+)\"\\s*\\)\\s*\\)");

    /**
     * Matches the generated integration test's {@code PostgresContainer} field declaration, capturing
     * nothing: only its position in the source is load-bearing.
     */
    private static final Pattern CONTAINER_DECLARATION =
            Pattern.compile("\\bstatic\\s+final\\s+PostgresContainer\\s+\\w+\\s*=\\s*new PostgresContainer\\(");

    /** Matches the generated integration test's static-initializer container start. */
    private static final Pattern CONTAINER_START = Pattern.compile("\\bdb\\.start\\(\\)\\s*;");

    /** Matches the generated integration test's {@code VertiqueAppExtension} field construction. */
    private static final Pattern EXTENSION_DECLARATION = Pattern.compile(
            "\\bstatic\\s+final\\s+VertiqueAppExtension\\s+\\w+\\s*=\\s*VertiqueAppExtension\\.forFactory\\(");

    /** Matches a class-level {@code @Timeout(value = N, unit = TimeUnit.U)} declaration. */
    private static final Pattern CLASS_TIMEOUT =
            Pattern.compile("@Timeout\\(\\s*value\\s*=\\s*(\\d+)\\s*,\\s*unit\\s*=\\s*TimeUnit\\.(\\w+)\\s*\\)");

    /** Matches a fenced {@code ```bash ... ```} code block within a markdown document. */
    private static final Pattern FENCED_BASH_BLOCK = Pattern.compile("```bash\\R(.*?)```", Pattern.DOTALL);

    /** Matches a shell backslash line continuation together with the whitespace on either side of it. */
    private static final Pattern LINE_CONTINUATION = Pattern.compile("[ \\t]*\\\\\\R[ \\t]*");

    /** Matches one {@code -DpropertyName=value} generation-command flag; the value may be empty. */
    private static final Pattern GENERATE_PROPERTY = Pattern.compile("-D(\\w+)=(\\S*)");

    /** Matches a {@code <plugin>...</plugin>} declaration. */
    private static final Pattern PLUGIN_BLOCK = Pattern.compile("<plugin>(.*?)</plugin>", Pattern.DOTALL);

    /** Matches the {@code <container>...</container>} block within a jib-maven-plugin configuration. */
    private static final Pattern CONTAINER_BLOCK = Pattern.compile("<container>(.*?)</container>", Pattern.DOTALL);

    /** Matches a {@code <mainClass>value</mainClass>} element. */
    private static final Pattern MAIN_CLASS = Pattern.compile("<mainClass>([^<]+)</mainClass>");

    /** Matches a {@code <user>value</user>} element. */
    private static final Pattern CONTAINER_USER = Pattern.compile("<user>([^<]+)</user>");

    /** Matches a level-2 markdown heading line, capturing nothing beyond the {@code ##} marker itself. */
    private static final Pattern HEADING_2 = Pattern.compile("(?m)^## ");

    /**
     * Matches the {@code "port"} entry of a JSON config section. Kept as its own constant rather than
     * built by {@link #scalarPattern(String)}: its value is an unquoted JSON number, so the quoted
     * string-scalar shape that helper produces would not match it at all.
     */
    private static final Pattern CONFIG_PORT = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");

    /**
     * Matches every {@code @PathParam("id")} declaration together with the declared type that
     * follows it, capturing that type. Discovery is keyed on the annotation rather than on a method
     * signature, so an id parameter that regressed to another type is found and then fails the type
     * assertion instead of disappearing from the proof.
     */
    private static final Pattern ID_PATH_PARAM = Pattern.compile("@PathParam\\(\\s*\"id\"\\s*\\)\\s+(\\w+)\\s+id");

    /** Matches the {@code <modules>...</modules>} block of an aggregator POM. */
    private static final Pattern MODULES_BLOCK = Pattern.compile("<modules>(.*?)</modules>", Pattern.DOTALL);

    /** Matches one {@code <module>name</module>} entry within a {@code <modules>} block. */
    private static final Pattern MODULE_ENTRY = Pattern.compile("<module>([^<]+)</module>");

    // --- Frozen contracts ---

    /**
     * The complete dependency contract of the generated project, in declaration order. Group,
     * artifact, type, and scope are each load-bearing, and the list is exhaustive: any added,
     * removed, re-grouped, re-typed, or re-scoped declaration is a consumer-visible change to what
     * the archetype generates. Both starters are production-scope; every test library is explicit.
     */
    private static final List<Dependency> EXPECTED_TEMPLATE_DEPENDENCIES = List.of(
            new Dependency("dev.vertique", "vertique-starter-rest", null, null),
            new Dependency("dev.vertique", "vertique-starter-postgresql", null, null),
            new Dependency("dev.vertique", "vertique-launcher", null, null),
            new Dependency("dev.vertique", "vertique-application-test", null, "test"),
            new Dependency("dev.vertique", "vertique-db-test", null, "test"),
            new Dependency("org.junit.jupiter", "junit-jupiter", null, "test"),
            new Dependency("io.rest-assured", "rest-assured", null, "test"));

    /**
     * The complete dependency contract of the archetype module itself, in declaration order: one
     * Surefire harness edge plus the frozen staging set that populates the isolated integration-test
     * repository. The list is exhaustive so a staging edge cannot be added or dropped silently — a
     * missing edge would let the nested build fall back to a coordinate this reactor did not produce.
     */
    private static final List<Dependency> EXPECTED_ARCHETYPE_DEPENDENCIES = List.of(
            new Dependency("org.junit.jupiter", "junit-jupiter", null, "test"),
            new Dependency("dev.vertique", "vertique-app-parent", "pom", "test"),
            new Dependency("dev.vertique", "vertique-bom", "pom", "test"),
            new Dependency("dev.vertique", "vertique-codegen-all", null, "test"),
            new Dependency("dev.vertique", "vertique-starter-rest", null, "test"),
            new Dependency("dev.vertique", "vertique-starter-postgresql", null, "test"),
            new Dependency("dev.vertique", "vertique-launcher", null, "test"),
            new Dependency("dev.vertique", "vertique-application-test", null, "test"),
            new Dependency("dev.vertique", "vertique-db-test", null, "test"));

    /**
     * The exact module set the generated Dagger component names, sorted. Four modules: the REST
     * application starter, the independent PostgreSQL persistence starter, the application-owned
     * module, and the generated JAX-RS resources module.
     */
    private static final List<String> EXPECTED_COMPONENT_MODULES = List.of(
            "AppModule.class",
            "GeneratedJaxRsResourcesModule.class",
            "PostgresqlPersistenceModule.class",
            "RestApplicationModule.class");

    /** The exact {@code id@phase} deployments the generated application module contributes, sorted. */
    private static final List<String> EXPECTED_DEPLOYMENTS = List.of("http@EDGE", "management@INFRA");

    /** The annotations every deployment provider must carry; membership is order-insensitive. */
    private static final Set<String> REQUIRED_PROVIDER_ANNOTATIONS = Set.of("@Provides", "@IntoSet");

    /** The single application-owned migration script the archetype generates. */
    private static final String EXPECTED_MIGRATION_FILE = "V1__create_items.sql";

    /** The Flyway mode both the generated config and the generated test-injected config must select. */
    private static final String EXPECTED_FLYWAY_MODE = "MIGRATE";

    /**
     * The container-side migration call the templates must never make. Migration ownership belongs to
     * the generated application's {@code MIGRATE}-phase Flyway step; a container that migrated for it
     * would leave that step unexercised while still producing a green suite.
     */
    private static final String FORBIDDEN_CONTAINER_MIGRATION = "withMigration";

    /** The frozen class-level execution bound of the generated integration test. */
    private static final int EXPECTED_TIMEOUT_VALUE = 120;

    /** The frozen class-level execution bound unit of the generated integration test. */
    private static final String EXPECTED_TIMEOUT_UNIT = "SECONDS";

    /**
     * Tokens that would let the generated build sidestep a missing or broken Docker daemon instead of
     * failing. Docker is a hard prerequisite of the generated {@code mvn verify}: a JUnit assumption,
     * a disable annotation, a container-availability probe, a skip flag, or a Maven profile would each
     * turn an absent daemon into a silently green build.
     */
    private static final List<String> FORBIDDEN_ESCAPE_TOKENS = List.of(
            "Assumptions",
            "assumeTrue",
            "assumeFalse",
            "assumingThat",
            "@Disabled",
            "@EnabledIf",
            "@DisabledIf",
            "DockerClientFactory",
            "isDockerAvailable",
            "skipTests",
            "skipITs",
            "<profile>",
            "<profiles>");

    /** The exact {@code -D} property set and values of the frozen §4.6 non-interactive generation command. */
    private static final Map<String, String> EXPECTED_GENERATE_PROPERTIES = Map.of(
            "archetypeGroupId", "dev.vertique",
            "archetypeArtifactId", "vertique-archetype-rest-postgresql",
            "archetypeVersion", "<vertiqueVersion>",
            "groupId", "<groupId>",
            "artifactId", "<artifactId>",
            "version", "0.1.0-SNAPSHOT",
            "package", "<packageName>",
            "vertiqueVersion", "<vertiqueVersion>",
            "interactiveMode", "false");

    /**
     * The frozen leading tokens of the §4.6 generation command. Everything after them must be a
     * {@code -D} flag from {@link #EXPECTED_GENERATE_PROPERTIES} — no extra goal, profile, or shell
     * syntax may ride along in the documented command.
     */
    private static final List<String> EXPECTED_GENERATE_COMMAND_TOKENS =
            List.of("mvn", "-B", "-ntp", "archetype:generate");

    /** The complete ordered command list the generated project's README documents. */
    private static final List<String> EXPECTED_GENERATED_APPLICATION_COMMANDS =
            List.of("mvn -ntp exec:java", "mvn -ntp verify", "mvn -ntp package", "mvn -ntp jib:dockerBuild");

    /** The frozen main class both {@code exec-maven-plugin} and {@code jib-maven-plugin} launch. */
    private static final String EXPECTED_MAIN_CLASS = "dev.vertique.launcher.VertiqueApplication";

    /**
     * The non-root uid:gid the generated container image runs as. Pinned rather than left to Jib's
     * default (root), so the image the archetype produces is not root-by-default.
     */
    private static final String EXPECTED_CONTAINER_USER = "65532:65532";

    /**
     * The frozen, whitespace-collapsed sentence tying the Docker-daemon prerequisite to {@code mvn
     * verify}. Pinned verbatim — rather than checked via a generic fragment like {@code "Docker"} —
     * so unrelated prose elsewhere in the section cannot satisfy the assertion, and so removing or
     * rewording the guidance sentence fails the proof.
     */
    private static final String EXPECTED_DOCKER_PREREQUISITE_SENTENCE = "A reachable Docker daemon, required to"
            + " run `mvn verify`: the integration test starts a real PostgreSQL container for the"
            + " application to migrate and connect against";

    /**
     * The frozen, whitespace-collapsed sentence documenting the local connection shape and labeling it
     * development-only and unsuitable for production. Pinned verbatim so a rewording that drops the
     * production warning — rather than merely removing an unrelated fragment — still fails the proof.
     */
    private static final String EXPECTED_LOCAL_CONNECTION_SENTENCE = "Its connection defaults to"
            + " `localhost:5432/vertique` with `vertique`/`vertique` — development-only placeholders,"
            + " unsuitable for production.";

    /** The local PostgreSQL host the generated config's placeholders must resolve to. */
    private static final String EXPECTED_LOCAL_DB_HOST = "localhost";

    /** The local PostgreSQL port the generated config's placeholders must resolve to. */
    private static final int EXPECTED_LOCAL_DB_PORT = 5432;

    /** The local PostgreSQL database name the generated config's placeholders must resolve to. */
    private static final String EXPECTED_LOCAL_DB_NAME = "vertique";

    /** The local PostgreSQL user the generated config's placeholders must resolve to. */
    private static final String EXPECTED_LOCAL_DB_USER = "vertique";

    /** The local PostgreSQL password the generated config's placeholders must resolve to. */
    private static final String EXPECTED_LOCAL_DB_PASSWORD = "vertique";

    /** The archetype family aggregator's declared modules, in the frozen exact declaration order. */
    private static final List<String> EXPECTED_AGGREGATOR_MODULES =
            List.of("vertique-archetype-rest", "vertique-archetype-services", "vertique-archetype-rest-postgresql");

    /**
     * The frozen, whitespace-collapsed constraint declaration both request records must carry on
     * their {@code name} component. Pinned as one contiguous sequence rather than as three
     * independent token probes, so the constraints are proven to sit on {@code name} — not on the
     * unconstrained {@code description} — and in the frozen order.
     */
    private static final String EXPECTED_NAME_CONSTRAINTS =
            "@NotBlank @Pattern(regexp = \"\\\\S\") @Size(max = 255) String name";

    /** The declared type every {@code @PathParam("id")} parameter must carry. */
    private static final String EXPECTED_ID_PARAM_TYPE = "UUID";

    /** The number of id-taking resource methods: {@code getById}, {@code update}, {@code delete}. */
    private static final int EXPECTED_ID_PARAM_COUNT = 3;

    /**
     * Tokens proving a hand-rolled guard has come back. Name presence and identifier parsing are the
     * framework's job — the request-validation gate and the built-in {@code UUID} parameter
     * converter — so a template that parses an id itself or re-checks a name in-resource has
     * silently taken back a responsibility the generated application must delegate.
     */
    private static final List<String> FORBIDDEN_HANDROLLED_GUARD_TOKENS =
            List.of("parseId", "UUID.fromString", "isBlank", "BadRequestException");

    /** The retired top-level archetype coordinate tag; no live generator may republish it. */
    private static final String LEGACY_ARCHETYPE_COORDINATE_TAG = "<artifactId>vertique-archetype</artifactId>";

    /** The Maven Archetype packaging tag; the family aggregator must never carry it. */
    private static final String MAVEN_ARCHETYPE_PACKAGING_TAG = "<packaging>maven-archetype</packaging>";

    // --- Tests ---

    @Test
    @DisplayName("uses both starters, the frozen staging set, and exactly four component modules")
    void usesBothStartersAndExactComponentModules() throws IOException {
        // Given the PostgreSQL REST archetype module and its templates.
        String archetypePom = read(ARCHETYPE_POM);
        String templatePom = read(TEMPLATE_POM);
        String component = stripJavaComments(read(TEMPLATE_COMPONENT));

        // When the coordinate, generated dependency contract, staging set, and component modules are
        // parsed.
        List<Dependency> dependencies = dependenciesOf(templatePom);
        List<Dependency> stagingDependencies = dependenciesOf(archetypePom);
        List<String> componentModules = componentModulesOf(component);

        // Then the archetype publishes the PostgreSQL REST coordinate.
        assertTrue(
                archetypePom.contains("<artifactId>vertique-archetype-rest-postgresql</artifactId>"),
                "archetype coordinate must be vertique-archetype-rest-postgresql");

        // And the generated project inherits the standalone application parent.
        assertTrue(
                templatePom.contains("<artifactId>vertique-app-parent</artifactId>"),
                "generated project must inherit vertique-app-parent");

        // And its declared dependency contract is exactly the frozen list — group, artifact, type,
        // scope, and count all pinned, so nothing can be added, dropped, re-grouped, or re-scoped
        // silently. Both starters are production-scope; the host and every test library are explicit.
        assertEquals(EXPECTED_TEMPLATE_DEPENDENCIES, dependencies);

        // And the archetype module declares exactly the frozen staging set, so the generated project's
        // nested build resolves every internal coordinate from the isolated repository.
        assertEquals(EXPECTED_ARCHETYPE_DEPENDENCIES, stagingDependencies);

        // And the component names exactly the four frozen modules.
        assertEquals(EXPECTED_COMPONENT_MODULES, componentModules);
    }

    @Test
    @DisplayName("owns exactly one migration, selects Flyway MIGRATE everywhere, and never migrates from the container")
    void requiresApplicationOwnedMigration() throws IOException {
        // Given the generated migration directory, application config, and integration test.
        List<String> migrations = migrationFileNames();
        String applicationConfig = read(TEMPLATE_APPLICATION_CONFIG);
        String applicationIt = stripJavaComments(read(TEMPLATE_APPLICATION_IT));

        // Then the application owns exactly one migration script.
        assertEquals(List.of(EXPECTED_MIGRATION_FILE), migrations);

        // And the generated application config selects the MIGRATE Flyway mode structurally, from the
        // flyway section itself rather than from an unanchored literal anywhere in the document.
        Matcher flywaySection = sectionPattern("flyway").matcher(applicationConfig);
        assertTrue(flywaySection.find(), "generated config must declare a flyway section");
        Matcher configuredMode = scalarPattern("mode").matcher(flywaySection.group(1));
        assertTrue(configuredMode.find(), "generated flyway section must declare a mode");
        assertEquals(
                EXPECTED_FLYWAY_MODE,
                configuredMode.group(1),
                "generated config must run the application-owned migration at startup");

        // And the generated integration test injects the same mode, so the proven path is the
        // application's own MIGRATE-phase Flyway step rather than a pre-migrated fixture.
        Matcher injectedMode = INJECTED_FLYWAY_MODE.matcher(applicationIt);
        assertTrue(
                injectedMode.find(),
                "generated integration test must inject flyway.mode through .put(\"flyway\", new JsonObject()"
                        + ".put(\"mode\", …))");
        assertEquals(
                EXPECTED_FLYWAY_MODE,
                injectedMode.group(1),
                "generated integration test must exercise the application's own migration step");

        // And no template anywhere delegates migration to the test container.
        for (Path template : templateFiles()) {
            assertFalse(
                    read(template).contains(FORBIDDEN_CONTAINER_MIGRATION),
                    () -> "template " + template + " must not call PostgresContainer." + FORBIDDEN_CONTAINER_MIGRATION
                            + "(): the generated application owns its migrations");
        }
    }

    /**
     * Proves the generated integration test requires a real Docker-backed PostgreSQL instance and
     * bounds its lifecycle.
     *
     * <p><strong>Deliberate bound.</strong> The declaration-order half is a structural <em>text</em>
     * proof, not Java parsing: it compares the source offsets of the container field, its static-block
     * start, and the extension field. Static field and static initializer execution follows source
     * order, so the offsets are the ordering contract. A test that moved the container start into a
     * {@code @BeforeAll} method would evade the offset comparison; that residual is accepted knowingly
     * — closing it would require real Java parsing, which is disproportionate for a template this
     * small and frozen, and the frozen {@code supportsItemCrud} case would fail outright against an
     * unstarted container anyway.
     *
     * @throws IOException when a template cannot be read
     */
    @Test
    @DisplayName(
            "starts the container before the extension, bounds the class at 120 seconds, and offers no skip escape")
    void enforcesDockerAndBoundedContainerLifecycle() throws IOException {
        // Given the generated integration test with its comments stripped, so only live code is parsed.
        String applicationIt = stripJavaComments(read(TEMPLATE_APPLICATION_IT));

        // Then it drives a real Docker-backed PostgreSQL instance rather than an in-process substitute.
        assertTrue(
                applicationIt.contains("import dev.vertique.db.test.PostgresContainer;"),
                "generated integration test must drive a real PostgresContainer");

        // And the container is declared and started strictly before the application extension is
        // constructed: static field and static initializer execution follows source order, so the
        // container is up — and its mapped connection identity readable — before the extension's
        // beforeAll boots the application and its MIGRATE-phase Flyway step connects.
        int containerAt = requiredStart(CONTAINER_DECLARATION, applicationIt, "PostgresContainer field declaration");
        int startAt = requiredStart(CONTAINER_START, applicationIt, "container start");
        int extensionAt = requiredStart(EXTENSION_DECLARATION, applicationIt, "VertiqueAppExtension field declaration");
        assertTrue(
                containerAt < startAt,
                "the PostgresContainer field must be declared before it is started, found declaration at " + containerAt
                        + " and start at " + startAt);
        assertTrue(
                startAt < extensionAt,
                "the container must be started before the VertiqueAppExtension is constructed, found start at "
                        + startAt + " and extension at " + extensionAt);

        // And the class's test method carries the frozen bound — the HTTP journey, not the
        // container start: JUnit applies a class-level @Timeout to test methods only, the container
        // is started from the static initializer (bounded by vertique-db-test's own 120-second
        // startup timeout), and the extension's beforeAll carries its own 30-second start timeout.
        // This class is raised from the framework's 20-second default because it drives a journey
        // against a freshly provisioned database rather than an in-process fixture.
        Matcher timeout = CLASS_TIMEOUT.matcher(applicationIt);
        assertTrue(timeout.find(), "generated integration test must declare a class-level @Timeout");
        assertEquals(
                EXPECTED_TIMEOUT_VALUE,
                Integer.parseInt(timeout.group(1)),
                "generated integration test must bound its test method at the frozen allowance");
        assertEquals(EXPECTED_TIMEOUT_UNIT, timeout.group(2), "the frozen allowance is expressed in seconds");

        // And teardown always resets REST Assured and closes the container: the body is unconditional
        // (no branch can skip either call) and the close sits in a finally block, so a failing reset
        // cannot leak the container's provisioned database.
        String tearDown = afterAllBodyOf(applicationIt);
        assertTrue(tearDown.contains("RestAssured.reset();"), "@AfterAll must reset REST Assured, found:\n" + tearDown);
        assertTrue(tearDown.contains("db.close();"), "@AfterAll must close the container, found:\n" + tearDown);
        assertFalse(tearDown.contains("if ("), "@AfterAll teardown must be unconditional, found:\n" + tearDown);
        assertTrue(
                tearDown.indexOf("finally") < tearDown.indexOf("db.close();"),
                "@AfterAll must close the container from a finally block, found:\n" + tearDown);

        // And no template offers a route around an absent Docker daemon: Docker is a hard prerequisite
        // of the generated `mvn verify`, so a missing daemon must fail the build rather than pass it.
        for (Path template : templateFiles()) {
            String text = read(template);
            FORBIDDEN_ESCAPE_TOKENS.forEach(token -> assertFalse(
                    text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT)),
                    () -> "template " + template + " must offer no Docker skip escape, found: " + token));
        }
        String archetypePom = read(ARCHETYPE_POM);
        FORBIDDEN_ESCAPE_TOKENS.forEach(token -> assertFalse(
                archetypePom.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT)),
                () -> "archetype POM must offer no Docker skip escape, found: " + token));
    }

    /**
     * Proves the generated application module contributes exactly the two frozen deployments.
     *
     * <p><strong>Deliberate bound.</strong> This is a structural <em>text</em> proof, not Java
     * parsing. Its discovery key is the declared return type {@code VerticleDeployment}: any method
     * declaring that return type is treated as a contribution and must satisfy the full contract, so
     * a non-canonical addition is detected rather than ignored. A method that hides its return type
     * behind a type alias, {@code var}, or a generic factory would evade discovery. That residual is
     * accepted knowingly — closing it would require real Java parsing, which is disproportionate to
     * the risk of a template this small and frozen.
     *
     * @throws IOException when a template cannot be read
     */
    @Test
    @DisplayName("contributes exactly management at INFRA and http at EDGE")
    void usesExactDeploymentIdsAndPhases() throws IOException {
        // Given the generated application module template with its comments stripped, so only live
        // code is parsed.
        String appModule = stripJavaComments(read(TEMPLATE_APP_MODULE));

        // When every method declaring a VerticleDeployment return type is isolated with its body.
        List<DeploymentProvider> providers = deploymentProvidersOf(appModule);

        // Then exactly the two frozen contributions exist — persistence adds no deployment of its own.
        assertEquals(2, providers.size(), "generated AppModule must declare exactly two deployment providers");

        // And each is a static multibinding contribution — annotation order is irrelevant, presence
        // is not.
        providers.forEach(provider -> {
            assertTrue(
                    provider.annotations().containsAll(REQUIRED_PROVIDER_ANNOTATIONS),
                    () -> "deployment provider " + provider.name() + " must carry " + REQUIRED_PROVIDER_ANNOTATIONS
                            + ", found " + provider.annotations());
            assertTrue(provider.isStatic(), () -> "deployment provider " + provider.name() + " must be static");
        });

        // And each provider's entire body is a single recognized construction — not merely a body
        // that contains one somewhere, so neither a delegating return beside a stray construction nor
        // an extra return path can be credited. The frozen id/phase set is derived from exactly those
        // whole-body constructions.
        List<String> deployments = providers.stream()
                .map(RestPostgresqlArchetypeContractTest::returnedDeploymentOf)
                .sorted()
                .toList();
        assertEquals(EXPECTED_DEPLOYMENTS, deployments);

        // And no deployment is built by direct construction anywhere in the module.
        assertFalse(
                appModule.contains("new VerticleDeployment("),
                "generated AppModule must build deployments through VerticleDeployment.of(…)");
    }

    @Test
    @DisplayName("documents the Docker prerequisite for mvn verify and labels the local connection as development-only")
    void documentsDockerAndLocalOnlyCredentials() throws IOException {
        // Given the generated project's README and application config.
        String readme = read(TEMPLATE_README);
        String applicationConfig = read(TEMPLATE_APPLICATION_CONFIG);

        // When the Prerequisites section is isolated and its wrapped prose collapsed to single
        // spaces, so a phrase split across a markdown line wrap is still one contiguous match.
        String prerequisites = collapseWhitespace(sectionOf(readme, "## Prerequisites"));

        // Then it states the Docker daemon prerequisite via the frozen sentence tying it to
        // `mvn verify`.
        assertTrue(
                prerequisites.contains(EXPECTED_DOCKER_PREREQUISITE_SENTENCE),
                () -> "README Prerequisites section must state the frozen Docker prerequisite: "
                        + EXPECTED_DOCKER_PREREQUISITE_SENTENCE);

        // When the Database section is isolated the same way.
        String database = collapseWhitespace(sectionOf(readme, "## Database"));

        // Then it documents the exact local connection shape and labels it development-only and
        // unsuitable for production via the frozen sentence.
        assertTrue(
                database.contains(EXPECTED_LOCAL_CONNECTION_SENTENCE),
                () -> "README Database section must state the frozen local-connection sentence: "
                        + EXPECTED_LOCAL_CONNECTION_SENTENCE);

        // And the generated config's db section structurally carries the same local-only
        // placeholders, isolated from the sibling "http"/"management"/"flyway" sections so an
        // unrelated port cannot satisfy the check.
        Matcher dbSection = sectionPattern("db").matcher(applicationConfig);
        assertTrue(dbSection.find(), "generated config must declare a db section");
        String db = dbSection.group(1);
        assertEquals(
                EXPECTED_LOCAL_DB_HOST,
                firstGroup(scalarPattern("host"), db),
                "generated config db.host must be local-only");
        assertEquals(
                EXPECTED_LOCAL_DB_PORT,
                Integer.parseInt(firstGroup(CONFIG_PORT, db)),
                "generated config db.port must be local-only");
        assertEquals(
                EXPECTED_LOCAL_DB_NAME,
                firstGroup(scalarPattern("database"), db),
                "generated config db.database must be local-only");
        assertEquals(
                EXPECTED_LOCAL_DB_USER,
                firstGroup(scalarPattern("user"), db),
                "generated config db.user must be local-only");
        assertEquals(
                EXPECTED_LOCAL_DB_PASSWORD,
                firstGroup(scalarPattern("password"), db),
                "generated config db.password must be local-only");
    }

    @Test
    @DisplayName(
            "documents the PostgreSQL REST generation command and the four supported generated-application commands")
    void documentsSupportedCommands() throws IOException {
        // Given the PostgreSQL REST archetype's own README and the generated project's README/POM
        // templates.
        String archetypeReadme = read(ARCHETYPE_README);
        String generatedReadme = read(TEMPLATE_README);
        String templatePom = read(TEMPLATE_POM);

        // When the archetype README's documented commands are parsed, one command per
        // continuation-joined line.
        List<String> generationCommands = commandsIn(archetypeReadme).stream()
                .filter(command -> command.contains("archetype:generate"))
                .toList();

        // Then exactly one generation command is documented.
        assertEquals(
                1, generationCommands.size(), "archetype README must document exactly one archetype:generate command");
        String generationCommand = generationCommands.get(0);

        // And its leading tokens are exactly the frozen batch-mode goal invocation.
        List<String> tokens = List.of(generationCommand.split("\\s+"));
        int goalTokens = EXPECTED_GENERATE_COMMAND_TOKENS.size();
        assertEquals(
                EXPECTED_GENERATE_COMMAND_TOKENS,
                tokens.subList(0, Math.min(goalTokens, tokens.size())),
                "generation command must invoke the frozen batch-mode goal");

        // And every remaining token is a -D flag — no extra goal, profile, or shell syntax rides along.
        List<String> flags = tokens.subList(goalTokens, tokens.size());
        flags.forEach(flag -> assertTrue(
                GENERATE_PROPERTY.matcher(flag).matches(),
                () -> "generation command must carry only -Dkey=value flags after the goal, found: " + flag));

        // And those flags are exactly the frozen §4.6 properties, each with its frozen value — a
        // duplicated flag fails the parse rather than being silently collapsed.
        assertEquals(EXPECTED_GENERATE_PROPERTIES, generationPropertiesOf(generationCommand));

        // And the JDK and Maven prerequisites are stated on the archetype README.
        assertTrue(archetypeReadme.contains("JDK 21"), "archetype README must state the JDK 21 prerequisite");
        assertTrue(
                archetypeReadme.toLowerCase(Locale.ROOT).contains("maven"),
                "archetype README must state the Maven prerequisite");

        // When every command the generated README documents is parsed, in document order.
        List<String> documentedCommands = commandsIn(generatedReadme);

        // Then exactly those four commands are documented — no more, in that order.
        assertEquals(EXPECTED_GENERATED_APPLICATION_COMMANDS, documentedCommands);

        // And the JDK/Maven prerequisites are stated on the generated README.
        assertTrue(generatedReadme.contains("JDK 21"), "generated README must state the JDK 21 prerequisite");
        assertTrue(
                generatedReadme.toLowerCase(Locale.ROOT).contains("maven"),
                "generated README must state the Maven prerequisite");

        // And exec-maven-plugin and jib-maven-plugin structurally launch the same main class.
        assertEquals(EXPECTED_MAIN_CLASS, execMainClassOf(templatePom));
        assertEquals(EXPECTED_MAIN_CLASS, jibContainerMainClassOf(templatePom));

        // And the container image structurally runs as a pinned non-root user rather than as root.
        assertEquals(EXPECTED_CONTAINER_USER, jibContainerUserOf(templatePom));
    }

    @Test
    @DisplayName("delegates name validation and id conversion to the framework, leaving no hand-rolled guard")
    void delegatesValidationAndIdConversionToTheFramework() throws IOException {
        // Given the two request records and the resource template, comments stripped and whitespace
        // collapsed so a formatter line wrap cannot break a contiguous match.
        String createRequest = collapseWhitespace(stripJavaComments(read(TEMPLATE_CREATE_REQUEST)));
        String updateRequest = collapseWhitespace(stripJavaComments(read(TEMPLATE_UPDATE_REQUEST)));
        String itemResource = stripJavaComments(read(TEMPLATE_ITEM_RESOURCE));

        // Then both request records constrain name — and only name — with the frozen trio: presence
        // and non-emptiness (@NotBlank), whitespace-only rejection (@Pattern), and the column width
        // (@Size). Each is what one of the generated integration test's 400 steps proves.
        assertTrue(
                createRequest.contains(EXPECTED_NAME_CONSTRAINTS),
                () -> "CreateItemRequest must declare " + EXPECTED_NAME_CONSTRAINTS + ", found:\n" + createRequest);
        assertTrue(
                updateRequest.contains(EXPECTED_NAME_CONSTRAINTS),
                () -> "UpdateItemRequest must declare " + EXPECTED_NAME_CONSTRAINTS + ", found:\n" + updateRequest);

        // And every id path parameter is declared as a UUID, so conversion — and the 400 for a
        // malformed value — is the built-in converter's rather than the resource's. The count is
        // pinned too: an id-taking method that regressed to a raw String is caught by the type
        // assertion, and one that lost its @PathParam entirely is caught by the count.
        List<String> idParamTypes = ID_PATH_PARAM
                .matcher(itemResource)
                .results()
                .map(match -> match.group(1))
                .toList();
        assertEquals(
                EXPECTED_ID_PARAM_COUNT,
                idParamTypes.size(),
                () -> "ItemResource must declare exactly " + EXPECTED_ID_PARAM_COUNT
                        + " id path parameters (getById, update, delete), found " + idParamTypes);
        idParamTypes.forEach(type -> assertEquals(
                EXPECTED_ID_PARAM_TYPE, type, "every id path parameter must be converted by the framework"));

        // And no generated Java source anywhere reinstates a hand-rolled guard: no identifier
        // parsing, no in-resource name check, no directly thrown 400. Comments are stripped first,
        // so prose explaining why the framework owns these checks is not mistaken for one of them.
        for (Path template : templateFiles()) {
            if (!template.toString().endsWith(".java")) {
                continue;
            }
            String code = stripJavaComments(read(template));
            FORBIDDEN_HANDROLLED_GUARD_TOKENS.forEach(token -> assertFalse(
                    code.contains(token),
                    () -> "template " + template + " must leave validation and conversion to the framework, found: "
                            + token));
        }
    }

    @Test
    @DisplayName("lists exactly the three completed archetype children with no placeholder or retired top-level alias")
    void listsExactlyCompletedArchetypes() throws IOException {
        // Given the archetype family aggregator POM.
        String aggregatorPom = read(AGGREGATOR_POM);

        // When its declared modules are parsed, in declaration order.
        List<String> modules = modulesOf(aggregatorPom);

        // Then it lists exactly the three completed archetype children — no placeholder module, and
        // none dropped or reordered silently.
        assertEquals(EXPECTED_AGGREGATOR_MODULES, modules);

        // And the aggregator itself is a grouping-only artifact, never a generator.
        assertTrue(aggregatorPom.contains("<packaging>pom</packaging>"), "aggregator must be packaging=pom");
        assertFalse(
                aggregatorPom.contains(MAVEN_ARCHETYPE_PACKAGING_TAG),
                "aggregator must not itself be packaged as a maven-archetype");

        // And no completed child republishes the retired top-level coordinate as a generator: every
        // child POM must declare its own distinct, longer artifactId, never the bare aggregator alias.
        List<Path> childPoms = Stream.concat(SIBLING_ARCHETYPE_POMS.stream(), Stream.of(ARCHETYPE_POM))
                .toList();
        for (Path childPom : childPoms) {
            String childPomText = read(childPom);
            assertFalse(
                    childPomText.contains(LEGACY_ARCHETYPE_COORDINATE_TAG),
                    () -> childPom + " must not republish the retired coordinate " + LEGACY_ARCHETYPE_COORDINATE_TAG);
        }
    }

    // --- Helpers ---

    /**
     * Reads a template file relative to this module's base directory.
     *
     * @param relative path relative to the archetype module basedir
     * @return the file's UTF-8 content
     * @throws IOException when the file cannot be read
     */
    private static String read(Path relative) throws IOException {
        return Files.readString(relative);
    }

    /**
     * Lists every file the archetype generates, so whole-template scans cover the complete generated
     * output rather than a hand-picked subset. An added template is therefore in scope automatically.
     *
     * @return every regular file beneath the archetype resources, in stable path order
     * @throws IOException when the template tree cannot be walked
     */
    private static List<Path> templateFiles() throws IOException {
        try (Stream<Path> tree = Files.walk(ARCHETYPE_RESOURCES)) {
            List<Path> files = tree.filter(Files::isRegularFile).sorted().toList();
            assertFalse(files.isEmpty(), "archetype resources must contain generated templates");
            return files;
        }
    }

    /**
     * Lists the file names of every generated migration script, so the migration set is compared
     * exhaustively rather than probed for one expected member.
     *
     * @return the migration file names, in stable order
     * @throws IOException when the migration directory cannot be walked
     */
    private static List<String> migrationFileNames() throws IOException {
        assertTrue(
                Files.isDirectory(TEMPLATE_MIGRATIONS),
                () -> "archetype must generate a migration directory at " + TEMPLATE_MIGRATIONS);
        try (Stream<Path> tree = Files.walk(TEMPLATE_MIGRATIONS)) {
            return tree.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /**
     * Extracts every {@code <dependency>} declared directly under {@code <project>}. XML comments are
     * stripped first, so a commented-out declaration is absent from the result rather than parsed as
     * a live one.
     *
     * @param pom the POM text
     * @return the declared dependencies in declaration order
     */
    private static List<Dependency> dependenciesOf(String pom) {
        Matcher block = PROJECT_DEPENDENCIES.matcher(stripXmlComments(pom));
        assertTrue(block.find(), "POM must declare a project-level <dependencies> block");
        return DEPENDENCY
                .matcher(block.group(1))
                .results()
                .map(match -> new Dependency(
                        firstGroup(GROUP_ID, match.group(1)),
                        firstGroup(ARTIFACT_ID, match.group(1)),
                        firstGroup(TYPE, match.group(1)),
                        firstGroup(SCOPE, match.group(1))))
                .toList();
    }

    /**
     * Removes every XML comment from a template.
     *
     * @param xml the template text
     * @return the same text with all {@code <!-- … -->} spans removed
     */
    private static String stripXmlComments(String xml) {
        return XML_COMMENT.matcher(xml).replaceAll("");
    }

    /**
     * Removes every block and line comment from a Java template.
     *
     * @param java the template text
     * @return the same text with all comment spans removed
     */
    private static String stripJavaComments(String java) {
        return JAVA_COMMENT.matcher(java).replaceAll("");
    }

    /**
     * Extracts the module class literals named by the generated component's {@code @Component}. Every
     * non-empty comma-separated token must be a class literal — an entry the parse cannot classify
     * fails the proof rather than being filtered away.
     *
     * @param component the comment-stripped component template text
     * @return the module class literals, sorted for order-independent comparison
     */
    private static List<String> componentModulesOf(String component) {
        Matcher modules = COMPONENT_MODULES.matcher(component);
        assertTrue(modules.find(), "generated component must declare @Component(modules = { … })");
        List<String> entries = Arrays.stream(modules.group(1).split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .sorted()
                .toList();
        entries.forEach(entry -> assertTrue(
                entry.endsWith(".class"),
                () -> "every @Component modules entry must be a class literal, found: " + entry));
        return entries;
    }

    /**
     * Returns the start offset of a pattern's first match, failing the proof when it is absent.
     *
     * @param pattern the pattern to locate
     * @param source the comment-stripped template text
     * @param description what the pattern locates, used in the failure message
     * @return the match's start offset
     */
    private static int requiredStart(Pattern pattern, String source, String description) {
        Matcher matcher = pattern.matcher(source);
        assertTrue(matcher.find(), () -> "generated integration test must declare its " + description);
        return matcher.start();
    }

    /**
     * Returns the brace-balanced body of the method annotated {@code @AfterAll}.
     *
     * @param applicationIt the comment-stripped integration-test template text
     * @return the teardown method's body text
     */
    private static String afterAllBodyOf(String applicationIt) {
        int annotation = applicationIt.indexOf("@AfterAll");
        assertTrue(annotation >= 0, "generated integration test must declare an @AfterAll teardown");
        return methodBodyAfter(applicationIt, annotation);
    }

    /**
     * Discovers every method declaring a {@code VerticleDeployment} return type, in declaration
     * order, capturing each one's annotations, {@code static} modifier, and body.
     *
     * <p>Discovery is by return type rather than by annotation prefix: a contribution that reorders
     * or drops its annotations is still found, and then fails the annotation assertions, instead of
     * disappearing from the proof entirely.
     *
     * @param appModule the comment-stripped application module template text
     * @return one entry per discovered method
     */
    private static List<DeploymentProvider> deploymentProvidersOf(String appModule) {
        List<DeploymentProvider> providers = new ArrayList<>();
        Matcher signature = DEPLOYMENT_RETURNING_METHOD.matcher(appModule);
        while (signature.find()) {
            String prefix = appModule.substring(memberStartBefore(appModule, signature.start()), signature.start());
            providers.add(new DeploymentProvider(
                    signature.group(1),
                    Set.copyOf(ANNOTATION
                            .matcher(prefix)
                            .results()
                            .map(MatchResult::group)
                            .toList()),
                    MODIFIER_STATIC.matcher(prefix).find(),
                    methodBodyAfter(appModule, signature.end())));
        }
        return List.copyOf(providers);
    }

    /**
     * Locates the start of the member declaration containing {@code signatureStart} by scanning back
     * to the preceding member boundary, so the returned span holds only that member's annotations and
     * modifiers.
     *
     * @param source the comment-stripped template text
     * @param signatureStart the index of the member's return type
     * @return the index just past the preceding {@code &#123;}, {@code &#125;}, or {@code ;}
     */
    private static int memberStartBefore(String source, int signatureStart) {
        for (int index = signatureStart - 1; index >= 0; index--) {
            char character = source.charAt(index);
            if (character == '{' || character == '}' || character == ';') {
                return index + 1;
            }
        }
        return 0;
    }

    /**
     * Returns the brace-balanced method body opening at or after {@code signatureStart}. Brace
     * counting assumes no brace appears inside a string or character literal, which holds for these
     * templates.
     *
     * @param source the comment-stripped template text
     * @param signatureStart the index to begin searching for the body's opening brace
     * @return the body text between the outermost braces
     */
    private static String methodBodyAfter(String source, int signatureStart) {
        int open = source.indexOf('{', signatureStart);
        assertTrue(open >= 0, "the discovered member must declare a method body");
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open + 1, index);
            }
        }
        throw new AssertionError("the discovered member's method body is not brace-balanced");
    }

    /**
     * Extracts the {@code return VerticleDeployment.of(…);} that must constitute a provider's entire
     * body.
     *
     * <p>The match is a <em>full</em> match against the trimmed body rather than a count of
     * occurrences: counting would accept a body that reaches the expected construction down one path
     * while returning something else down another (a guard clause returning early, or the expected
     * construction buried in a nested lambda beside a delegating top-level return). Requiring the
     * body to <em>be</em> the single statement rules all of those out at once, which the frozen
     * single-statement provider shape makes the proportionate check.
     *
     * @param provider one discovered provider
     * @return the returned construction's {@code id@phase}
     */
    private static String returnedDeploymentOf(DeploymentProvider provider) {
        String body = provider.body().trim();
        Matcher returned = RETURNED_DEPLOYMENT.matcher(body);
        assertTrue(
                returned.matches(),
                () -> "deployment provider " + provider.name()
                        + " body must be exactly one return VerticleDeployment.of(…); statement, found:\n" + body);
        return returned.group(1) + "@" + returned.group(2);
    }

    /**
     * Builds a pattern matching one named JSON object together with its body, capturing the body. The
     * body is matched brace-free, so the pattern is exact for the frozen flat sections of the
     * generated application config and stops at a nested object rather than swallowing it.
     *
     * @param name the object's key, matched literally
     * @return a pattern whose first group is the section body
     */
    private static Pattern sectionPattern(String name) {
        return Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\{([^{}]*)}");
    }

    /**
     * Builds a pattern matching one string-valued scalar entry of a JSON config section, capturing the
     * value. Numeric entries are not expressible here — see {@link #CONFIG_PORT}.
     *
     * @param key the entry's key, matched literally
     * @return a pattern whose first group is the quoted value's content
     */
    private static Pattern scalarPattern(String key) {
        return Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
    }

    /**
     * Returns the first capturing group of {@code pattern} within {@code text}.
     *
     * @param pattern the pattern to apply
     * @param text the text to search
     * @return the first captured value, or {@code null} when the pattern does not match
     */
    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Normalizes every fenced {@code ```bash``` } block of a markdown document into individual
     * commands: backslash-continued lines are joined into the command they belong to, and each
     * remaining non-empty line is one command. Assertions can therefore count and compare commands
     * rather than blocks.
     *
     * @param markdown the markdown document text
     * @return the documented commands, in document order
     */
    private static List<String> commandsIn(String markdown) {
        return FENCED_BASH_BLOCK
                .matcher(markdown)
                .results()
                .map(match -> LINE_CONTINUATION.matcher(match.group(1)).replaceAll(" "))
                .flatMap(String::lines)
                .map(String::trim)
                .filter(command -> !command.isEmpty())
                .toList();
    }

    /**
     * Parses every {@code -D} property set by a generation command. A repeated key fails the proof:
     * a duplicated flag is ambiguous documentation, not a value to silently collapse.
     *
     * @param command the command text
     * @return property names mapped to their assigned (possibly empty) values
     */
    private static Map<String, String> generationPropertiesOf(String command) {
        Map<String, String> properties = new LinkedHashMap<>();
        GENERATE_PROPERTY
                .matcher(command)
                .results()
                .forEach(match -> assertNull(
                        properties.put(match.group(1), match.group(2)),
                        () -> "generation command must set -D" + match.group(1) + " exactly once"));
        return properties;
    }

    /**
     * Locates the {@code <plugin>} declaration for a given artifact identifier. XML comments are
     * stripped first, so a commented-out element inside the plugin cannot be read as configured.
     *
     * @param pom the POM template text
     * @param artifactId the plugin's artifact identifier
     * @return the plugin's declaration body
     */
    private static String pluginBlockFor(String pom, String artifactId) {
        Matcher plugins = PLUGIN_BLOCK.matcher(stripXmlComments(pom));
        while (plugins.find()) {
            String block = plugins.group(1);
            if (block.contains("<artifactId>" + artifactId + "</artifactId>")) {
                return block;
            }
        }
        throw new AssertionError("template POM must declare " + artifactId);
    }

    /**
     * Extracts the {@code exec-maven-plugin} main class configured in the template POM.
     *
     * @param pom the POM template text
     * @return the configured main class
     */
    private static String execMainClassOf(String pom) {
        return firstGroup(MAIN_CLASS, pluginBlockFor(pom, "exec-maven-plugin"));
    }

    /**
     * Extracts the {@code jib-maven-plugin}'s {@code <container>} configuration body.
     *
     * @param pom the POM template text
     * @return the container configuration body
     */
    private static String jibContainerOf(String pom) {
        Matcher container = CONTAINER_BLOCK.matcher(pluginBlockFor(pom, "jib-maven-plugin"));
        assertTrue(container.find(), "jib-maven-plugin must declare a <container> configuration");
        return container.group(1);
    }

    /**
     * Extracts the {@code jib-maven-plugin} container main class configured in the template POM.
     *
     * @param pom the POM template text
     * @return the configured container main class
     */
    private static String jibContainerMainClassOf(String pom) {
        return firstGroup(MAIN_CLASS, jibContainerOf(pom));
    }

    /**
     * Extracts the {@code jib-maven-plugin} container user configured in the template POM.
     *
     * @param pom the POM template text
     * @return the configured {@code uid:gid}, or {@code null} when the container declares no user
     */
    private static String jibContainerUserOf(String pom) {
        return firstGroup(CONTAINER_USER, jibContainerOf(pom));
    }

    /**
     * Isolates the text of one level-2 markdown section, from its heading line up to (but not
     * including) the next level-2 heading or the end of the document.
     *
     * @param markdown the markdown document text
     * @param heading the exact {@code "## Heading"} line that starts the section
     * @return the section text, including its heading line
     */
    private static String sectionOf(String markdown, String heading) {
        int start = markdown.indexOf(heading);
        assertTrue(start >= 0, () -> "document must contain the section heading: " + heading);
        Matcher nextHeading = HEADING_2.matcher(markdown);
        int end = markdown.length();
        while (nextHeading.find()) {
            if (nextHeading.start() > start) {
                end = nextHeading.start();
                break;
            }
        }
        return markdown.substring(start, end);
    }

    /**
     * Collapses every run of whitespace — including a markdown line wrap's newline — into a single
     * space, so a prose phrase split across a wrapped line is still one contiguous substring to match
     * against.
     *
     * @param text the text to normalize
     * @return the text with every whitespace run replaced by a single space
     */
    private static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ");
    }

    /**
     * Extracts every {@code <module>} entry declared within an aggregator POM's {@code <modules>}
     * block. XML comments are stripped first, so a commented-out entry is absent from the result.
     *
     * @param pom the aggregator POM text
     * @return the declared module names, in declaration order
     */
    private static List<String> modulesOf(String pom) {
        Matcher block = MODULES_BLOCK.matcher(stripXmlComments(pom));
        assertTrue(block.find(), "aggregator POM must declare a <modules> block");
        return MODULE_ENTRY
                .matcher(block.group(1))
                .results()
                .map(match -> match.group(1).trim())
                .toList();
    }

    /**
     * One parsed Maven dependency declaration.
     *
     * @param groupId the declared group identifier
     * @param artifactId the declared artifact identifier
     * @param type the declared type, or {@code null} when the implicit {@code jar} type applies
     * @param scope the declared scope, or {@code null} when the implicit compile scope applies
     */
    private record Dependency(String groupId, String artifactId, String type, String scope) {}

    /**
     * One method discovered by its {@code VerticleDeployment} return type.
     *
     * @param name the method name
     * @param annotations the annotation names preceding the signature, as an order-insensitive set
     * @param isStatic whether the declaration carries the {@code static} modifier
     * @param body the method's brace-balanced body text
     */
    private record DeploymentProvider(String name, Set<String> annotations, boolean isStatic, String body) {}
}
