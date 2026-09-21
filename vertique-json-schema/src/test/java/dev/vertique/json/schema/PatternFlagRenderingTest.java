// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.Validator;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The measurement design point 4 requires: whether {@code io.vertx.json.schema} 5.1.6's {@code
 * pattern} keyword honors an embedded Java regex modifier group, which decides whether
 * {@link MetadataConstraintSource} may render a flagged {@code @Pattern} inline or must refuse it.
 *
 * <p>{@code io.vertx.json.schema} compiles the {@code pattern} keyword with plain
 * {@code java.util.regex.Pattern.compile(...)} (confirmed here, not assumed): an embedded modifier
 * group such as {@code (?i:...)} is honored exactly as it would be by any direct
 * {@code java.util.regex} caller. {@link MetadataConstraintSource} therefore renders every {@code
 * jakarta.validation.constraints.Pattern.Flag} that has an embeddable Java regex modifier character
 * this way, and refuses (with a diagnostic) the one flag that has none: {@code CANON_EQ}.
 */
class PatternFlagRenderingTest {

    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    @Test
    @DisplayName("measurement: io.vertx.json.schema honors an embedded (?i:...) modifier group on `pattern`")
    void vertxJsonSchemaHonorsEmbeddedCaseInsensitiveModifier() {
        JsonObject schema = new JsonObject()
                .put("type", "object")
                .put(
                        "properties",
                        new JsonObject()
                                .put(
                                        "code",
                                        new JsonObject().put("type", "string").put("pattern", "(?i:^abc$)")));
        JsonSchemaOptions options =
                new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/pattern-flag/");
        Validator validator = Validator.create(JsonSchema.of(schema), options);

        assertTrue(validator.validate(new JsonObject().put("code", "abc")).getValid());
        assertTrue(validator.validate(new JsonObject().put("code", "ABC")).getValid());
        assertTrue(validator.validate(new JsonObject().put("code", "aBc")).getValid());
        assertFalse(validator.validate(new JsonObject().put("code", "xyz")).getValid());
    }

    @Test
    @DisplayName("a CASE_INSENSITIVE @Pattern renders as an embedded (?i:...) group that the real gate enforces")
    void caseInsensitiveFlagRendersAndValidates() {
        Validator validator = generatedValidatorFor(MetadataFixtures.Sharp606Dto.class);

        assertTrue(validator.validate(sharp606Instance("abc")).getValid());
        assertTrue(validator.validate(sharp606Instance("ABC")).getValid());
        assertFalse(validator.validate(sharp606Instance("xyz")).getValid());
    }

    @Test
    @DisplayName("a @Pattern flag with no embeddable modifier (CANON_EQ) is refused with a diagnostic")
    void canonEqFlagIsRefused() {
        jakarta.validation.Validator bv = MetadataTestValidators.plain();
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> AnnotationJsonSchemaGenerator.forInputProfile(
                                vertiqueProfile(), bv)
                        .generateCanonical(CanonEqDto.class));

        assertTrue(failure.getMessage().contains("CANON_EQ"), "the diagnostic must name the unrenderable flag");
    }

    /** No embeddable Java regex modifier exists for {@code CANON_EQ}. */
    static final class CanonEqDto {
        @Pattern(regexp = "[a-z]+", flags = Pattern.Flag.CANON_EQ)
        public String value;
    }

    private static Validator generatedValidatorFor(Class<?> type) {
        jakarta.validation.Validator bv = MetadataTestValidators.plain();
        String canonical = AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile(), bv)
                .generateCanonical(type);
        JsonObject schema = new JsonObject(canonical);
        JsonSchemaOptions options =
                new JsonSchemaOptions().setDraft(Draft.DRAFT202012).setBaseUri("https://vertique.local/sharp606/");
        return Validator.create(JsonSchema.of(schema), options);
    }

    private static JsonObject sharp606Instance(String pattern) {
        return new JsonObject()
                .put("range", 15)
                .put("length", "abcd")
                .put("url", "https://example.com")
                .put("caseInsensitivePattern", pattern)
                .put("exclusiveMax", 1);
    }
}
