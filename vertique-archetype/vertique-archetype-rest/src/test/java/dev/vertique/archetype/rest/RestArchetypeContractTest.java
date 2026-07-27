// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-template contract proof for the REST archetype.
 *
 * <p>Verifies that the archetype coordinate, the generated project's dependency contract, the
 * generated Dagger component's module set, and the generated deployment identifiers/phases match
 * the frozen REST contracts. The templates are Velocity sources rather than compilable Java, so
 * every assertion is made against the template text.
 *
 * <p>Each parse is <em>exhaustive</em> rather than filtering: the full parsed dependency list,
 * component module set, deployment set, and documented command list are compared against frozen
 * expectations, and any token the parse cannot classify fails the proof instead of being dropped.
 * A declaration that a filtering parse would quietly skip — a commented-out dependency, an extra
 * scope, a deployment built by other means, a duplicated {@code -D} flag, an undocumented command —
 * is therefore caught rather than absorbed.
 */
class RestArchetypeContractTest {

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

    // --- Template parsing ---

    /**
     * Matches an XML comment. Stripped before any structural parse so a commented-out declaration
     * cannot be mistaken for a live one — the templates are Velocity sources (the POM carries a
     * leading {@code #set} directive), so they are parsed as text rather than as XML documents.
     */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** Matches the {@code <dependencies>} block that is a direct child of {@code <project>}. */
    private static final Pattern PROJECT_DEPENDENCIES =
            Pattern.compile("\\R {4}<dependencies>\\R(.*?)\\R {4}</dependencies>", Pattern.DOTALL);

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern SCOPE = Pattern.compile("<scope>([^<]+)</scope>");

    /** Matches the {@code modules = { … }} member of the generated component's {@code @Component}. */
    private static final Pattern COMPONENT_MODULES = Pattern.compile("modules\\s*=\\s*\\{(.*?)}", Pattern.DOTALL);

    /** Matches one {@code VerticleDeployment.of("id", …, LifecyclePhase.PHASE)} contribution. */
    private static final Pattern DEPLOYMENT = Pattern.compile(
            "VerticleDeployment\\.of\\(\\s*\"([^\"]+)\"\\s*,[^,]+,\\s*LifecyclePhase\\.([A-Z_]+)\\s*\\)");

    /**
     * Matches one {@code @Provides @IntoSet static VerticleDeployment …(…)} provider method header,
     * regardless of how the returned deployment is constructed.
     */
    private static final Pattern DEPLOYMENT_PROVIDER =
            Pattern.compile("@Provides\\s+@IntoSet\\s+static\\s+VerticleDeployment\\s+\\w+\\s*\\(");

    /** Tokens that would indicate a concrete JWT/JOSE token mechanism. */
    private static final List<String> MECHANISM_TOKENS = List.of("jwt", "jose", "jwks");

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

    // --- Frozen contracts ---

    /**
     * The complete dependency contract of the generated project, in declaration order. Group,
     * artifact, and scope are each load-bearing, and the list is exhaustive: any added, removed,
     * re-grouped, or re-scoped declaration is a consumer-visible change to what the archetype
     * generates.
     */
    private static final List<Dependency> EXPECTED_TEMPLATE_DEPENDENCIES = List.of(
            new Dependency("dev.vertique", "vertique-starter-rest", null),
            new Dependency("dev.vertique", "vertique-launcher", null),
            new Dependency("dev.vertique", "vertique-application-test", "test"),
            new Dependency("org.junit.jupiter", "junit-jupiter", "test"),
            new Dependency("io.rest-assured", "rest-assured", "test"));

    /** The exact module set the generated Dagger component names, sorted. */
    private static final List<String> EXPECTED_COMPONENT_MODULES =
            List.of("AppModule.class", "GeneratedJaxRsResourcesModule.class", "RestApplicationModule.class");

    /** The exact {@code id@phase} deployments the generated application module contributes, sorted. */
    private static final List<String> EXPECTED_DEPLOYMENTS = List.of("http@EDGE", "management@INFRA");

    /** The exact {@code -D} property set and values of the frozen §4.6 non-interactive generation command. */
    private static final Map<String, String> EXPECTED_GENERATE_PROPERTIES = Map.of(
            "archetypeGroupId", "dev.vertique",
            "archetypeArtifactId", "vertique-archetype-rest",
            "archetypeVersion", "<vertiqueVersion>",
            "groupId", "<groupId>",
            "artifactId", "<artifactId>",
            "version", "0.1.0-SNAPSHOT",
            "package", "<packageName>",
            "vertiqueVersion", "<vertiqueVersion>",
            "interactiveMode", "false");

