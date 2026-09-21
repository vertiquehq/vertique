// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
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
 * <p>The first owner ruling ({@code spike/deserializer-driven-schema}) closed that gap by bounding the
 * borrow to the shape it can actually vouch for — see {@link BuilderBorrowDetector} and {@code
 * module.md}'s "Builder borrow assumption": the whole shape {@code @Jacksonized} generates by
 * default, read entirely through {@code java.lang.reflect} over Jackson's own runtime-visible
 * annotations. A hand-written builder reproducing that exact shape resolves too — the ruling tolerates
 * that ({@link #handWrittenLombokShapedBuilderAlsoBorrows()} pins it) — but one that does not (a
 * value-transforming setter behind a plainly named builder class, on a <em>getter-less</em> field) is
 * published by type only, with no borrowed constraint. {@link #lombokBuilderStillBorrowsBuiltFieldConstraint()}
 * pins that a genuine Lombok builder is unaffected.
 *
 * <p><strong>Round 2 (this task's owner ruling).</strong> A package review found the shape-only gate
 * loosens the gate against {@code main} for a hand-written builder whose built type has getters:
 * {@code main}'s field walk published every getter-backed field's constraints regardless of the
 * builder, but the shape gate drops them, so a value {@code main} would have rejected is silently
 * accepted. The owner ruling: borrow from the built type's Jackson-visible property with a getter for
 * <em>any</em> builder, exactly what {@code main} did; keep the Lombok-shape rule only for the
 * getter-less private-field case, where {@code main} published nothing. {@link
 * #withPrefixBuilderStillBorrowsThroughTheGetterBackedProperty()}, {@link
 * #plainlyNamedBuilderClassStillBorrowsThroughTheGetterBackedProperty()}, and {@link
 * #renamedBuildMethodStillBorrowsThroughTheGetterBackedProperty()} each violate exactly one condition
 * of {@link BuilderBorrowDetector}'s Lombok shape while keeping both built properties getter-backed —
 * expected red now, since production code still gates every builder on the Lombok shape regardless of
 * a getter. {@link #handWrittenTransformingBuilderWithGetterBorrowsMainsInheritedBehavior()} pins the
 * value-transforming counterpart: main's field walk borrowed the getter-backed field's constraint even
 * though the builder itself transforms the value, so this is main's own inherited behavior, not a new
 * strictness the round-2 fix introduces.
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
     * The getter-less counterpart to {@link TransformingBuilderDto}: the same hand-written,
     * value-transforming, non-Lombok-shaped builder, but the built field has no accessor at all. Round
     * 2 (this task's owner ruling) draws the line at the getter — {@code main} never published a
     * getter-less field's constraint either, so this shape must stay unresolved before and after the
     * round-2 fix, unlike {@link TransformingBuilderDto}'s getter-backed {@code amount}. {@code
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

    /**
     * Round 2 owner ruling (this task): {@link TransformingBuilderDto#getAmount()} makes {@code amount}
     * a getter-backed property, so it is no longer bounded by {@link BuilderBorrowDetector}'s Lombok
     * shape at all — the borrow must resolve exactly as {@code main}'s field walk did, unconditionally
     * on the builder's shape, even though the builder itself transforms the value before assigning it.
     * This is main's own inherited behavior through the getter, not a new strictness round 2
     * introduces: main's field walk borrowed every getter-backed field's constraints regardless of
     * what any builder method's body did with the value. Expected red now: production code still
     * requires {@link BuilderBorrowDetector#isSoundBorrow} for every builder, getter-backed or not, so
     * {@code amount} is currently published by type only.
     */
    @Test
    @DisplayName("Round 2: a hand-written, value-transforming builder still borrows a getter-backed built field's"
            + " constraint — main's own inherited behavior, not a new strictness")
    void handWrittenTransformingBuilderWithGetterBorrowsMainsInheritedBehavior() {
        JsonNode document = document(TransformingBuilderDto.class);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must still be published: the builder method binds it");
        assertEquals(
                10,
                amount.at("/maximum").asInt(-1),
                "ROUND-2 DECISIVE (expected red now): amount has a getter, so its @Max(10) must be borrowed"
                        + " exactly as main's field walk always did, regardless of the builder's own"
                        + " value-transforming body or its plainly named builder class; document: " + document);
        assertEquals(
                "integer",
                amount.at("/type").asText(),
                "the property must still be described by its Jackson-resolved type; document: " + document);
    }

    // --- Round 2: getter-backed properties borrow for any hand-written builder shape ---

    /**
     * Round 2: a hand-written builder whose {@code @JsonPOJOBuilder} carries a non-empty
     * {@code withPrefix} ({@code "with"}, never the empty string {@code @Jacksonized} always emits)
     * fails {@link BuilderBorrowDetector}'s shape match on that condition alone. Both built properties
     * carry a getter, so under the round-2 owner ruling the borrow no longer depends on the shape at
     * all.
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
     * Round 2: a hand-written builder class named plainly ({@code Factory}), not in the Lombok
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
     * Round 2: a hand-written builder whose build method is named {@code create}, not {@code build},
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
    @DisplayName("Round 2: a getter-backed property borrows even when @JsonPOJOBuilder's withPrefix is not empty")
    void withPrefixBuilderStillBorrowsThroughTheGetterBackedProperty() {
        JsonNode document = document(WithPrefixBuilderDto.class);
        JsonNode name = document.at("/properties/name");
        JsonNode level = document.at("/properties/level");

        assertEquals(
                3,
                name.at("/maxLength").asInt(-1),
                "ROUND-2 DECISIVE (expected red now): name has a getter, so its @Size(max = 3) must be"
                        + " borrowed regardless of the builder's non-empty withPrefix; document: " + document);
        assertEquals(
                10,
                level.at("/maximum").asInt(-1),
                "ROUND-2 DECISIVE (expected red now): level has a getter, so its @Max(10) must be borrowed"
                        + " regardless of the builder's non-empty withPrefix; document: " + document);
    }

    @Test
    @DisplayName(
            "Round 2: a getter-backed property borrows even when the builder class is not named" + " <Type>Builder")
    void plainlyNamedBuilderClassStillBorrowsThroughTheGetterBackedProperty() {
        JsonNode document = document(PlainlyNamedBuilderClassDto.class);
        JsonNode name = document.at("/properties/name");
        JsonNode level = document.at("/properties/level");

        assertEquals(
                3,
                name.at("/maxLength").asInt(-1),
                "ROUND-2 DECISIVE (expected red now): name has a getter, so its @Size(max = 3) must be"
                        + " borrowed regardless of the builder class's plain name; document: " + document);
        assertEquals(
                10,
                level.at("/maximum").asInt(-1),
                "ROUND-2 DECISIVE (expected red now): level has a getter, so its @Max(10) must be borrowed"
                        + " regardless of the builder class's plain name; document: " + document);
    }

    @Test
    @DisplayName("Round 2: a getter-backed property borrows even when the build method is not named build")
    void renamedBuildMethodStillBorrowsThroughTheGetterBackedProperty() {
        JsonNode document = document(RenamedBuildMethodDto.class);
        JsonNode name = document.at("/properties/name");
        JsonNode level = document.at("/properties/level");

        assertEquals(
                3,
                name.at("/maxLength").asInt(-1),
                "ROUND-2 DECISIVE (expected red now): name has a getter, so its @Size(max = 3) must be"
                        + " borrowed regardless of the builder's renamed build method; document: " + document);
        assertEquals(
                10,
                level.at("/maximum").asInt(-1),
                "ROUND-2 DECISIVE (expected red now): level has a getter, so its @Max(10) must be borrowed"
                        + " regardless of the builder's renamed build method; document: " + document);
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
