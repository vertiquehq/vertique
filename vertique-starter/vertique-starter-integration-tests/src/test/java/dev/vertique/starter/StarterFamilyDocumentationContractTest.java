// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.starter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Navigation contract test over the starter family's root documentation.
 *
 * <p>The starter family publishes exactly four consumable starter JARs. This test proves that the
 * root {@code README.md} and {@code docs/architecture.md} both surface exactly those four
 * artifacts, that a reader can actually navigate from {@code README.md} to each starter's canonical
 * module reference (directly, or by following the link to {@code docs/modules.md}), and that the
 * family's internal reactor infrastructure — the {@code vertique-starter} POM aggregator and the
 * {@code vertique-starter-integration-tests} harness — is explicitly called out as non-consumable
 * wherever it is mentioned, and stays out of the BOM and the module index.
 */
class StarterFamilyDocumentationContractTest {

    /** Artifact IDs of the starter family's published, BOM-managed consumable starters. */
    private static final List<String> CONSUMABLE_STARTERS = List.of(
            "vertique-starter-core",
            "vertique-starter-rest",
            "vertique-starter-services",
            "vertique-starter-postgresql");

    /** Artifact IDs of the starter family's internal, non-consumable reactor infrastructure. */
    private static final List<String> INFRASTRUCTURE_STARTERS =
            List.of("vertique-starter", "vertique-starter-integration-tests");

    /** Case-insensitive markers that must accompany a mention of infrastructure-only reactor children. */
    private static final List<String> NON_CONSUMABLE_MARKERS = List.of("internal", "non-consumable");

    /** Matches a complete {@code vertique-starter[-<suffix>]*} artifact-ID token in prose. */
    private static final Pattern STARTER_TOKEN_PATTERN = Pattern.compile("\\bvertique-starter(?:-[a-z]+)*\\b");