    /** The frozen §4.6 generation command prefix, ahead of its {@code -D} flags. */
    private static final String EXPECTED_GENERATE_COMMAND_PREFIX = "mvn -B -ntp archetype:generate";

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

    // --- Tests ---

    @Test
    @DisplayName("uses the REST archetype coordinate, the REST starter, and exactly three component modules")
    void usesRestCoordinateStarterAndExactComponentModules() throws IOException {
        // Given the moved REST archetype module and its templates.
        String archetypePom = read(ARCHETYPE_POM);
        String templatePom = read(TEMPLATE_POM);
        String component = read(TEMPLATE_COMPONENT);

        // When the coordinate, generated dependency contract, and component modules are parsed.
        List<Dependency> dependencies = dependenciesOf(templatePom);
        List<String> componentModules = componentModulesOf(component);

        // Then the archetype publishes the REST coordinate.
        assertTrue(
                archetypePom.contains("<artifactId>vertique-archetype-rest</artifactId>"),
                "archetype coordinate must be vertique-archetype-rest");

        // And the generated project inherits the standalone application parent.
        assertTrue(
                templatePom.contains("<artifactId>vertique-app-parent</artifactId>"),
                "generated project must inherit vertique-app-parent");

        // And its declared dependency contract is exactly the frozen list — group, artifact, scope,
        // and count all pinned, so nothing can be added, dropped, re-grouped, or re-scoped silently.
        assertEquals(EXPECTED_TEMPLATE_DEPENDENCIES, dependencies);

        // And the component names exactly the three frozen modules.
        assertEquals(EXPECTED_COMPONENT_MODULES, componentModules);
    }

    @Test
    @DisplayName("keeps REST security mechanism-neutral: no JWT/JOSE artifact and no validator opt-out")
    void keepsMechanismNeutralSecurity() throws IOException {
        // Given the generated dependency contract and the generated component/module templates.
        String templatePom = read(TEMPLATE_POM);
        String component = read(TEMPLATE_COMPONENT);
        String appModule = read(TEMPLATE_APP_MODULE);

        // When the security surface of every declared dependency and the component is inspected.
        List<Dependency> dependencies = dependenciesOf(templatePom);

        // Then no declared dependency, in any scope, pins a token mechanism.
        dependencies.forEach(dependency -> {
            String coordinate = dependency.groupId() + ":" + dependency.artifactId();
            MECHANISM_TOKENS.forEach(token -> assertFalse(
                    coordinate.toLowerCase(Locale.ROOT).contains(token),
                    "generated dependency must not pin a token mechanism: " + coordinate));
        });
        MECHANISM_TOKENS.forEach(token -> assertFalse(
                component.toLowerCase(Locale.ROOT).contains(token),
                "generated component must not name a token mechanism: " + token));

        // And REST security arrives through the starter rather than through named framework modules.
        assertFalse(component.contains("AuthModule"), "AuthModule must arrive through vertique-starter-rest");
        assertFalse(component.contains("SecurityModule"), "SecurityModule must arrive through vertique-starter-rest");

        // And no null-validator opt-out is generated.
        assertFalse(
                appModule.contains("SecurityPolicyValidator"),
                "generated AppModule must not add a security-policy validator opt-out");
    }

    @Test
    @DisplayName("contributes exactly management at INFRA and http at EDGE")
    void usesExactDeploymentIdsAndPhases() throws IOException {
        // Given the generated application module template.
        String appModule = read(TEMPLATE_APP_MODULE);

        // When its VerticleDeployment contributions and provider methods are parsed.
        List<String> deployments = DEPLOYMENT
                .matcher(appModule)
                .results()
                .map(match -> match.group(1) + "@" + match.group(2))
                .sorted()
                .toList();
        long providerMethods = DEPLOYMENT_PROVIDER.matcher(appModule).results().count();

        // Then it contributes exactly the two frozen REST deployments.
        assertEquals(EXPECTED_DEPLOYMENTS, deployments);

        // And every contributing provider method was parsed — a third contribution, or one built by
        // any means other than VerticleDeployment.of(…), cannot slip past the identifier/phase proof.
        assertEquals(2L, providerMethods, "generated AppModule must declare exactly two deployment providers");
        assertEquals(
                providerMethods,
                deployments.size(),
                "every @IntoSet VerticleDeployment provider must contribute a parsed VerticleDeployment.of(…)");
        assertFalse(
                appModule.contains("new VerticleDeployment("),
                "generated AppModule must build deployments through VerticleDeployment.of(…)");
    }

