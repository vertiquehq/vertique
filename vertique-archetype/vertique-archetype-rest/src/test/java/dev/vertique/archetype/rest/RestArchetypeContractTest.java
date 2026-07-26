// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /** Tokens that would indicate a concrete JWT/JOSE authentication mechanism. */
    private static final List<String> MECHANISM_TOKENS = List.of("jwt", "jose");

    /** Matches a fenced {@code ```bash ... ```} code block within a markdown document. */
    private static final Pattern FENCED_BASH_BLOCK = Pattern.compile("```bash\\R(.*?)```", Pattern.DOTALL);

    /** Matches one {@code -DpropertyName=value} generation-command flag; the value may be empty. */
    private static final Pattern GENERATE_PROPERTY = Pattern.compile("-D(\\w+)=(\\S*)");

    /** Matches a {@code <plugin>...</plugin>} declaration. */
    private static final Pattern PLUGIN_BLOCK = Pattern.compile("<plugin>(.*?)</plugin>", Pattern.DOTALL);

    /** Matches the {@code <container>...</container>} block within a jib-maven-plugin configuration. */
    private static final Pattern CONTAINER_BLOCK = Pattern.compile("<container>(.*?)</container>", Pattern.DOTALL);

    /** Matches a {@code <mainClass>value</mainClass>} element. */
    private static final Pattern MAIN_CLASS = Pattern.compile("<mainClass>([^<]+)</mainClass>");

    /** The exact {@code -D} property set the frozen §4.6 non-interactive generation command must carry. */
    private static final Set<String> EXPECTED_GENERATE_PROPERTIES = Set.of(
            "archetypeGroupId",
            "archetypeArtifactId",
            "archetypeVersion",
            "groupId",
            "artifactId",
            "version",
            "package",
            "vertiqueVersion",
            "interactiveMode");

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
        List<String> production = artifactIdsInScope(dependencies, null);
        List<String> testScoped = artifactIdsInScope(dependencies, "test");
        List<String> componentModules = componentModulesOf(component);

        // Then the archetype publishes the REST coordinate.
        assertTrue(
                archetypePom.contains("<artifactId>vertique-archetype-rest</artifactId>"),
                "archetype coordinate must be vertique-archetype-rest");

        // And the generated project inherits the standalone application parent.
        assertTrue(
                templatePom.contains("<artifactId>vertique-app-parent</artifactId>"),
                "generated project must inherit vertique-app-parent");

        // And its production dependencies are exactly the REST starter plus the launcher.
        assertEquals(List.of("vertique-launcher", "vertique-starter-rest"), production);

        // And its test libraries stay explicit.
        assertEquals(List.of("junit-jupiter", "rest-assured", "vertique-application-test"), testScoped);

        // And the component names exactly the three frozen modules.
        assertEquals(
                List.of("AppModule.class", "GeneratedJaxRsResourcesModule.class", "RestApplicationModule.class"),
                componentModules);
    }

    @Test
    @DisplayName("keeps REST security mechanism-neutral: no JWT/JOSE artifact and no validator opt-out")
    void keepsMechanismNeutralSecurity() throws IOException {
        // Given the generated dependency contract and the generated component/module templates.
        String templatePom = read(TEMPLATE_POM);
        String component = read(TEMPLATE_COMPONENT);
        String appModule = read(TEMPLATE_APP_MODULE);

        // When the security surface of the production dependencies and the component is inspected.
        List<String> production = artifactIdsInScope(dependenciesOf(templatePom), null);

        // Then no concrete authentication mechanism is pinned by the generated project.
        production.forEach(artifactId -> MECHANISM_TOKENS.forEach(token -> assertFalse(
                artifactId.toLowerCase(Locale.ROOT).contains(token),
                "generated production dependency must not pin an authentication mechanism: " + artifactId)));
        MECHANISM_TOKENS.forEach(token -> assertFalse(
                component.toLowerCase(Locale.ROOT).contains(token),
                "generated component must not name an authentication mechanism: " + token));

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

        // When its VerticleDeployment contributions are parsed.
        List<String> deployments = DEPLOYMENT
                .matcher(appModule)
                .results()
                .map(match -> match.group(1) + "@" + match.group(2))
                .sorted()
                .toList();

        // Then it contributes exactly the two frozen REST deployments.
        assertEquals(List.of("http@EDGE", "management@INFRA"), deployments);
    }

    @Test
    @DisplayName("documents exactly one non-interactive REST generation command with the frozen §4.6 shape")
    void documentsOnlyTheRestGenerationCommand() throws IOException {
        // Given the REST archetype's own README.
        String readme = read(ARCHETYPE_README);

        // When its fenced generation command blocks are parsed.
        List<String> generationCommands = fencedBashBlocksContaining(readme, "archetype:generate");

        // Then exactly one generation command is documented.
        assertEquals(1, generationCommands.size(), "README must document exactly one archetype:generate command");
        String command = generationCommands.get(0);

        // And it carries exactly the frozen §4.6 property set.
        assertEquals(
                EXPECTED_GENERATE_PROPERTIES, generationPropertiesOf(command).keySet());

        // And it names the REST archetype coordinate and runs non-interactively.
        assertEquals("dev.vertique", propertyValue(command, "archetypeGroupId"));
        assertEquals("vertique-archetype-rest", propertyValue(command, "archetypeArtifactId"));
        assertEquals("false", propertyValue(command, "interactiveMode"));

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

        // When the supported commands are parsed.
        List<String> requiredCommands =
                List.of("mvn -ntp exec:java", "mvn -ntp verify", "mvn -ntp package", "mvn -ntp jib:dockerBuild");

        // Then exactly those four commands are documented, alongside the JDK/Maven prerequisites.
        requiredCommands.forEach(
                command -> assertTrue(readme.contains(command), () -> "generated README must document: " + command));
        assertTrue(readme.contains("JDK 21"), "generated README must state the JDK 21 prerequisite");
        assertTrue(
                readme.toLowerCase(Locale.ROOT).contains("maven"),
                "generated README must state the Maven prerequisite");

        // And exec-maven-plugin and jib-maven-plugin structurally launch the same main class.
        assertEquals("dev.vertique.launcher.VertiqueApplication", execMainClassOf(templatePom));
        assertEquals("dev.vertique.launcher.VertiqueApplication", jibContainerMainClassOf(templatePom));
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
     * Extracts the {@code <dependency>} entries declared directly under {@code <project>}.
     *
     * @param pom the POM template text
     * @return the declared dependencies in declaration order
     */
    private static List<Dependency> dependenciesOf(String pom) {
        Matcher block = PROJECT_DEPENDENCIES.matcher(pom);
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
     * Selects the sorted artifact identifiers declared in one Maven scope.
     *
     * @param dependencies the parsed dependencies
     * @param scope the scope to select, or {@code null} for the implicit compile scope
     * @return the matching artifact identifiers, sorted for order-independent comparison
     */
    private static List<String> artifactIdsInScope(List<Dependency> dependencies, String scope) {
        return dependencies.stream()
                .filter(dependency -> java.util.Objects.equals(dependency.scope(), scope))
                .map(Dependency::artifactId)
                .sorted()
                .toList();
    }

    /**
     * Extracts the module class literals named by the generated component's {@code @Component}.
     *
     * @param component the component template text
     * @return the module class literals, sorted for order-independent comparison
     */
    private static List<String> componentModulesOf(String component) {
        Matcher modules = COMPONENT_MODULES.matcher(component);
        assertTrue(modules.find(), "generated component must declare @Component(modules = { … })");
        return modules.group(1)
                .lines()
                .map(String::trim)
                .map(line -> line.endsWith(",") ? line.substring(0, line.length() - 1) : line)
                .filter(line -> line.endsWith(".class"))
                .sorted()
                .toList();
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
     * Extracts the bodies of fenced {@code ```bash``` } blocks containing a given substring.
     *
     * @param markdown the markdown document text
     * @param needle the substring a matching block must contain
     * @return the matching block bodies, in document order
     */
    private static List<String> fencedBashBlocksContaining(String markdown, String needle) {
        return FENCED_BASH_BLOCK
                .matcher(markdown)
                .results()
                .map(match -> match.group(1))
                .filter(block -> block.contains(needle))
                .toList();
    }

    /**
     * Parses every {@code -D} property set by a generation command; the first occurrence of a
     * repeated key wins, matching single-{@code find()} lookup semantics.
     *
     * @param command the command block text
     * @return property names mapped to their assigned (possibly empty) values
     */
    private static Map<String, String> generationPropertiesOf(String command) {
        Map<String, String> properties = new LinkedHashMap<>();
        GENERATE_PROPERTY
                .matcher(command)
                .results()
                .forEach(match -> properties.putIfAbsent(match.group(1), match.group(2)));
        return properties;
    }

    /**
     * Extracts the value assigned to one {@code -D} property within a generation command.
     *
     * @param command the command block text
     * @param key the property name
     * @return the assigned value
     */
    private static String propertyValue(String command, String key) {
        Map<String, String> properties = generationPropertiesOf(command);
        assertTrue(properties.containsKey(key), () -> "generation command must set -D" + key);
        return properties.get(key);
    }

    /**
     * Locates the {@code <plugin>} declaration for a given artifact identifier.
     *
     * @param pom the POM template text
     * @param artifactId the plugin's artifact identifier
     * @return the plugin's declaration body
     */
    private static String pluginBlockFor(String pom, String artifactId) {
        Matcher plugins = PLUGIN_BLOCK.matcher(pom);
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
     * Extracts the {@code jib-maven-plugin} container main class configured in the template POM.
     *
     * @param pom the POM template text
     * @return the configured container main class
     */
    private static String jibContainerMainClassOf(String pom) {
        String jibBlock = pluginBlockFor(pom, "jib-maven-plugin");
        Matcher container = CONTAINER_BLOCK.matcher(jibBlock);
        assertTrue(container.find(), "jib-maven-plugin must declare a <container> configuration");
        return firstGroup(MAIN_CLASS, container.group(1));
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
