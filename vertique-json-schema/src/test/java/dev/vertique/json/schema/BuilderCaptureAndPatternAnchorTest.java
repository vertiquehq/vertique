// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S1 (the {@code builderFor} capturing-mapper guard) and S2 (the {@code \z} pattern anchor for
 * case-folded property names).
 */
class BuilderCaptureAndPatternAnchorTest {

    private static JsonMapperProfile profile(ObjectMapper mapper) {
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("test");
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }

            @Override
            public List<dev.vertique.core.json.JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return List.of();
            }
        };
    }

    // ================================================================== S1

    /** An {@link ObjectMapper} that refuses to copy itself, simulating a hostile or misconfigured subclass. */
    static final class RefusesToCopyMapper extends ObjectMapper {
        @Override
        public ObjectMapper copy() {
            throw new UnsupportedOperationException("this mapper refuses to be copied");
        }
    }

    static final class PlainDto {
        public String name;
    }

    @Test
    @DisplayName("S1: a mapper whose copy() throws yields the bounded generation diagnostic, not a raw exception")
    void mapperCopyFailureYieldsBoundedDiagnostic() {
        JsonSchemaGenerationException failure = assertThrows(
                JsonSchemaGenerationException.class,
                () -> AnnotationJsonSchemaGenerator.forInputProfile(profile(new RefusesToCopyMapper()))
                        .generateCanonical(PlainDto.class),
                "a mapper that refuses to copy itself must surface as the bounded generation diagnostic,"
                        + " not the raw UnsupportedOperationException mapper.copy() throws");

        assertNotNull(failure.getMessage(), "the diagnostic must carry a message");
        assertTrue(
                failure.getMessage().contains(PlainDto.class.getSimpleName()),
                "the message must name the type being described; was: " + failure.getMessage());
        // Decisive, not merely "some JsonSchemaGenerationException": AnnotationJsonSchemaGenerator's
        // own outer catch (RuntimeException | StackOverflowError) around generator.generateSchema(...)
        // already wraps any raw exception, S1 or not — so a bare assertThrows(JsonSchemaGenerationException
        // .class, ...) alone cannot tell the two apart. What only S1's guarded builderFor produces is
        // this specific message; the outer catch's fallback message never names the mapper at all.
        assertTrue(
                failure.getMessage().contains("the profile's mapper cannot deserialize it"),
                "the message must be builderFor's own specific diagnostic, not the generator's generic"
                        + " outer-catch fallback; was: " + failure.getMessage());
        assertTrue(
                failure.getCause() instanceof UnsupportedOperationException,
                "the original failure must be preserved as the cause; was: " + failure.getCause());
    }

    // ================================================================== S2

    static final class CaseInsensitiveDto {
        @jakarta.validation.constraints.Size(max = 10)
        public String name;

        @com.fasterxml.jackson.annotation.JsonAnySetter
        private final java.util.Map<String, Object> extras = new java.util.LinkedHashMap<>();
    }

    @Test
    @DisplayName("S2: the case-folded property pattern is anchored with \\z, not $ — a trailing newline must not match")
    void caseFoldedPatternDoesNotMatchAKeyWithATrailingNewline() {
        ObjectMapper mapper = new ObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        JsonNode document = assertCanonicalForm(AnnotationJsonSchemaGenerator.forInputProfile(profile(mapper))
                .generateCanonical(CaseInsensitiveDto.class));
        JsonNode patternProperties = document.path("patternProperties");
        assertFalse(patternProperties.isMissingNode(), "the case-insensitive type must publish patternProperties");
        String regex = patternProperties.fieldNames().next();

        // io.vertx.json.schema 5.1.6 compiles "pattern" with plain java.util.regex.Pattern and applies
        // it with an unanchored substring search (Matcher#find) — exactly what this test reproduces.
        boolean matchesTrailingNewlineKey =
                Pattern.compile(regex).matcher("name\n").find();

        assertFalse(
                matchesTrailingNewlineKey,
                "a key ending in a newline must not match the case-folded pattern for \"name\": under a"
                        + " trailing $ anchor (rather than \\z), java.util.regex.Pattern's $ matches"
                        + " immediately before a single trailing line terminator even without MULTILINE,"
                        + " which would wrongly accept \"name\\n\" as the property \"name\"; pattern was: "
                        + regex);
        assertTrue(
                Pattern.compile(regex).matcher("name").find(),
                "the fold must still match the exact key; pattern was: " + regex);
    }
}
