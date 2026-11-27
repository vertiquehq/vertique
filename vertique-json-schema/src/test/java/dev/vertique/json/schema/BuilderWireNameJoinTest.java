// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.Jackson2JsonProfileCodec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code InputPropertyDescriber#methodSchema}'s builder-method borrow takes a builder method's
 * constraints from the built type's own <em>Jackson-introspected</em> property of the method's wire
 * name, never from a raw name scan over the declared fields. A builder method is assumed to set the
 * built property of the same wire name — an assumption guaranteed by construction for a Lombok
 * {@code @Builder @Jacksonized} setter but not for a hand-written builder that transforms the value
 * before assigning it. The borrow is therefore bounded:
 *
 * <ul>
 *   <li>A built property <em>with a getter</em> borrows for any builder, whatever its shape, because
 *       the getter publishes the field's constraints regardless of the builder. {@link
 *       #withPrefixBuilderStillBorrowsThroughTheGetterBackedProperty()}, {@link
 *       #plainlyNamedBuilderClassStillBorrowsThroughTheGetterBackedProperty()}, and {@link
 *       #renamedBuildMethodStillBorrowsThroughTheGetterBackedProperty()} each violate exactly one
 *       condition of {@link BuilderBorrowDetector}'s Lombok shape while keeping both built properties
 *       getter-backed. {@link #handWrittenTransformingBuilderWithGetterBorrowsTheGetterBackedConstraint()}
 *       pins the value-transforming counterpart: the getter-backed field's constraint is borrowed even
 *       though the builder itself transforms the value.
 *   <li>A <em>getter-less</em> private field borrows only for the shape the borrow can vouch for — see
 *       {@link BuilderBorrowDetector} and {@code module.md}'s "Builder borrow assumption": the whole
 *       shape {@code @Jacksonized} generates by default, read entirely through {@code
 *       java.lang.reflect} over Jackson's own runtime-visible annotations. A hand-written builder
 *       reproducing that exact shape resolves too ({@link #handWrittenLombokShapedBuilderAlsoBorrows()}
 *       pins it); one that does not (a value-transforming setter behind a plainly named builder class)
 *       is published by type only, with no borrowed constraint. {@link
 *       #lombokBuilderStillBorrowsBuiltFieldConstraint()} pins that a genuine Lombok builder borrows.
 * </ul>
 */
class BuilderWireNameJoinTest {

    private static JsonProfileCodec profile() {
        return new Jackson2JsonProfileCodec() {
            @Override
            public JsonProfileId id() {
                return JsonProfileId.of("test");
            }

            @Override
            protected ObjectMapper mapper() {
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
     * The getter-less counterpart to {@link TransformingBuilderDto}: the same hand-written,
     * value-transforming, non-Lombok-shaped builder, but the built field has no accessor at all. The
     * line is drawn at the getter — a getter-less field's constraint is never published, so this shape
     * stays unresolved, unlike {@link TransformingBuilderDto}'s getter-backed {@code amount}. {@code
     * MetadataConstraintSourceCoverageTest} pins this with and without a validator, alongside the
     * getter-backed sibling.
     */
    @JsonDeserialize(builder = TransformingBuilderNoGetterDto.Builder.class)
    static final class TransformingBuilderNoGetterDto {
        @Max(10)
        private final int amount;

        private TransformingBuilderNoGetterDto(int amount) {
            this.amount = amount;
        }

        @JsonPOJOBuilder(withPrefix = "")
        static final class Builder {
            private int amount;

            Builder amount(int amountCents) {
                this.amount = amountCents / 100;
                return this;
            }

            TransformingBuilderNoGetterDto build() {
                return new TransformingBuilderNoGetterDto(amount);
            }
        }
    }

    /**
     * A control twin of {@link TransformingBuilderDto}, testing the floor rather than the metadata
     * supplement (see {@link MetadataConstraintSourceCoverageTest
     * #HandWrittenBuilderXmlMappedRenamedDto}, the supplement-side fixture): the built field
     * carries a direct {@code @Max(10)} — always reflectable, no XML mapping or Validator needed — and
     * the wire name ({@code amount_cents}, via {@code @JsonProperty} on both the getter and the
     * builder method) diverges from the Java name implied by the builder method ({@code withAmount}
     * stripped of {@code with} -> {@code amount}). {@link InputPropertyDescriber
     * #borrowBuilderFieldAttributes} (the floor) keys its own built-property lookup on {@code
     * BeanPropertyDefinition#getName()} — always the wire name — so this shape is the control proving
     * the floor already gets the wire-name join right; {@link
     * #renamedWireNameBuilderStillBorrowsThroughTheGetterBackedProperty()} pins it.
     */
    @JsonDeserialize(builder = RenamedWireNameBuilderDto.Builder.class)
    static final class RenamedWireNameBuilderDto {
        @Max(10)
        private final int amount;

        private RenamedWireNameBuilderDto(int amount) {
            this.amount = amount;
        }

        @JsonProperty("amount_cents")
        public int getAmount() {
            return amount;
        }

        @JsonPOJOBuilder(withPrefix = "with")
        static final class Builder {
            private int amount;

            @JsonProperty("amount_cents")
            public Builder withAmount(int amount) {
                this.amount = amount;
                return this;
            }

            RenamedWireNameBuilderDto build() {
                return new RenamedWireNameBuilderDto(amount);
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
     * {@link BuilderBorrowDetector} cannot tell this apart from a real Lombok builder — which is
     * tolerated: a hand-written builder <em>may</em> go unresolved, but is not required to.
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

    /**
     * {@link TransformingBuilderDto#getAmount()} makes {@code amount} a getter-backed property, so it
     * is not bounded by {@link BuilderBorrowDetector}'s Lombok shape at all — the borrow resolves
     * unconditionally on the builder's shape, even though the builder itself transforms the value
     * before assigning it. Every getter-backed field's constraints are borrowed regardless of what any
     * builder method's body does with the value.
     */
    @Test
    @DisplayName("a hand-written, value-transforming builder still borrows a getter-backed built field's constraint")
    void handWrittenTransformingBuilderWithGetterBorrowsTheGetterBackedConstraint() {
        JsonNode document = document(TransformingBuilderDto.class);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must still be published: the builder method binds it");
        assertEquals(
                10,
                amount.at("/maximum").asInt(-1),
                "amount has a getter, so its @Max(10) must be borrowed regardless of the builder's own"
                        + " value-transforming body or its plainly named builder class; document: " + document);
        assertEquals(
                "integer",
                amount.at("/type").asText(),
                "the property must still be described by its Jackson-resolved type; document: " + document);
    }

    @Test
    @DisplayName("control: a getter-backed property still borrows through the floor when the wire name diverges"
            + " from the builder method's implied Java name")
    void renamedWireNameBuilderStillBorrowsThroughTheGetterBackedProperty() {
        // Control for MetadataConstraintSourceCoverageTest
        // #handWrittenBuilderWithGetterJoinsAnXmlMappedConstraintUnderARenamedWireName: this fixture's
        // @Max(10) is directly reflectable, so InputPropertyDescriber#borrowBuilderFieldAttributes (the
        // floor) alone must already publish it under the wire name, without any Validator. It shows
        // the floor keys its built-property lookup on the wire name (BeanPropertyDefinition#getName()),
        // unlike the supplement's own javaName-keyed lookup.
        JsonNode document = document(RenamedWireNameBuilderDto.class);
        JsonNode amountCents = document.at("/properties/amount_cents");
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amountCents.isMissingNode(), "amount_cents must be published: the builder method binds it");
        assertEquals(
                10,
                amountCents.at("/maximum").asInt(-1),
                "the floor must borrow the getter-backed built field's @Max(10) under its wire name"
                        + " \"amount_cents\", regardless of the divergence from the builder method's implied"
                        + " Java name \"amount\"; document: " + document);
        assertTrue(
                amount.isMissingNode(),
                "the property must be published under its wire name \"amount_cents\" only, never the"
                        + " builder method's implied Java name \"amount\"; document: " + document);
    }

    // --- Getter-backed properties borrow for any hand-written builder shape ---

    /**
     * A hand-written builder whose {@code @JsonPOJOBuilder} carries a non-empty
     * {@code withPrefix} ({@code "with"}, never the empty string {@code @Jacksonized} always emits)
     * fails {@link BuilderBorrowDetector}'s shape match on that condition alone. Both built properties
     * carry a getter, so the borrow does not depend on the shape at all.
     */
    @JsonDeserialize(builder = WithPrefixBuilderDto.WithPrefixBuilderDtoBuilder.class)
    static final class WithPrefixBuilderDto {
        @Size(max = 3)
        private final String name;

        @Max(10)
        private final int level;

        private WithPrefixBuilderDto(String name, int level) {
            this.name = name;
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public int getLevel() {
            return level;
        }

        @JsonPOJOBuilder(withPrefix = "with", buildMethodName = "build")
        static final class WithPrefixBuilderDtoBuilder {
            private String name;
            private int level;

            WithPrefixBuilderDtoBuilder withName(String name) {
                this.name = name;
                return this;
            }

            WithPrefixBuilderDtoBuilder withLevel(int level) {
                this.level = level;
                return this;
            }

            WithPrefixBuilderDto build() {
                return new WithPrefixBuilderDto(name, level);
            }
        }
    }

    /**
     * A hand-written builder class named plainly ({@code Factory}), not in the Lombok
     * {@code <Type>Builder} convention, fails {@link BuilderBorrowDetector}'s naming condition alone.
     * Both built properties carry a getter.
     */
    @JsonDeserialize(builder = PlainlyNamedBuilderClassDto.Factory.class)
    static final class PlainlyNamedBuilderClassDto {
        @Size(max = 3)
        private final String name;

        @Max(10)
        private final int level;

        private PlainlyNamedBuilderClassDto(String name, int level) {
            this.name = name;
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public int getLevel() {
            return level;
        }

        @JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
        static final class Factory {
            private String name;
            private int level;

            Factory name(String name) {
                this.name = name;
                return this;
            }

            Factory level(int level) {
                this.level = level;
                return this;
            }

            PlainlyNamedBuilderClassDto build() {
                return new PlainlyNamedBuilderClassDto(name, level);
            }
        }
    }

    /**
     * A hand-written builder whose build method is named {@code create}, not {@code build},
     * fails {@link BuilderBorrowDetector}'s build-method-name condition alone. Both built properties
     * carry a getter.
     */
    @JsonDeserialize(builder = RenamedBuildMethodDto.RenamedBuildMethodDtoBuilder.class)
    static final class RenamedBuildMethodDto {
        @Size(max = 3)
        private final String name;

        @Max(10)
        private final int level;

        private RenamedBuildMethodDto(String name, int level) {
            this.name = name;
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public int getLevel() {
            return level;
        }

        @JsonPOJOBuilder(withPrefix = "", buildMethodName = "create")
        static final class RenamedBuildMethodDtoBuilder {
            private String name;
            private int level;

            RenamedBuildMethodDtoBuilder name(String name) {
                this.name = name;
                return this;
            }

            RenamedBuildMethodDtoBuilder level(int level) {
                this.level = level;
                return this;
            }

            RenamedBuildMethodDto create() {
                return new RenamedBuildMethodDto(name, level);
            }
        }
    }

    @Test
    @DisplayName("a getter-backed property borrows even when @JsonPOJOBuilder's withPrefix is not empty")
    void withPrefixBuilderStillBorrowsThroughTheGetterBackedProperty() {
        JsonNode document = document(WithPrefixBuilderDto.class);
        JsonNode name = document.at("/properties/name");
        JsonNode level = document.at("/properties/level");

        assertEquals(
                3,
                name.at("/maxLength").asInt(-1),
                "name has a getter, so its @Size(max = 3) must be"
                        + " borrowed regardless of the builder's non-empty withPrefix; document: " + document);
        assertEquals(
                10,
                level.at("/maximum").asInt(-1),
                "level has a getter, so its @Max(10) must be borrowed"
                        + " regardless of the builder's non-empty withPrefix; document: " + document);
    }

    @Test
    @DisplayName("a getter-backed property borrows even when the builder class is not named <Type>Builder")
    void plainlyNamedBuilderClassStillBorrowsThroughTheGetterBackedProperty() {
        JsonNode document = document(PlainlyNamedBuilderClassDto.class);
        JsonNode name = document.at("/properties/name");
        JsonNode level = document.at("/properties/level");

        assertEquals(
                3,
                name.at("/maxLength").asInt(-1),
                "name has a getter, so its @Size(max = 3) must be"
                        + " borrowed regardless of the builder class's plain name; document: " + document);
        assertEquals(
                10,
                level.at("/maximum").asInt(-1),
                "level has a getter, so its @Max(10) must be borrowed"
                        + " regardless of the builder class's plain name; document: " + document);
    }

    @Test
    @DisplayName("a getter-backed property borrows even when the build method is not named build")
    void renamedBuildMethodStillBorrowsThroughTheGetterBackedProperty() {
        JsonNode document = document(RenamedBuildMethodDto.class);
        JsonNode name = document.at("/properties/name");
        JsonNode level = document.at("/properties/level");

        assertEquals(
                3,
                name.at("/maxLength").asInt(-1),
                "name has a getter, so its @Size(max = 3) must be"
                        + " borrowed regardless of the builder's renamed build method; document: " + document);
        assertEquals(
                10,
                level.at("/maximum").asInt(-1),
                "level has a getter, so its @Max(10) must be borrowed"
                        + " regardless of the builder's renamed build method; document: " + document);
    }

    @Test
    @DisplayName(
            "a real Lombok @Builder @Jacksonized type's builder method still borrows the" + " built field's constraint")
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
    @DisplayName("a hand-written builder that exactly reproduces the Lombok shape also borrows"
            + " the built field's constraint")
    void handWrittenLombokShapedBuilderAlsoBorrows() {
        JsonNode document = document(HandWrittenLombokShapedDto.class);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must be published: the builder method binds it");
        assertEquals(
                10,
                amount.at("/maximum").asInt(),
                "a hand-written builder that reproduces @Jacksonized's exact shape must resolve the same"
                        + " way a real Lombok builder does; document: " + document);
    }
}
