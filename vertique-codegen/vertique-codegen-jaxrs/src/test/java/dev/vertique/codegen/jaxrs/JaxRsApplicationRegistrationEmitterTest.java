// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * APT compilation tests for the resource bindings {@link GeneratedJaxRsResourcesModuleEmitter}
 * writes beside the application registrations (C-GEN): for every DI-eligible resource, a
 * presence-gated legacy {@code @JaxRsResources} binding that takes the native registration set,
 * {@code Set<GeneratedRestApplicationRegistration> applications}, and contributes the resource only
 * while that set is empty, and a lazy {@code GeneratedJaxRsResourceEntry} catalog entry that never
 * calls the provider. A conditional resource's binding and entry share one
 * {@code X_BINDING_CONDITIONS} constant.
 *
 * <p>The registrations themselves are proved elsewhere: {@code @RestApplication} declarations by
 * {@link RestApplicationDeclarationTest} and {@link RestApplicationRegistrationEmitterTest}, and the
 * absence of any {@code jakarta.ws.rs.core.Application} registration by
 * {@link ApplicationSubclassWarningTest}.
 *
 * <p>The test compiles two resource fixtures through {@link JaxRsPipelineProcessor} via
 * {@link ProcessorTestHarness} and asserts each generated method, signature and body, in the
 * {@code GeneratedJaxRsResourcesModule} source.
 */
class JaxRsApplicationRegistrationEmitterTest {

    // -----------------------------------------------------------------------------------------
    // TP-008 — catalog entries and presence-gated bindings are emitted
    // -----------------------------------------------------------------------------------------

    private static final String TP008_MODULE = "dev.vertique.test.tp008.GeneratedJaxRsResourcesModule";

    private static final JavaFileObject TP008_CATALOG_RESOURCE =
            SourceFiles.inline("dev.vertique.test.tp008.CatalogResource", """
            package dev.vertique.test.tp008;

            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/catalog")
            public class CatalogResource {
                @Inject
                public CatalogResource() {}

                @GET
                public String catalog() { return ""; }
            }
            """);

    private static final JavaFileObject TP008_BETA_RESOURCE =
            SourceFiles.inline("dev.vertique.test.tp008.BetaResource", """
            package dev.vertique.test.tp008;

            import dev.vertique.codegen.ConditionalOnProperty;
            import jakarta.inject.Inject;
            import jakarta.ws.rs.GET;
            import jakarta.ws.rs.Path;

            @Path("/beta")
            @ConditionalOnProperty(name = "tp008.betaResource.enabled")
            public class BetaResource {
                @Inject
                public BetaResource() {}

                @GET
                public String beta() { return ""; }
            }
            """);

    /** Expected C-GEN shape (mirroring T002's hand-written {@code unita} module) for the plain resource. */
    private static final String TP008_CATALOG_BINDING = """
            @Provides
            @ElementsIntoSet
            @JaxRsResources
            static Set<Object> catalogResourceBinding(
                    @VertxConfig JsonObject config,
                    Set<GeneratedRestApplicationRegistration> applications,
                    Provider<CatalogResource> provider) {
                return applications.isEmpty() ? Set.of(provider.get()) : Set.of();
            }
            """;

    private static final String TP008_CATALOG_ENTRY = """
            @Provides
            @IntoSet
            static GeneratedJaxRsResourceEntry catalogResourceEntry(
                    @VertxConfig JsonObject config, Provider<CatalogResource> provider) {
                return GeneratedJaxRsResourceEntry.of(CatalogResource.class, true, provider);
            }
            """;

    /** Expected C-GEN shape for the {@code @ConditionalOnProperty} resource, mirroring {@code unita}'s DisabledResource. */
    private static final String TP008_BETA_BINDING = """
            @Provides
            @ElementsIntoSet
            @JaxRsResources
            static Set<Object> betaResourceBinding(
                    @VertxConfig JsonObject config,
                    Set<GeneratedRestApplicationRegistration> applications,
                    Provider<BetaResource> provider) {
                return applications.isEmpty() && PropertyCondition.matchesAll(config, BETA_RESOURCE_BINDING_CONDITIONS)
                        ? Set.of(provider.get())
                        : Set.of();
            }
            """;

