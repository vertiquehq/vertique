// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.junit5.VertxExtension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Verifies the captured public API declarations remain available after compatible changes. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowPublicApiCompatibilityIT {

    private static final String CAPTURE_PROPERTY = "workflow.compatibility.capture";
    private static final String BASELINE_RESOURCE = "/compatibility/workflow-public-api-baseline.txt";
    private static final String APPROVED_REMOVALS_RESOURCE = "/compatibility/workflow-public-api-approved-removals.txt";
    private static final int APPROVED_REMOVAL_COUNT = 47;

    @Test
    @DisplayName("capture the original public API baseline when requested")
    void captureBaseline() throws Exception {
        if (!Boolean.getBoolean(CAPTURE_PROPERTY)) {
            assertThat(readBaseline()).isNotEmpty();
            return;
        }

        Path repositoryRoot = WorkflowPublicApiSnapshot.repositoryRoot();
        List<String> declarations = WorkflowPublicApiSnapshot.capturePinnedBaseline(repositoryRoot);
        assertThat(declarations).isNotEmpty();

        Path output = repositoryRoot.resolve(
                "vertique-workflow/vertique-workflow-postgresql/target/compatibility/workflow-public-api-baseline.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, String.join("\n", declarations) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("preserve the original public API except the approved replacements and removals")
    void preservesBaselineExceptApprovedReplacementsAndRemovals() throws Exception {
        Path repositoryRoot = WorkflowPublicApiSnapshot.repositoryRoot();
        List<String> baseline = readBaseline();
        List<String> approvedReplacements = approvedReplacements();
        Set<String> replacementSet = Set.copyOf(approvedReplacements);
        List<ApprovedRemoval> approvedRemovals = readApprovedRemovals();
        Set<String> removalSet = new HashSet<>();
        approvedRemovals.forEach(removal -> removalSet.add(removal.declaration()));

        assertThat(baseline)
                .containsExactlyElementsOf(baseline.stream().sorted().toList());
        assertThat(baseline).doesNotHaveDuplicates();
        assertThat(approvedReplacements).hasSize(8).doesNotHaveDuplicates();
        assertThat(baseline.stream().filter(replacementSet::contains).toList())
                .containsExactlyInAnyOrderElementsOf(approvedReplacements);

        assertThat(approvedRemovals).hasSize(APPROVED_REMOVAL_COUNT);
        assertThat(approvedRemovals).extracting(ApprovedRemoval::declaration).doesNotHaveDuplicates();
        assertThat(approvedRemovals).extracting(ApprovedRemoval::reason).allMatch(reason -> !reason.isBlank());
        assertThat(baseline)
                .as("every approved removal is a captured declaration")
                .containsAll(removalSet);
        assertThat(removalSet)
                .as("a declaration is replaced or removed, not both")
                .doesNotContainAnyElementsOf(replacementSet);

        // A removed type cannot be loaded, so the current capture looks up only the types the baseline keeps.
        List<String> keptForLookup = baseline.stream()
                .filter(declaration -> !removalSet.contains(declaration))
                .toList();
        Set<String> currentDeclarations =
                new HashSet<>(WorkflowPublicApiSnapshot.captureCurrentTypes(repositoryRoot, keptForLookup));
        List<String> missing = baseline.stream()
                .filter(declaration -> !replacementSet.contains(declaration))
                .filter(declaration -> !removalSet.contains(declaration))
                .filter(declaration -> !currentDeclarations.contains(declaration))
                .toList();

        assertThat(missing)
                .as("Original public declarations missing from the compiled modules")
                .isEmpty();
        assertThat(removalSet.stream().filter(currentDeclarations::contains).toList())
                .as("Approved removals that are still declared, which no longer need an approval")
                .isEmpty();
    }

    /** One captured declaration the release removes on purpose, with the reason it is removed. */
    private record ApprovedRemoval(String declaration, String reason) {}

    private List<ApprovedRemoval> readApprovedRemovals() throws IOException {
        InputStream removalStream = getClass().getResourceAsStream(APPROVED_REMOVALS_RESOURCE);
        if (removalStream == null) {
            throw new AssertionError("The approved public API removals fixture is missing");
        }
        try (removalStream) {
            return new String(removalStream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> {
                        int separator = line.indexOf('\t');
                        if (separator < 0) {
                            throw new AssertionError("An approved removal has no reason: " + line);
                        }
                        return new ApprovedRemoval(line.substring(0, separator), line.substring(separator + 1));
                    })
                    .toList();
        }
    }

    private List<String> readBaseline() throws IOException {
        InputStream baselineStream = getClass().getResourceAsStream(BASELINE_RESOURCE);
        if (baselineStream == null) {
            throw new AssertionError("The original public API baseline fixture is missing");
        }
        try (baselineStream) {
            return new String(baselineStream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .toList();
        }
    }

    private List<String> approvedReplacements() {
        String service = "dev.vertique.workflow.definition.service.WorkflowDefinitionService";
        String implementation = "dev.vertique.workflow.definition.service.DefaultWorkflowDefinitionService";
        String format = "dev.vertique.workflow.definition.parser.DocumentFormat";
        String metadata = "dev.vertique.workflow.definition.source.SourceMetadata";
        String reference = "dev.vertique.workflow.definition.service.CompiledDefinitionRef";

        return List.of(
                method(service, "load", "public abstract", List.of("byte[]", format, metadata), reference),
                method(service, "activate", "public abstract", List.of("java.lang.String", "long"), "void"),
                method(service, "list", "public abstract", List.of(), "java.util.Collection<" + reference + ">"),
                method(
                        service,
                        "sourceMetadata",
                        "public abstract",
                        List.of("java.lang.String", "long"),
                        "java.util.Optional<" + metadata + ">"),
                method(implementation, "load", "public", List.of("byte[]", format, metadata), reference),
                method(implementation, "activate", "public", List.of("java.lang.String", "long"), "void"),
                method(implementation, "list", "public", List.of(), "java.util.Collection<" + reference + ">"),
                method(
                        implementation,
                        "sourceMetadata",
                        "public",
                        List.of("java.lang.String", "long"),
                        "java.util.Optional<" + metadata + ">"));
    }

    private String method(
            String declaringType, String name, String modifiers, List<String> parameterTypes, String returnType) {
        return String.join(
                "|",
                "METHOD",
                declaringType,
                name,
                modifiers,
                "[]",
                "[" + String.join(",", parameterTypes) + "]",
                returnType,
                "[]",
                "false");
    }
}