    @Test
    @DisplayName("documents exactly one non-interactive REST generation command with the frozen §4.6 shape")
    void documentsOnlyTheRestGenerationCommand() throws IOException {
        // Given the REST archetype's own README.
        String readme = read(ARCHETYPE_README);

        // When its documented commands are parsed, one command per continuation-joined line.
        List<String> generationCommands = commandsIn(readme).stream()
                .filter(command -> command.contains("archetype:generate"))
                .toList();

        // Then exactly one generation command is documented.
        assertEquals(1, generationCommands.size(), "README must document exactly one archetype:generate command");
        String command = generationCommands.get(0);

        // And it invokes the frozen §4.6 batch-mode goal.
        assertTrue(
                command.startsWith(EXPECTED_GENERATE_COMMAND_PREFIX),
                () -> "generation command must start with '" + EXPECTED_GENERATE_COMMAND_PREFIX + "': " + command);

        // And it carries exactly the frozen §4.6 properties, each with its frozen value.
        assertEquals(EXPECTED_GENERATE_PROPERTIES, generationPropertiesOf(command));

        // And the JDK and Maven prerequisites are stated.
        assertTrue(readme.contains("JDK 21"), "README must state the JDK 21 prerequisite");
        assertTrue(readme.toLowerCase(Locale.ROOT).contains("maven"), "README must state the Maven prerequisite");
    }

    @Test
    @DisplayName("documents the four supported generated-application commands with matching exec/Jib configuration")
    void documentsSupportedGeneratedApplicationCommands() throws IOException {
        // Given the generated project's README and POM templates.
        String readme = read(TEMPLATE_README);
        String templatePom = read(TEMPLATE_POM);

        // When every documented command is parsed, in document order.
        List<String> documentedCommands = commandsIn(readme);

        // Then exactly those four commands are documented — no more, in that order.
        assertEquals(EXPECTED_GENERATED_APPLICATION_COMMANDS, documentedCommands);

        // And the JDK/Maven prerequisites are stated.
        assertTrue(readme.contains("JDK 21"), "generated README must state the JDK 21 prerequisite");
        assertTrue(
                readme.toLowerCase(Locale.ROOT).contains("maven"),
                "generated README must state the Maven prerequisite");

        // And exec-maven-plugin and jib-maven-plugin structurally launch the same main class.
        assertEquals(EXPECTED_MAIN_CLASS, execMainClassOf(templatePom));
        assertEquals(EXPECTED_MAIN_CLASS, jibContainerMainClassOf(templatePom));

        // And the container image structurally runs as a pinned non-root user rather than as root.
        assertEquals(EXPECTED_CONTAINER_USER, jibContainerUserOf(templatePom));
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
     * Extracts every {@code <dependency>} declared directly under {@code <project>}. XML comments are
     * stripped first, so a commented-out declaration is absent from the result rather than parsed as
     * a live one.
     *
     * @param pom the POM template text
     * @return the declared dependencies in declaration order
     */
    private static List<Dependency> dependenciesOf(String pom) {
        Matcher block = PROJECT_DEPENDENCIES.matcher(stripXmlComments(pom));
        assertTrue(block.find(), "template POM must declare a project-level <dependencies> block");
        return DEPENDENCY
                .matcher(block.group(1))
                .results()
                .map(match -> new Dependency(
                        firstGroup(GROUP_ID, match.group(1)),
                        firstGroup(ARTIFACT_ID, match.group(1)),
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
     * Extracts the module class literals named by the generated component's {@code @Component}. Every
     * non-empty comma-separated token must be a class literal — an entry the parse cannot classify
     * fails the proof rather than being filtered away.
     *
     * @param component the component template text
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
     * One parsed Maven dependency declaration.
     *
     * @param groupId the declared group identifier
     * @param artifactId the declared artifact identifier
     * @param scope the declared scope, or {@code null} when the implicit compile scope applies
     */
    private record Dependency(String groupId, String artifactId, String scope) {}
}
