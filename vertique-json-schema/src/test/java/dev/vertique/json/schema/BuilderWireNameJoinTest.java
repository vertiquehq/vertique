// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import jakarta.validation.constraints.Max;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code InputPropertyDescriber#methodSchema}'s builder-method borrow used to scan the built type's
 * declared fields by raw name for a match against either the builder method's own Java name or the
 * property's wire name. It was first tightened to borrow from the built type's own
 * <em>Jackson-introspected</em> property of that wire name instead — Jackson's own semantics, rather
 * than a coincidental name scan — which by itself did not close the shape-level gap: a builder method
 * is assumed to set the built property of the same wire name, an assumption guaranteed by construction
 * for a Lombok {@code @Builder @Jacksonized} setter but not for a hand-written builder that transforms
 * the value before assigning it.
 *
 * <p>The owner ruling ({@code spike/deserializer-driven-schema}) closes that gap by bounding the
 * borrow to the shape it can actually vouch for — see {@link BuilderBorrowDetector} and {@code
 * module.md}'s "Builder borrow assumption": the whole shape {@code @Jacksonized} generates by
 * default, read entirely through {@code java.lang.reflect} over Jackson's own runtime-visible
 * annotations. A hand-written builder reproducing that exact shape resolves too — the ruling tolerates
 * that ({@link #handWrittenLombokShapedBuilderAlsoBorrows()} pins it) — but one that does not (a
 * value-transforming setter behind a plainly named builder class, {@link
 * #handWrittenTransformingBuilderNoLongerBorrows()}) is published by type only, with no borrowed
 * constraint. {@link #lombokBuilderStillBorrowsBuiltFieldConstraint()} pins that a genuine Lombok
 * builder is unaffected.
 */
class BuilderWireNameJoinTest {

    private static JsonMapperProfile profile() {
        return new JsonMapperProfile() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("test");
            }

            @Override
            public ObjectMapper mapper() {
                return new ObjectMapper();
            }

            @Override
            public List<JsonSchemaTypeOverride> jsonSchemaTypeOverrides() {
                return List.of();
            }
        };
    }

    private static JsonNode document(Class<?> type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
    }

    /**
     * A hand-written (non-Lombok) builder whose setter divides the incoming value by 100 before
     * assigning it to the built field of the same wire name. Its builder class is deliberately named
     * plainly ({@code Builder}, nested in {@code TransformingBuilderDto}) rather than in the Lombok
     * convention ({@code TransformingBuilderDtoBuilder}) that {@link BuilderBorrowDetector} requires,
     * so this fixture does not match the shape regardless of its {@code @JsonPOJOBuilder} values — it
     * is, on purpose, a hand-written builder the detector cannot mistake for a Lombok one.
     */
    @JsonDeserialize(builder = TransformingBuilderDto.Builder.class)
    static final class TransformingBuilderDto {
        @Max(10)
        private final int amount;

        private TransformingBuilderDto(int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }

        @JsonPOJOBuilder(withPrefix = "")
        static final class Builder {
            private int amount;

            Builder amount(int amountCents) {
                this.amount = amountCents / 100;
                return this;
            }

            TransformingBuilderDto build() {
                return new TransformingBuilderDto(amount);
            }
        }
    }

    /**
     * A real Lombok {@code @Builder @Jacksonized} type with a constrained field, the counterpart to
     * {@link TransformingBuilderDto}: {@code @Jacksonized} emits {@code @JsonDeserialize(builder =
     * LombokAmountDtoBuilder.class)} on this class and {@code @JsonPOJOBuilder(withPrefix = "",
     * buildMethodName = "build")} on the builder, guaranteeing by construction that its {@code
     * amount(int)} method sets this class's own {@code amount} field unchanged.
     */
    @Builder
    @Jacksonized
    @Getter
    static final class LombokAmountDto {
        @Max(10)
        private final int amount;
    }

    /**
     * A hand-written (non-Lombok) builder that reproduces {@code @Jacksonized}'s exact shape by hand:
     * a {@code static} nested class named in the Lombok convention ({@code
     * HandWrittenLombokShapedDtoBuilder}), the same {@code @JsonDeserialize}/{@code @JsonPOJOBuilder}
     * annotation values, a {@code build()} returning the built type, and a one-argument setter whose
     * name and parameter type both match the field exactly, with no value transform.
     * {@link BuilderBorrowDetector} cannot tell this apart from a real Lombok builder — which the
     * owner ruling tolerates: it says a hand-written builder <em>may</em> go unresolved, not that it
     * must.
     */
    @JsonDeserialize(builder = HandWrittenLombokShapedDto.HandWrittenLombokShapedDtoBuilder.class)
    static final class HandWrittenLombokShapedDto {
        @Max(10)
        private final int amount;

        private HandWrittenLombokShapedDto(int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }

        @JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
        static final class HandWrittenLombokShapedDtoBuilder {
            private int amount;

            HandWrittenLombokShapedDtoBuilder amount(int amount) {
                this.amount = amount;
                return this;
            }

            HandWrittenLombokShapedDto build() {
                return new HandWrittenLombokShapedDto(amount);
            }
        }
    }

    @Test
    @DisplayName("Owner ruling: a hand-written builder method that transforms its value no longer borrows the"
            + " built field's constraint — the property is published by type only")
    void handWrittenTransformingBuilderNoLongerBorrows() {
        JsonNode document = document(TransformingBuilderDto.class);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must still be published: the builder method binds it");
        assertTrue(
                amount.at("/maximum").isMissingNode(),
                "a hand-written builder that does not reproduce the Lombok shape must not have its"
                        + " constraint borrowed: amount must carry no maximum, only its type; document: "
                        + document);
        assertEquals(
                "integer",
                amount.at("/type").asText(),
                "the property must still be described by its Jackson-resolved type even with no borrowed"
                        + " constraint; document: " + document);
    }

    @Test
    @DisplayName("Owner ruling: a real Lombok @Builder @Jacksonized type's builder method still borrows the"
            + " built field's constraint")
    void lombokBuilderStillBorrowsBuiltFieldConstraint() {
        JsonNode document = document(LombokAmountDto.class);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must be published: the builder method binds it");
        assertEquals(
                10,
                amount.at("/maximum").asInt(),
                "a genuine Lombok builder is guaranteed by construction to set the built field of the same"
                        + " name, so BuilderBorrowDetector must recognize its shape and keep borrowing its"
                        + " @Max(10); document: " + document);
    }

    @Test
    @DisplayName("Owner ruling: a hand-written builder that exactly reproduces the Lombok shape also borrows"
            + " the built field's constraint")
    void handWrittenLombokShapedBuilderAlsoBorrows() {
        JsonNode document = document(HandWrittenLombokShapedDto.class);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must be published: the builder method binds it");
        assertEquals(
                10,
                amount.at("/maximum").asInt(),
                "a hand-written builder that reproduces @Jacksonized's exact shape must resolve the same"
                        + " way a real Lombok builder does: the owner ruling allows a hand-written builder"
                        + " to go unresolved, it never requires it; document: " + document);
    }
}
