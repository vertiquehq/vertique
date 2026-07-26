// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
    private static final Path ARCHETYPE_RESOURCES = Path.of("src", "main", "resources", "archetype-resources");
    private static final Path TEMPLATE_POM = ARCHETYPE_RESOURCES.resolve("pom.xml");
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
                .filter(line -> line.endsWith(".class,") || line.endsWith(".class"))
                .map(line -> line.endsWith(",") ? line.substring(0, line.length() - 1) : line)
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
     * One parsed Maven dependency declaration.
     *
     * @param groupId the declared group identifier
     * @param artifactId the declared artifact identifier
     * @param scope the declared scope, or {@code null} when the implicit compile scope applies
     */
    private record Dependency(String groupId, String artifactId, String scope) {}
}
