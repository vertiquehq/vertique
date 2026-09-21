// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import dev.vertique.rest.validation.corpus.CorpusFixture;
import dev.vertique.rest.validation.corpus.SchemaCorpus;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fallback identity, corpus half (the other half is {@code vertique-json-schema}'s own {@code
 * MetadataConstraintSourceCoverageTest.fallbackMatchesSingleArgumentFactoryForEveryCoverageFixture}):
 * {@link AnnotationJsonSchemaGenerator}'s single-argument factory ({@code
 * forInputProfile(JsonMapperProfile)}) must produce byte-identical output to the two-argument overload
 * called with an explicit {@code null} {@code Validator}, for every one of the 20 pinned {@code
 * schema-corpus} fixtures under both profiles the factory supports ({@code system}, {@code vertique}) —
 * not just the one type a narrower proof might happen to exercise. {@code legacy} is not a case here:
 * its generator, {@code AnnotationJsonSchemaGenerator#withVictoolsDefaults()}, has no {@code Validator}
 * overload at all.
 */
class SchemaCorpusFallbackIdentityTest {

    private static JsonMapperProfile profileOf(String id) {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of(id));
    }

    static Stream<Arguments> corpusFixturesByProfile() {
        return SchemaCorpus.FIXTURES.stream().flatMap(fixture -> Stream.of("system", "vertique")
                .map(profileId ->
                        Arguments.of(Named.of(fixture.name() + " (" + profileId + ")", fixture), profileId)));
    }

    @ParameterizedTest
    @MethodSource("corpusFixturesByProfile")
    @DisplayName("forInputProfile(profile) matches forInputProfile(profile, null) for every corpus fixture")
    void fallbackMatchesSingleArgumentFactoryForEveryCorpusFixture(CorpusFixture fixture, String profileId) {
        Type type = fixture.genericType() != null ? fixture.genericType() : fixture.rawType();
        JsonMapperProfile profile = profileOf(profileId);

        String throughOverload =
                AnnotationJsonSchemaGenerator.forInputProfile(profile).generateCanonical(type);
        String withNullValidator =
                AnnotationJsonSchemaGenerator.forInputProfile(profile, null).generateCanonical(type);

        assertEquals(
                throughOverload,
                withNullValidator,
                "the single-argument factory and the two-argument overload with a null Validator must be"
                        + " byte-identical for corpus fixture " + fixture.name() + " under profile " + profileId);
    }
}
