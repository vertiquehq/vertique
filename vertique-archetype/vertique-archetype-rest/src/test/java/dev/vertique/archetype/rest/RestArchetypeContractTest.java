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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-template contract proof for the REST archetype.
 *
 * <p>Verifies that the archetype coordinate, the generated project's dependency contract, the
 * archetype module's own staging set, the generated Dagger component's module set, and the
 * generated deployment identifiers/phases match the frozen REST contracts. The templates are Velocity sources rather than compilable Java, so
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

    /**
     * Matches a Java block or line comment. Stripped before any structural parse of a Java template
     * for the same reason {@link #XML_COMMENT} is: a commented-out declaration is not a live one.
     *
     * <p>This is a deliberately simple lexer — it does not track string or character literals, so a
     * literal containing Java comment delimiters would be mis-stripped. The REST templates carry only
     * simple literals ({@code "management"}, {@code "http"}), none of which contain a delimiter, so
     * the simple form is exact here. A template that gains such a literal must move to a
     * literal-aware scan.
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

    /** Matches the {@code <environmentVariables>...</environmentVariables>} block of a plugin configuration. */
    private static final Pattern ENVIRONMENT_VARIABLES =
            Pattern.compile("<environmentVariables>(.*?)</environmentVariables>", Pattern.DOTALL);

    /**
     * Matches the {@code VERTX_CONFIG_LOCATIONS} entry of an environment block in either the
     * self-closing or the open/close form, capturing the declared value from the latter. Written to
     * match <em>any</em> declared value rather than only the blank one, so a neutralization that
     * regressed into a real redirect is captured and then fails the value assertion instead of
     * vanishing from the proof.
     */
    private static final Pattern CONFIG_LOCATIONS_ENTRY = Pattern.compile(
            "<VERTX_CONFIG_LOCATIONS\\s*/>|<VERTX_CONFIG_LOCATIONS>(.*?)</VERTX_CONFIG_LOCATIONS>", Pattern.DOTALL);

    // --- Frozen contracts ---

    /**
     * The complete dependency contract of the generated project, in declaration order. Group,
     * artifact, type, and scope are each load-bearing, and the list is exhaustive: any added,
     * removed, re-grouped, re-typed, or re-scoped declaration is a consumer-visible change to what
     * the archetype generates.
     */
    private static final List<Dependency> EXPECTED_TEMPLATE_DEPENDENCIES = List.of(
            new Dependency("dev.vertique", "vertique-starter-rest", null, null),
            new Dependency("dev.vertique", "vertique-launcher", null, null),
            new Dependency("dev.vertique", "vertique-application-test", null, "test"),
            new Dependency("org.junit.jupiter", "junit-jupiter", null, "test"),
            new Dependency("io.rest-assured", "rest-assured", null, "test"));

    /**
     * The complete dependency contract of the archetype module itself, in declaration order: one
     * Surefire harness edge plus the frozen §4.5 staging set that populates the isolated
     * integration-test repository. The list is exhaustive so a staging edge cannot be added or
     * dropped silently — a missing edge would let the nested build fall back to a coordinate this
     * reactor did not produce.
     */
    private static final List<Dependency> EXPECTED_ARCHETYPE_DEPENDENCIES = List.of(
            new Dependency("org.junit.jupiter", "junit-jupiter", null, "test"),
            new Dependency("dev.vertique", "vertique-app-parent", "pom", "test"),
            new Dependency("dev.vertique", "vertique-bom", "pom", "test"),
            new Dependency("dev.vertique", "vertique-codegen-all", null, "test"),
            new Dependency("dev.vertique", "vertique-starter-rest", null, "test"),
            new Dependency("dev.vertique", "vertique-launcher", null, "test"),
            new Dependency("dev.vertique", "vertique-application-test", null, "test"));

    /** The exact module set the generated Dagger component names, sorted. */
    private static final List<String> EXPECTED_COMPONENT_MODULES =
            List.of("AppModule.class", "GeneratedJaxRsResourcesModule.class", "RestApplicationModule.class");

    /** The exact {@code id@phase} deployments the generated application module contributes, sorted. */
    private static final List<String> EXPECTED_DEPLOYMENTS = List.of("http@EDGE", "management@INFRA");

    /** The annotations every deployment provider must carry; membership is order-insensitive. */
    private static final Set<String> REQUIRED_PROVIDER_ANNOTATIONS = Set.of("@Provides", "@IntoSet");

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

    // --- Tests ---

    @Test
    @DisplayName("uses the REST archetype coordinate, the REST starter, and exactly three component modules")
    void usesRestCoordinateStarterAndExactComponentModules() throws IOException {
        // Given the moved REST archetype module and its templates.
        String archetypePom = read(ARCHETYPE_POM);
        String templatePom = read(TEMPLATE_POM);
        String component = read(TEMPLATE_COMPONENT);

        // When the coordinate, generated dependency contract, staging set, and component modules are
        // parsed.
        List<Dependency> dependencies = dependenciesOf(templatePom);
        List<Dependency> stagingDependencies = dependenciesOf(archetypePom);
        List<String> componentModules = componentModulesOf(component);

        // Then the archetype publishes the REST coordinate.
        assertTrue(
                archetypePom.contains("<artifactId>vertique-archetype-rest</artifactId>"),
                "archetype coordinate must be vertique-archetype-rest");

        // And the generated project inherits the standalone application parent.
        assertTrue(
                templatePom.contains("<artifactId>vertique-app-parent</artifactId>"),
                "generated project must inherit vertique-app-parent");

        // And its declared dependency contract is exactly the frozen list — group, artifact, type,
        // scope, and count all pinned, so nothing can be added, dropped, re-grouped, re-typed, or
        // re-scoped silently.
        assertEquals(EXPECTED_TEMPLATE_DEPENDENCIES, dependencies);

        // And the archetype module declares exactly the frozen staging set, so the generated
        // project's nested build resolves every internal coordinate from the isolated repository.
        assertEquals(EXPECTED_ARCHETYPE_DEPENDENCIES, stagingDependencies);

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

        // Then exactly the two frozen contributions exist.
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
                .map(RestArchetypeContractTest::returnedDeploymentOf)
                .sorted()
                .toList();
        assertEquals(EXPECTED_DEPLOYMENTS, deployments);

        // And no deployment is built by direct construction anywhere in the module.
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

        // And its leading tokens are exactly the frozen batch-mode goal invocation.
        List<String> tokens = List.of(command.split("\\s+"));
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

        // And those flags are exactly the frozen §4.6 properties, each with its frozen value.
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

    @Test
    @DisplayName("neutralizes an ambient VERTX_CONFIG_LOCATIONS for the generated integration-test run")
    void neutralizesAmbientConfigLocations() throws IOException {
        // Given the generated project's POM template.
        String templatePom = read(TEMPLATE_POM);

        // When the value the failsafe plugin pins for VERTX_CONFIG_LOCATIONS is read.
        String configLocations = failsafeConfigLocationsOf(templatePom);

        // Then it is blank. ConfigBootstrap.resolveConfigDirs treats a blank value exactly as an
        // unset one and falls back to the default config/ directory, so the generated tests resolve
        // this project's own configuration whatever the surrounding environment sets. A non-blank
        // value here would redirect the loader and make the working-directory proof either spuriously
        // fail or vacuously pass against a foreign directory.
        assertTrue(
                configLocations.isBlank(),
                () -> "failsafe must pin VERTX_CONFIG_LOCATIONS blank so the generated tests resolve this project's"
                        + " own config/ directory, found: " + configLocations);
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
     * fails the proof rather than being filtered away. Comments are stripped first, so a
     * commented-out annotation cannot shadow the live one.
     *
     * @param component the component template text
     * @return the module class literals, sorted for order-independent comparison
     */
    private static List<String> componentModulesOf(String component) {
        Matcher modules = COMPONENT_MODULES.matcher(stripJavaComments(component));
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
        assertTrue(open >= 0, "deployment provider must declare a method body");
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open + 1, index);
            }
        }
        throw new AssertionError("deployment provider method body is not brace-balanced");
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
     * Extracts the {@code VERTX_CONFIG_LOCATIONS} value the failsafe plugin pins for the generated
     * integration-test JVM.
     *
     * @param pom the POM template text
     * @return the declared value — the empty string for the blank, neutralizing form
     */
    private static String failsafeConfigLocationsOf(String pom) {
        Matcher environment = ENVIRONMENT_VARIABLES.matcher(pluginBlockFor(pom, "maven-failsafe-plugin"));
        assertTrue(
                environment.find(),
                "maven-failsafe-plugin must declare an <environmentVariables> block for the generated tests");
        Matcher entry = CONFIG_LOCATIONS_ENTRY.matcher(environment.group(1));
        assertTrue(entry.find(), "failsafe <environmentVariables> must declare VERTX_CONFIG_LOCATIONS");
        String declared = entry.group(1);
        return declared == null ? "" : declared;
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
