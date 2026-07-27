// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype.restpostgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 * generated integration test's Docker-backed container lifecycle, and the generated deployment
 * identifiers/phases match the frozen PostgreSQL REST contracts. The templates are Velocity sources
 * rather than compilable Java, so every assertion is made against the template text.
 *
 * <p>Each parse is <em>exhaustive</em> rather than filtering: the full parsed dependency list, the
 * component module set, the migration file set, and the deployment set are compared against frozen
 * expectations, and any token the parse cannot classify fails the proof instead of being dropped. A
 * declaration that a filtering parse would quietly skip — a commented-out dependency, an extra
 * scope, a second migration, a deployment built by other means — is therefore caught rather than
 * absorbed.
 *
 * <p>This class is deliberately kept textually parallel to the REST and services archetypes' contract
 * tests rather than sharing a common seam: the three proofs freeze different contracts, and
 * extracting the shared machinery is a separate, later concern.
 */
class RestPostgresqlArchetypeContractTest {

    // --- Template locations (relative to the archetype module basedir) ---

    private static final Path ARCHETYPE_POM = Path.of("pom.xml");
    private static final Path ARCHETYPE_RESOURCES = Path.of("src", "main", "resources", "archetype-resources");
    private static final Path TEMPLATE_POM = ARCHETYPE_RESOURCES.resolve("pom.xml");
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
     * "/items/"}), none of which contain a delimiter, so the simple form is exact here. A template
     * that gains such a literal must move to a literal-aware scan.
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
     * Matches the {@code "flyway"} object of the generated application config together with its body.
     * The body is brace-free, so the non-greedy character class is exact for the frozen flat section.
     */
    private static final Pattern CONFIG_FLYWAY_SECTION = Pattern.compile("\"flyway\"\\s*:\\s*\\{([^{}]*)}");

    /** Matches the {@code "mode"} entry of a JSON config section. */
    private static final Pattern CONFIG_MODE = Pattern.compile("\"mode\"\\s*:\\s*\"([A-Z_]+)\"");

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
        Matcher flywaySection = CONFIG_FLYWAY_SECTION.matcher(applicationConfig);
        assertTrue(flywaySection.find(), "generated config must declare a flyway section");
        Matcher configuredMode = CONFIG_MODE.matcher(flywaySection.group(1));
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

        // And class execution carries the frozen bound: a PostgreSQL cold start is slower than the
        // framework's 20-second non-Docker default, so this class — and only this class — is bounded
        // at 120 seconds.
        Matcher timeout = CLASS_TIMEOUT.matcher(applicationIt);
        assertTrue(timeout.find(), "generated integration test must declare a class-level @Timeout");
        assertEquals(
                EXPECTED_TIMEOUT_VALUE,
                Integer.parseInt(timeout.group(1)),
                "generated integration test must bound class execution at the frozen cold-start allowance");
        assertEquals(
                EXPECTED_TIMEOUT_UNIT, timeout.group(2), "the frozen cold-start allowance is expressed in seconds");

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
