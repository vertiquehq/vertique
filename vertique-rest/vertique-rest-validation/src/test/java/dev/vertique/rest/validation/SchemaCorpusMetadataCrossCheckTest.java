// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.rest.validation.corpus.CorpusFixture;
import dev.vertique.rest.validation.corpus.SchemaCorpus;
import dev.vertique.rest.validation.corpus.SchemaCorpusGenerator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Corpus cross-check for the Bean Validation metadata source: every one of the 60 pinned {@code
 * schema-corpus} documents (20 fixtures &times; {@code legacy}/{@code system}/{@code vertique}) is
 * regenerated with a {@link Validator} supplied and asserted byte-identical to both the pinned golden
 * file and the annotation-walk document the existing {@code AnnotationSchemaSourceTest} corpus proofs
 * already pin. The frozen fixture set carries no Bean-Validation-metadata-only shape (no
 * constructor-parameter-only constraint, no container-element constraint, no XML mapping), so an
 * identical byte-for-byte result is the expected outcome: this proof is a regression guard, not a
 * coverage proof — {@code MetadataConstraintSourceCoverageTest} (in {@code vertique-json-schema})
 * covers the shapes only the metadata source can express.
 */
class SchemaCorpusMetadataCrossCheckTest {

    /** Bootstrapped with {@link ParameterMessageInterpolator}, matching the design's no-EL finding. */
    private static final Validator VALIDATOR = Validation.byProvider(HibernateValidator.class)
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    private static DefaultJsonMapperProfileRegistry builtInRegistry() {
        return new DefaultJsonMapperProfileRegistry(Set.of());
    }

    private static JsonMapperProfile profileOf(String id) {
        return builtInRegistry().profile(JsonProfileId.of(id));
    }

    private static String goldenDocument(String corpusDirectory, CorpusFixture fixture) {
        String resource = "/" + SchemaCorpusGenerator.CORPUS_ROOT + "/" + corpusDirectory + "/" + fixture.fileName();
        try (InputStream stream = SchemaCorpusMetadataCrossCheckTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "golden corpus document " + resource + " must exist");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The metadata-backed schema source, isolated from the module's default (validator-less) singleton. */
    private static final class MetadataBackedSource extends AnnotationSchemaSource {
        MetadataBackedSource() {
            super(Optional.of(VALIDATOR));
        }
    }

    private static JsonNode metadataDocumentFor(CorpusFixture fixture, JsonMapperProfile profile) {
        MetadataBackedSource source = new MetadataBackedSource();
        Type type = fixture.genericType() != null ? fixture.genericType() : fixture.rawType();
        return source.generateBodySchema(type, profile);
    }

    private static JsonNode walkDocumentFor(CorpusFixture fixture, JsonMapperProfile profile) {
        AnnotationSchemaSource source = new AnnotationSchemaSource();
        Type type = fixture.genericType() != null ? fixture.genericType() : fixture.rawType();
        return source.generateBodySchema(type, profile);
    }

    static Stream<Arguments> corpusFixtures() {
        return SchemaCorpus.FIXTURES.stream().map(fixture -> Arguments.of(Named.of(fixture.name(), fixture)));
    }

    @ParameterizedTest
    @MethodSource("corpusFixtures")
    @DisplayName("system: the metadata source's document equals the walk's document and the pinned file")
    void systemMetadataMatchesWalkAndGolden(CorpusFixture fixture) {
        JsonMapperProfile profile = profileOf("system");
        assertTripleEquality("system", fixture, profile);
    }

    @ParameterizedTest
    @MethodSource("corpusFixtures")
    @DisplayName("vertique: the metadata source's document equals the walk's document and the pinned file")
    void vertiqueMetadataMatchesWalkAndGolden(CorpusFixture fixture) {
        JsonMapperProfile profile = profileOf("vertique");
        assertTripleEquality("vertique", fixture, profile);
    }

    @ParameterizedTest
    @MethodSource("corpusFixtures")
    @DisplayName("legacy: the pinned file is unaffected by the metadata source (withVictoolsDefaults never uses one)")
    void legacyIsUnaffected(CorpusFixture fixture) {
        assertEquals(
                goldenDocument(SchemaCorpusGenerator.LEGACY_DIRECTORY, fixture),
                SchemaCorpusGenerator.document(fixture) + "\n",
                "the legacy golden document for " + fixture.name() + " is generated by withVictoolsDefaults() alone,"
                        + " which has no validator overload, so it must be unaffected by this change");
    }

    private static void assertTripleEquality(String corpusDirectory, CorpusFixture fixture, JsonMapperProfile profile) {
        String golden = goldenDocument(corpusDirectory, fixture);
        String metadataRendered = SchemaCorpusGenerator.render(metadataDocumentFor(fixture, profile));
        String walkRendered = SchemaCorpusGenerator.render(walkDocumentFor(fixture, profile));

        assertEquals(
                golden,
                walkRendered,
                "the walk-source document for " + fixture.name() + " under " + corpusDirectory
                        + " must equal its pinned golden file (regression baseline)");
        assertEquals(
                golden,
                metadataRendered,
                "the metadata-source document for " + fixture.name() + " under " + corpusDirectory
                        + " must equal its pinned golden file — the frozen corpus has no shape only the"
                        + " metadata source can express, so it must render byte-identical output");
    }
}