    /** Matches a Markdown inline link's target, e.g. {@code [text](target)}. */
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[[^\\]]*]\\(([^)]+)\\)");

    @Test
    void listsExactlyConsumableStarters() throws IOException {
        Path reactorRoot = ReactorRootLocator.locate();
        Path readmePath = reactorRoot.resolve("README.md");
        Path architecturePath = reactorRoot.resolve("docs/architecture.md");
        Path modulesIndexPath = reactorRoot.resolve("docs/modules.md");

        Set<String> allowedTokens = new HashSet<>(CONSUMABLE_STARTERS);
        allowedTokens.addAll(INFRASTRUCTURE_STARTERS);

        String readmeContent = Files.readString(readmePath, StandardCharsets.UTF_8);
        String architectureContent = Files.readString(architecturePath, StandardCharsets.UTF_8);

        for (Map.Entry<Path, String> document :
                List.of(Map.entry(readmePath, readmeContent), Map.entry(architecturePath, architectureContent))) {
            Set<String> tokens = starterTokens(document.getValue());
            assertTrue(
                    tokens.containsAll(CONSUMABLE_STARTERS),
                    document.getKey() + " must mention every consumable starter, found: " + tokens);
            assertTrue(
                    allowedTokens.containsAll(tokens),
                    document.getKey() + " mentions unexpected starter-family artifact(s), presented as consumable: "
                            + tokens);
        }

        List<String> readmeLinks = markdownLinkTargets(readmeContent);
        boolean readmeLinksToModulesIndex = linksResolveTo(reactorRoot, readmeLinks, modulesIndexPath.normalize());
        List<String> modulesIndexLinks = readmeLinksToModulesIndex
                ? markdownLinkTargets(Files.readString(modulesIndexPath, StandardCharsets.UTF_8))
                : List.of();

        for (String starter : CONSUMABLE_STARTERS) {
            Path canonicalDoc = canonicalModuleDoc(reactorRoot, starter);
            assertTrue(Files.isRegularFile(canonicalDoc), "expected canonical module doc missing: " + canonicalDoc);

            boolean linksDirectly = linksResolveTo(reactorRoot, readmeLinks, canonicalDoc);
            boolean linksViaModulesIndex = readmeLinksToModulesIndex
                    && linksResolveTo(reactorRoot.resolve("docs"), modulesIndexLinks, canonicalDoc);

            assertTrue(
                    linksDirectly || linksViaModulesIndex,
                    "README.md must navigate to " + starter + "'s canonical module doc, directly or via "
                            + "docs/modules.md; README links=" + readmeLinks);
        }
    }

    @Test
    void excludesInfrastructureChildren() throws IOException {
        Path reactorRoot = ReactorRootLocator.locate();
        assertInfrastructureMarkedNonConsumable(reactorRoot.resolve("README.md"));
        assertInfrastructureMarkedNonConsumable(reactorRoot.resolve("docs/architecture.md"));

        String bomPom = Files.readString(reactorRoot.resolve("vertique-bom/pom.xml"), StandardCharsets.UTF_8);
        for (String infraArtifact : INFRASTRUCTURE_STARTERS) {
            assertFalse(
                    bomPom.contains("<artifactId>" + infraArtifact + "</artifactId>"),
                    "vertique-bom/pom.xml must not manage " + infraArtifact);
        }

        String modulesIndex = Files.readString(reactorRoot.resolve("docs/modules.md"), StandardCharsets.UTF_8);
        for (String infraArtifact : INFRASTRUCTURE_STARTERS) {
            assertFalse(
                    modulesIndex.contains("`" + infraArtifact + "`"),
                    "docs/modules.md must not carry a row for " + infraArtifact);
        }
    }

    // --- Discovery ---

    // --- Markdown navigation ---

    /**
     * Resolves a starter artifact ID to its canonical module-reference path.
     *
     * @param reactorRoot the reactor root directory; must not be {@code null}
     * @param starterArtifactId the consumable starter's artifact ID; must not be {@code null}
     * @return the normalized path to the starter's {@code module.md}
     */
    private static Path canonicalModuleDoc(Path reactorRoot, String starterArtifactId) {
        return reactorRoot
                .resolve("vertique-starter")
                .resolve(starterArtifactId)
                .resolve("src/main/resources/META-INF/vertique/module.md")
                .normalize();
    }

    /**
     * Extracts every Markdown inline link target from the given document content.
     *
     * @param content the document content to scan; must not be {@code null}
     * @return the link targets in document order
     */
    private static List<String> markdownLinkTargets(String content) {
        List<String> targets = new ArrayList<>();
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            targets.add(matcher.group(1).trim());
        }
        return targets;
    }

    /**
     * Checks whether any link target, resolved against {@code baseDirectory}, points at
     * {@code expectedTarget}.
     *
     * @param baseDirectory the directory the link targets are relative to; must not be {@code null}
     * @param linkTargets the candidate link targets; must not be {@code null}
     * @param expectedTarget the normalized path a link must resolve to; must not be {@code null}
     * @return {@code true} when a link resolves exactly to {@code expectedTarget}
     */
    private static boolean linksResolveTo(Path baseDirectory, List<String> linkTargets, Path expectedTarget) {
        for (String target : linkTargets) {
            if (target.startsWith("http://") || target.startsWith("https://")) {
                continue;
            }
            Path resolved = baseDirectory.resolve(target).normalize();
            if (resolved.equals(expectedTarget)) {
                return true;
            }
        }
        return false;
    }

    // --- Token & marker checks ---

    /**
     * Collects every distinct {@code vertique-starter*} artifact-ID token mentioned in the content.
     *
     * @param content the document content to scan; must not be {@code null}
     * @return the distinct tokens found, sorted for deterministic reporting
     */
    private static Set<String> starterTokens(String content) {
        Set<String> tokens = new TreeSet<>();
        Matcher matcher = STARTER_TOKEN_PATTERN.matcher(content);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * Strips every Markdown inline link's URL target, keeping only the reader-facing link text.
     * Link targets (file paths) legitimately contain directory-name substrings such as
     * {@code vertique-starter/} that are not prose mentions and must not be searched as such.
     *
     * @param content the document content to strip; must not be {@code null}
     * @return the content with every {@code ](target)} replaced by {@code ]}
     */
    private static String stripMarkdownLinkTargets(String content) {
        return content.replaceAll("]\\([^)]+\\)", "]");
    }

    /**
     * Asserts that every mention of an infrastructure-only starter artifact in {@code document} is
     * accompanied, in the same paragraph, by an explicit non-consumable marker.
     *
     * @param document the documentation file to check; must not be {@code null}
     * @throws IOException when the document cannot be read
     */
    private static void assertInfrastructureMarkedNonConsumable(Path document) throws IOException {
        String content = stripMarkdownLinkTargets(Files.readString(document, StandardCharsets.UTF_8));
        String[] paragraphs = content.split("\\n\\s*\\n");
        for (String infraArtifact : INFRASTRUCTURE_STARTERS) {
            Pattern tokenPattern = "vertique-starter".equals(infraArtifact)
                    ? Pattern.compile("\\bvertique-starter\\b(?!-)")
                    : Pattern.compile("\\b" + Pattern.quote(infraArtifact) + "\\b");

            boolean mentioned = false;
            for (String paragraph : paragraphs) {
                if (!tokenPattern.matcher(paragraph).find()) {
                    continue;
                }
                mentioned = true;
                String lowerParagraph = paragraph.toLowerCase(Locale.ROOT);
                boolean markedNonConsumable = NON_CONSUMABLE_MARKERS.stream().anyMatch(lowerParagraph::contains);
                assertTrue(
                        markedNonConsumable,
                        document + ": paragraph mentioning \"" + infraArtifact
                                + "\" must mark it internal/non-consumable:\n" + paragraph);
            }
            assertTrue(mentioned, document + " must mention \"" + infraArtifact + "\" at least once");
        }
    }
}
