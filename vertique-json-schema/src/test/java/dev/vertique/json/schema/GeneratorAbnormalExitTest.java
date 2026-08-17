// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import java.lang.reflect.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@link AnnotationJsonSchemaGenerator#generateCanonical(Type)} guarantees when the
 * Victools generation call itself exits abnormally — as opposed to a failure raised by a
 * post-generation walk, which {@link GeneratorPostGenerationWalkTest} covers.
 *
 * <p>Three properties are proven. First, an aborted generation leaves no per-generation Victools
 * provider state pinned: the pinned Victools version resets its stateful providers as straight-line
 * code on the success path only, so a generation that throws from inside {@code generateSchema}
 * would otherwise corrupt every later generation on the same instance. Second, stack exhaustion
 * inside Victools' recursive generation — an {@link Error}, not a {@link RuntimeException} —
 * surfaces as the module's single bounded failure type like any other generation failure. Third,
 * a restoration that itself fails is recorded on the propagating failure rather than replacing it.
 */
class GeneratorAbnormalExitTest {

    @Test
    @DisplayName("An aborted generation restores Victools' provider state for the next generation")
    void abortedGenerationResetsProviderState() {
        // Given: a fresh profile-aware generator publishes a ref-bearing root type's own schema —
        // the baseline the aborted-then-reused generator below must reproduce.
        String baseline = AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile())
                .generateCanonical(HardeningFixtures.ClassLevelRefDto.class);
        assertTrue(
                baseline.contains(HardeningFixtures.CLASS_LEVEL_REF_MARKER),
                "a fresh generator must publish the root type's own schema; was: " + baseline);
        assertFalse(
                baseline.contains(HardeningFixtures.CLASS_LEVEL_REF),
                "a fresh generator must not publish the root type as a bare external $ref; was: " + baseline);

        // Given: a generator whose first generation aborts from inside Victools, through the
        // implementation guard — a documented, tested, reachable outcome.
        AnnotationJsonSchemaGenerator generator =
                AnnotationJsonSchemaGenerator.forInputProfile(HardeningFixtures.strictProfile());
        assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(HardeningFixtures.ImplementationOnOverriddenDto.class),
                "the implementation guard must abort the first generation from inside Victools");

        // When: a second generation runs on a different root type carrying a type-level
        // @Schema(ref = ...).
        String canonical = generator.generateCanonical(HardeningFixtures.ClassLevelRefDto.class);

        // Then: it publishes that type's full schema, exactly as the fresh generator did — the
        // aborted generation left no main-type pinned in Victools' external-ref provider.
        assertFalse(
                canonical.contains(HardeningFixtures.CLASS_LEVEL_REF),
                "an aborted generation must not leave Victools' external-ref provider pinned, which would"
                        + " publish this root type as a bare external $ref; was: " + canonical);
        assertTrue(
                canonical.contains(HardeningFixtures.CLASS_LEVEL_REF_MARKER),
                "the second generation must publish the root type's own schema; was: " + canonical);
    }

    @Test
    @DisplayName("A StackOverflowError inside Victools generation normalizes to a bounded failure")
    void stackOverflowDuringGenerationNormalizesToBoundedException() {
        // Given: a generator whose Victools stage exhausts the stack, as a pathologically deep type
        // graph does inside Victools' recursive generation.
        StackExhaustingGenerator probe = new StackExhaustingGenerator();
        AnnotationJsonSchemaGenerator generator = new AnnotationJsonSchemaGenerator(probe);

        // When: a document is generated.
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(HardeningFixtures.SimpleDto.class),
                "stack exhaustion inside generation must surface as the module's bounded failure type");

        // Then: the failure is bounded, names the type, and preserves the original Error as its cause.
        String message = failure.getMessage();
        assertNotNull(message, "the normalized failure must carry a message");
        assertTrue(
                message.length() <= Diagnostics.MAX_MESSAGE_LENGTH,
                "the normalized message must stay within " + Diagnostics.MAX_MESSAGE_LENGTH + " code units; was "
                        + message.length());
        assertTrue(
                message.contains(HardeningFixtures.SimpleDto.class.getName()),
                "the normalized message must name the requested type; was: " + message);
        assertInstanceOf(
                StackOverflowError.class, failure.getCause(), "the original Error must be preserved as the cause");

        // Then: the same abnormal-exit mechanism restored Victools' provider state, so one mechanism
        // covers both an aborting RuntimeException and an aborting StackOverflowError.
        assertTrue(
                probe.configRequested(),
                "an aborted generation must consult the Victools config to restore per-generation provider state");
    }

    @Test
    @DisplayName("A failing provider-state restoration is recorded, never allowed to displace the abort")
    void failingRestorationIsRecordedRatherThanPropagated() {
        // Given: a generator whose generation aborts, and whose provider-state restoration then fails
        // in turn while that abort is propagating.
        AnnotationJsonSchemaGenerator generator = new AnnotationJsonSchemaGenerator(new RestoreFailingGenerator());

        // When: a document is generated.
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> generator.generateCanonical(HardeningFixtures.SimpleDto.class),
                "a failing restoration must not replace the generation failure it was triggered by");

        // Then: the caller sees the generation failure with the original abort as its cause, and the
        // restoration failure is recorded on that abort rather than propagated in its place.
        Throwable cause = failure.getCause();
        assertInstanceOf(AbortingFailure.class, cause, "the failure that triggered the restoration must be the cause");
        assertEquals(
                1,
                cause.getSuppressed().length,
                "the restoration failure must be recorded as a suppressed exception on the abort");
        assertInstanceOf(
                IllegalStateException.class,
                cause.getSuppressed()[0],
                "the recorded suppressed exception must be the restoration failure itself");
    }

    // --- Probes ---

    /**
     * Victools generator whose generation stage exhausts the stack instead of returning a document,
     * and which records whether {@link #getConfig()} was consulted afterwards — the call through
     * which {@link AnnotationJsonSchemaGenerator} restores per-generation provider state.
     */
    private static final class StackExhaustingGenerator extends SchemaGenerator {

        /** Whether {@link #getConfig()} has been called on this probe. */
        private boolean configRequested;

        private StackExhaustingGenerator() {
            super(new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON).build());
        }

        @Override
        public ObjectNode generateSchema(Type mainTargetType, Type... typeParameters) {
            throw new StackOverflowError();
        }

        @Override
        public SchemaGeneratorConfig getConfig() {
            configRequested = true;
            return super.getConfig();
        }

        /**
         * Reports whether the wrapping generator consulted this probe's configuration.
         *
         * @return {@code true} once {@link #getConfig()} has been called
         */
        private boolean configRequested() {
            return configRequested;
        }
    }

    /**
     * Victools generator whose generation stage aborts with an {@link AbortingFailure} and whose
     * configuration — the entry point through which per-generation provider state is restored — is
     * itself unavailable, so the restoration fails while the generation failure is propagating.
     */
    private static final class RestoreFailingGenerator extends SchemaGenerator {

        private RestoreFailingGenerator() {
            super(new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON).build());
        }

        @Override
        public ObjectNode generateSchema(Type mainTargetType, Type... typeParameters) {
            throw new AbortingFailure();
        }

        @Override
        public SchemaGeneratorConfig getConfig() {
            throw new IllegalStateException("provider-state restoration is unavailable");
        }
    }

    /** The generation failure {@link RestoreFailingGenerator} aborts with, identifiable by its type. */
    private static final class AbortingFailure extends RuntimeException {

        private AbortingFailure() {
            super("generation aborted");
        }
    }
}