    private static final String TP008_BETA_ENTRY = """
            @Provides
            @IntoSet
            static GeneratedJaxRsResourceEntry betaResourceEntry(
                    @VertxConfig JsonObject config, Provider<BetaResource> provider) {
                return GeneratedJaxRsResourceEntry.of(
                        BetaResource.class,
                        PropertyCondition.matchesAll(config, BETA_RESOURCE_BINDING_CONDITIONS),
                        provider);
            }
            """;

    @Test
    @DisplayName("TP-008 — catalog entries and presence-gated bindings on the native registration set are emitted")
    void emitsCatalogEntryAndPresenceGatedBinding() {
        var result =
                ProcessorTestHarness.run(new JaxRsPipelineProcessor(), TP008_CATALOG_RESOURCE, TP008_BETA_RESOURCE);
        result.assertSuccess();
        String source = sourceOf(result, TP008_MODULE);
        logGeneratedSource("TP-008 module", source);

        assertAll(
                "TP-008 C-GEN shape",
                () -> assertContainsNormalized(
                        source, TP008_CATALOG_BINDING, "catalogResourceBinding in " + TP008_MODULE),
                () -> assertContainsNormalized(source, TP008_CATALOG_ENTRY, "catalogResourceEntry in " + TP008_MODULE),
                () -> assertContainsNormalized(source, TP008_BETA_BINDING, "betaResourceBinding in " + TP008_MODULE),
                () -> assertContainsNormalized(source, TP008_BETA_ENTRY, "betaResourceEntry in " + TP008_MODULE));
    }

    // -----------------------------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Reads the character content of a generated source file, asserting its presence first so a
     * missing file fails as an {@link AssertionFailedError} rather than surfacing an unchecked
     * {@code Optional.get()} exception.
     */
    private static String sourceOf(ProcessorTestHarness.Result result, String generatedFqn) {
        var generated = result.compilation().generatedSourceFile(generatedFqn);
        assertTrue(
                generated.isPresent(),
                () -> "Expected generated source file for '" + generatedFqn + "' but none was found."
                        + diagnosticsSummary(result));
        try {
            return generated.get().getCharContent(true).toString();
        } catch (IOException e) {
            throw new AssertionFailedError(
                    "Failed to read generated source for '" + generatedFqn + "': " + e.getMessage());
        }
    }

    private static String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").replaceAll(" ?([(),<>]) ?", "$1").trim();
    }

    /**
     * Asserts that {@code actual} contains {@code expectedSnippet} once both are collapsed to
     * single-space-separated tokens with no space beside a parenthesis, comma, or angle bracket, so
     * JavaPoet's column-100 wrapping (which may break a line right after an opening parenthesis)
     * cannot break an otherwise correct match.
     */
    private static void assertContainsNormalized(String actual, String expectedSnippet, String context) {
        String normalizedActual = normalizeWhitespace(actual);
        String normalizedExpected = normalizeWhitespace(expectedSnippet);
        assertTrue(
                normalizedActual.contains(normalizedExpected),
                () -> "Expected " + context + " to contain (after whitespace normalization):\n" + normalizedExpected
                        + "\nActual (normalized):\n" + normalizedActual);
    }

    private static void logGeneratedSource(String label, String source) {
        System.out.println("=== " + label + " ===");
        System.out.println(source);
    }

    private static String diagnosticsSummary(ProcessorTestHarness.Result result) {
        StringBuilder sb = new StringBuilder("\nCompilation diagnostics:\n");
        result.compilation().diagnostics().forEach(d -> sb.append("  [")
                .append(d.getKind())
                .append("] ")
                .append(d.getMessage(null))
                .append('\n'));
        return sb.toString();
    }
}
