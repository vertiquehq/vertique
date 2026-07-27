// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype.services;

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
 * Source-template contract proof for the headless services archetype.
 *
 * <p>Verifies that the archetype coordinate, the generated project's dependency contract, the
 * generated Dagger component's module set and service-client provision, and the generated
 * deployment identifiers/phases match the frozen services contracts. The templates are Velocity
 * sources rather than compilable Java, so every assertion is made against the template text.
 *
 * <p>Each parse is <em>exhaustive</em> rather than filtering: the full parsed dependency list,
 * component module set, and deployment set are compared against frozen expectations, and any token
 * the parse cannot classify fails the proof instead of being dropped. A declaration that a filtering
 * parse would quietly skip — a commented-out dependency, an extra scope, a deployment built by other
 * means — is therefore caught rather than absorbed.
 *
 * <p>This class is deliberately kept textually parallel to the REST archetype's contract test rather
 * than sharing a common seam: the two proofs freeze different contracts, and extracting the shared
 * machinery is a separate, later concern.
 */
class ServicesArchetypeContractTest {

    // --- Template locations (relative to the archetype module basedir) ---

    private static final Path ARCHETYPE_POM = Path.of("pom.xml");
    private static final Path ARCHETYPE_RESOURCES = Path.of("src", "main", "resources", "archetype-resources");
    private static final Path TEMPLATE_POM = ARCHETYPE_RESOURCES.resolve("pom.xml");
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
     * literal containing Java comment delimiters would be mis-stripped. The services templates carry
     * only simple literals ({@code "management"}, {@code "sample"}, {@code "greeting"},
     * {@code "greet"}, {@code "Hello, "}), none of which contain a delimiter, so the simple form is
     * exact here. A template that gains such a literal must move to a literal-aware scan.
     */
    private static final Pattern JAVA_COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n\\r]*", Pattern.DOTALL);

    /** Matches the {@code <dependencies>} block that is a direct child of {@code <project>}. */
    private static final Pattern PROJECT_DEPENDENCIES =
            Pattern.compile("\\R {4}<dependencies>\\R(.*?)\\R {4}</dependencies>", Pattern.DOTALL);

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern SCOPE = Pattern.compile("<scope>([^<]+)</scope>");

    /** Matches the {@code modules = { … }} member of the generated component's {@code @Component}. */
    private static final Pattern COMPONENT_MODULES = Pattern.compile("modules\\s*=\\s*\\{(.*?)}", Pattern.DOTALL);

    /**
     * Matches the generated component's abstract {@code ServiceClientFactory} provision method. The
     * whole declaration is pinned — return type, name, empty parameter list, and abstract body — so
     * the proof cannot be satisfied by an unrelated mention of the type.
     */
    private static final Pattern SERVICE_CLIENT_FACTORY_PROVISION =
            Pattern.compile("\\bServiceClientFactory\\s+serviceClientFactory\\s*\\(\\s*\\)\\s*;");

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

    // --- Frozen contracts ---

    /**
     * The complete dependency contract of the generated project, in declaration order. Group,
     * artifact, and scope are each load-bearing, and the list is exhaustive: any added, removed,
     * re-grouped, or re-scoped declaration is a consumer-visible change to what the archetype
     * generates. REST Assured is deliberately absent — the generated application serves no HTTP.
     */
    private static final List<Dependency> EXPECTED_TEMPLATE_DEPENDENCIES = List.of(
            new Dependency("dev.vertique", "vertique-starter-services", null),
            new Dependency("dev.vertique", "vertique-launcher", null),
            new Dependency("dev.vertique", "vertique-application-test", "test"),
            new Dependency("org.junit.jupiter", "junit-jupiter", "test"));

    /**
     * The exact module set the generated Dagger component names, sorted. {@code
     * GeneratedServicesModule} is listed explicitly rather than reached transitively: if the services
     * annotation processor is not on the processor path, naming it here fails compilation instead of
     * booting with a silently empty service registry.
     */
    private static final List<String> EXPECTED_COMPONENT_MODULES =
            List.of("AppModule.class", "GeneratedServicesModule.class", "ServicesApplicationModule.class");

    /** The exact {@code id@phase} deployments the generated application module contributes, sorted. */
    private static final List<String> EXPECTED_DEPLOYMENTS = List.of("management@INFRA");

    /** The annotations every deployment provider must carry; membership is order-insensitive. */
    private static final Set<String> REQUIRED_PROVIDER_ANNOTATIONS = Set.of("@Provides", "@IntoSet");

    /**
     * Artifact identifiers the generated project must never declare, matched against every {@code
     * <artifactId>} in the template POM rather than only its project dependencies. A REST runtime or
     * REST Assured arriving through a plugin, a profile, or a managed block is as much a violation of
     * the headless contract as a declared dependency would be.
     */
    private static final Pattern FORBIDDEN_ARTIFACT = Pattern.compile("^(vertique-rest-.*|rest-assured)$");

    /**
     * Tokens that would indicate a REST/JAX-RS surface in a generated Java source. {@code JaxRs}
     * catches the generated JAX-RS resources module, {@code jakarta.ws.rs} and {@code dev.vertique
     * .rest} catch the REST imports, and the remaining tokens catch the JAX-RS routing annotations
     * themselves.
     */
    private static final List<String> FORBIDDEN_SOURCE_TOKENS = List.of(
            "jakarta.ws.rs",
            "dev.vertique.rest",
            "JaxRs",
            "HttpVerticle",
            "@Path",
            "@GET",
            "@POST",
            "@PUT",
            "@DELETE",
            "@Produces",
            "@Consumes");

    // --- Tests ---

    @Test
    @DisplayName("uses the services archetype coordinate, the services starter, and exactly three component modules")
    void usesServicesStarterAndExactComponentModules() throws IOException {
        // Given the services archetype module and its templates.
        String archetypePom = read(ARCHETYPE_POM);
        String templatePom = read(TEMPLATE_POM);
        String component = stripJavaComments(read(TEMPLATE_COMPONENT));

        // When the coordinate, generated dependency contract, and component modules are parsed.
        List<Dependency> dependencies = dependenciesOf(templatePom);
        List<String> componentModules = componentModulesOf(component);

        // Then the archetype publishes the services coordinate.
        assertTrue(
                archetypePom.contains("<artifactId>vertique-archetype-services</artifactId>"),
                "archetype coordinate must be vertique-archetype-services");

        // And the generated project inherits the standalone application parent.
        assertTrue(
                templatePom.contains("<artifactId>vertique-app-parent</artifactId>"),
                "generated project must inherit vertique-app-parent");

        // And its declared dependency contract is exactly the frozen list — group, artifact, scope,
        // and count all pinned, so nothing can be added, dropped, re-grouped, or re-scoped silently.
        assertEquals(EXPECTED_TEMPLATE_DEPENDENCIES, dependencies);

        // And the component names exactly the three frozen modules, including the explicit
        // GeneratedServicesModule.
        assertEquals(EXPECTED_COMPONENT_MODULES, componentModules);

        // And it exposes the typed service-client factory the generated integration test dispatches
        // through.
        assertTrue(
                SERVICE_CLIENT_FACTORY_PROVISION.matcher(component).find(),
                "generated component must declare ServiceClientFactory serviceClientFactory();");
    }

    /**
     * Proves the generated services application carries no REST surface and contributes exactly the
     * one frozen deployment.
     *
     * <p><strong>Deliberate bound.</strong> The deployment half is a structural <em>text</em> proof,
     * not Java parsing. Its discovery key is the declared return type {@code VerticleDeployment}: any
     * method declaring that return type is treated as a contribution and must satisfy the full
     * contract, so a non-canonical addition is detected rather than ignored. A method that hides its
     * return type behind a type alias, {@code var}, or a generic factory would evade discovery. That
     * residual is accepted knowingly — closing it would require real Java parsing, which is
     * disproportionate to the risk of a template this small and frozen.
     *
     * @throws IOException when a template cannot be read
     */
    @Test
    @DisplayName("omits every REST dependency, source, and the HTTP deployment entry")
    void omitsRestAndHttpDeployment() throws IOException {
        // Given the generated project's POM and its complete generated Java source set, comments
        // stripped so only live code is parsed.
        String templatePom = stripXmlComments(read(TEMPLATE_POM));
        String appModule = stripJavaComments(read(TEMPLATE_APP_MODULE));

        // Then no artifact anywhere in the POM — dependency, plugin, or managed entry — pins a REST
        // runtime or REST Assured.
        ARTIFACT_ID
                .matcher(templatePom)
                .results()
                .map(match -> match.group(1))
                .forEach(artifactId -> assertFalse(
                        FORBIDDEN_ARTIFACT.matcher(artifactId).matches(),
                        () -> "generated project must declare no REST artifact, found: " + artifactId));

        // And no generated source imports, annotates, or otherwise names a JAX-RS/REST surface.
        for (Path source : templateSources()) {
            String text = stripJavaComments(read(source));
            FORBIDDEN_SOURCE_TOKENS.forEach(token -> assertFalse(
                    text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT)),
                    () -> "generated source " + source + " must not name a REST surface: " + token));
        }

        // When every method declaring a VerticleDeployment return type is isolated with its body.
        List<DeploymentProvider> providers = deploymentProvidersOf(appModule);

        // Then exactly the one frozen contribution exists.
        assertEquals(1, providers.size(), "generated AppModule must declare exactly one deployment provider");

        // And it is a static multibinding contribution — annotation order is irrelevant, presence is
        // not.
        providers.forEach(provider -> {
            assertTrue(
                    provider.annotations().containsAll(REQUIRED_PROVIDER_ANNOTATIONS),
                    () -> "deployment provider " + provider.name() + " must carry " + REQUIRED_PROVIDER_ANNOTATIONS
                            + ", found " + provider.annotations());
            assertTrue(provider.isStatic(), () -> "deployment provider " + provider.name() + " must be static");
        });

        // And its entire body is a single recognized construction — not merely a body that contains
        // one somewhere, so neither a delegating return beside a stray construction nor an extra
        // return path can be credited. The frozen id/phase set is derived from exactly that
        // whole-body construction, and it holds no http entry.
        List<String> deployments = providers.stream()
                .map(ServicesArchetypeContractTest::returnedDeploymentOf)
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
     * Lists every Java template the archetype generates, so the REST-absence scan covers the whole
     * generated source set rather than a hand-picked subset. An added template is therefore in scope
     * automatically.
     *
     * @return every {@code .java} file beneath the archetype resources, in stable path order
     * @throws IOException when the template tree cannot be walked
     */
    private static List<Path> templateSources() throws IOException {
        try (Stream<Path> tree = Files.walk(ARCHETYPE_RESOURCES)) {
            List<Path> sources = tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            assertFalse(sources.isEmpty(), "archetype resources must contain generated Java templates");
            return sources;
        }
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
     * One parsed Maven dependency declaration.
     *
     * @param groupId the declared group identifier
     * @param artifactId the declared artifact identifier
     * @param scope the declared scope, or {@code null} when the implicit compile scope applies
     */
    private record Dependency(String groupId, String artifactId, String scope) {}

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
