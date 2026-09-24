// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;
import org.hibernate.validator.constraints.Range;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * rest-023 T006 (D003, TP-001). {@link WalkConstraintSource}'s unscoped-member translation gains the
 * {@code @Positive}/{@code @PositiveOrZero}/{@code @Negative}/{@code @NegativeOrZero} keyword family
 * for every unscoped member kind the floor already translates — a static-factory creator parameter, a
 * constructor creator parameter, a setter, and a builder method — plus the type-use value position the
 * coordinator's P01-gate ruling extends the same widened table to (the walk vocabulary shared between
 * {@link WalkConstraintSource#forUnscopedMember} and {@link WalkConstraintSource#forTypeUse} before the
 * family is added). A scoped field/getter's own rendering (the schema library's own Jakarta module) is
 * pinned as the parity target every other member kind must match once widened.
 *
 * <p>Retained class name (the contract's own TP-001 identifier), extended in scope beyond the
 * static-factory case its own name suggests: every unscoped member kind, plus the type-use overlay and
 * the {@code @Email}/Hibernate-only/non-numeric-misapplication controls (AC-005.2, R16).
 *
 * <p>Per member kind, the floor reads the family from: a creator parameter (static-factory or
 * constructor) — the parameter's own annotation, including a same-named field Jackson's own
 * introspection merges onto it; a builder method — the method's own annotation; a setter — its
 * Jackson-introspected backing field only, never the method or the parameter, since a void setter
 * cannot itself carry a method-level constraint (Bean Validation forbids one on a {@code void} return
 * type, JSR-380 §8.2, enforced at validator bootstrap as Hibernate HV000132 — no method-level
 * void-setter fixture exists in this suite for that reason); and a type-use value position — the
 * value's own annotated type. Two gaps remain undescribed by design: a setter's or builder method's own
 * PARAMETER-level annotation, invisible to the floor ({@link #setterParameterAnnotationIsNotReadByTheFloor},
 * {@link #builderMethodParameterAnnotationIsNotReadByTheFloor}), and a void setter's own method-level
 * constraint, which cannot exist as a fixture at all under Bean Validation's own rule above.
 */
class StaticFactoryConstraintParityTest {

    private static JsonMapperProfile profile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    /** Walk-only (no-validator) document: the floor alone. */
    private static JsonNode walkDocument(Type type) {
        return SchemaAssertions.assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
    }

    /** Validator-supplemented document: the floor plus {@link MetadataConstraintSource}. */
    private static JsonNode metadataDocument(Type type, Validator validator) {
        return SchemaAssertions.assertCanonicalForm(AnnotationJsonSchemaGenerator.forInputProfile(profile(), validator)
                .generateCanonical(type));
    }

    /**
     * Generates {@code type}'s input document in both constraint-source modes and asserts {@code
     * property}'s own schema carries {@code keyword: expectedValue} in each — the floor alone, and the
     * floor plus the metadata supplement.
     */
    private static void assertKeywordValueBothModes(Class<?> type, String property, String keyword, int expectedValue) {
        Validator validator = MetadataTestValidators.plain();
        JsonNode withoutValidator = walkDocument(type);
        JsonNode withValidator = metadataDocument(type, validator);

        JsonNode walkValue = withoutValidator.at("/properties/" + property + "/" + keyword);
        JsonNode metadataValue = withValidator.at("/properties/" + property + "/" + keyword);

        assertFalse(
                walkValue.isMissingNode(),
                "walk-only mode must render " + keyword + ": " + expectedValue + " for " + type.getSimpleName() + "."
                        + property + "; document: " + withoutValidator);
        assertEquals(
                expectedValue,
                walkValue.asInt(Integer.MIN_VALUE),
                "walk-only mode's " + keyword + " must be exactly " + expectedValue + " for " + type.getSimpleName()
                        + "." + property);
        assertFalse(
                metadataValue.isMissingNode(),
                "validator-supplemented mode must render " + keyword + ": " + expectedValue + " for "
                        + type.getSimpleName() + "." + property + "; document: " + withValidator);
        assertEquals(
                expectedValue,
                metadataValue.asInt(Integer.MIN_VALUE),
                "validator-supplemented mode's " + keyword + " must be exactly " + expectedValue + " for "
                        + type.getSimpleName() + "." + property);
    }

    /**
     * Generates {@code type}'s input document in both constraint-source modes and asserts {@code
     * property}'s own schema carries {@code keyword: 0} in each — the floor alone, and the floor plus
     * the metadata supplement.
     */
    private static void assertFamilyKeywordBothModes(Class<?> type, String property, String keyword) {
        assertKeywordValueBothModes(type, property, keyword, 0);
    }

    // --- C3p: a static-factory creator parameter, every family member ---

    @Test
    @DisplayName("C3p: a static-factory creator parameter renders the Positive/Negative family exactly as the"
            + " floor's other keywords already do")
    void staticFactoryCreatorParameterRendersThePositiveFamily() {
        assertFamilyKeywordBothModes(StaticFactoryPositiveDto.class, "amount", "exclusiveMinimum");
        assertFamilyKeywordBothModes(StaticFactoryPositiveOrZeroDto.class, "amount", "minimum");
        assertFamilyKeywordBothModes(StaticFactoryNegativeDto.class, "amount", "exclusiveMaximum");
        assertFamilyKeywordBothModes(StaticFactoryNegativeOrZeroDto.class, "amount", "maximum");
    }

    // --- C3c: a constructor creator parameter ---

    @Test
    @DisplayName("C3c: a constructor creator parameter renders exclusiveMinimum: 0 in walk-only mode (red until"
            + " the floor is widened); the validator-supplemented mode already renders it today"
            + " (characterization control — Bean Validation exposes a constrained constructor's own"
            + " parameters), asserted here as parity with the floor's own rendering")
    void constructorCreatorParameterRendersThePositiveFamily() {
        assertFamilyKeywordBothModes(ConstructorPositiveDto.class, "amount", "exclusiveMinimum");
    }

    // --- setter ---

    @Test
    @DisplayName("Characterization: a setter backed by an annotated field renders the Positive/Negative"
            + " family through the scoped borrow — Bean Validation forbids a method-level constraint on a"
            + " void method (JSR-380 §8.2, Hibernate HV000132), so the backing field is the only legal"
            + " placement for a void setter's own constraint besides the invisible parameter; already"
            + " green before this task, since InputPropertyDescriber#borrowFieldAttributes joins the"
            + " setter to its Jackson-introspected field and the schema library's own always-on Jakarta"
            + " module renders it, independent of WalkConstraintSource's own widening")
    void setterBackedByAnAnnotatedFieldRendersThePositiveFamilyThroughTheScopedBorrow() {
        assertFamilyKeywordBothModes(SetterFieldPositiveDto.class, "amount", "exclusiveMinimum");
    }

    @Test
    @DisplayName("Characterization: a setter's own PARAMETER annotation (@Positive or @Max) is invisible to"
            + " the floor in both constraint-source modes — AnnotatedMethod#getAnnotation never consults"
            + " parameter annotations, and Bean Validation's own property-descriptor join reflects the"
            + " built field directly, never the setter parameter; pre-existing gap, unchanged by this"
            + " task, affects every walk keyword (documented for T008's Known gaps)")
    void setterParameterAnnotationIsNotReadByTheFloor() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode withoutValidator = walkDocument(SetterPositiveParameterOnlyDto.class);
        JsonNode withValidator = metadataDocument(SetterPositiveParameterOnlyDto.class, validator);

        JsonNode walkAmount = withoutValidator.at("/properties/amount");
        JsonNode metadataAmount = withValidator.at("/properties/amount");

        assertFalse(
                walkAmount.has("exclusiveMinimum"),
                "walk-only mode must not render exclusiveMinimum for a setter PARAMETER-level @Positive;"
                        + " document: " + withoutValidator);
        assertFalse(
                walkAmount.has("maximum"),
                "walk-only mode must not render maximum for a setter PARAMETER-level @Max(5); document: "
                        + withoutValidator);
        assertFalse(
                metadataAmount.has("exclusiveMinimum"),
                "validator-supplemented mode must not render exclusiveMinimum for a setter PARAMETER-level"
                        + " @Positive either; document: " + withValidator);
        assertFalse(
                metadataAmount.has("maximum"),
                "validator-supplemented mode must not render maximum for a setter PARAMETER-level @Max(5)"
                        + " either; document: " + withValidator);
    }

    // --- builder method ---

    @Test
    @DisplayName("A @JsonPOJOBuilder builder's own METHOD (not its parameter) carries @Positive — the shape"
            + " the floor actually reads; renders exclusiveMinimum: 0 in both constraint-source modes,"
            + " matching the floor's own pre-existing method-level @Max idiom for a builder method"
            + " (discriminator below)")
    void builderMethodRendersThePositiveFamily() {
        assertFamilyKeywordBothModes(BuilderPositiveDto.class, "amount", "exclusiveMinimum");

        // Discriminator: a method-level @Max(5) on a builder method already renders today, proving the
        // floor's merged-annotation read of a builder method is not something new invented for this
        // family.
        assertKeywordValueBothModes(BuilderMaxControlDto.class, "amount", "maximum", 5);
    }

    @Test
    @DisplayName("Characterization: a builder method's own PARAMETER annotation (@Positive or @Max) is"
            + " invisible to the floor in both constraint-source modes — the same AnnotatedMethod gap as"
            + " the setter's own parameter; pre-existing, unchanged by this task, documented for T008's"
            + " Known gaps")
    void builderMethodParameterAnnotationIsNotReadByTheFloor() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode withoutValidator = walkDocument(BuilderPositiveParameterOnlyDto.class);
        JsonNode withValidator = metadataDocument(BuilderPositiveParameterOnlyDto.class, validator);

        JsonNode walkAmount = withoutValidator.at("/properties/amount");
        JsonNode metadataAmount = withValidator.at("/properties/amount");

        assertFalse(
                walkAmount.has("exclusiveMinimum"),
                "walk-only mode must not render exclusiveMinimum for a builder-method PARAMETER-level"
                        + " @Positive; document: " + withoutValidator);
        assertFalse(
                walkAmount.has("maximum"),
                "walk-only mode must not render maximum for a builder-method PARAMETER-level @Max(5);" + " document: "
                        + withoutValidator);
        assertFalse(
                metadataAmount.has("exclusiveMinimum"),
                "validator-supplemented mode must not render exclusiveMinimum for a builder-method"
                        + " PARAMETER-level @Positive either; document: " + withValidator);
        assertFalse(
                metadataAmount.has("maximum"),
                "validator-supplemented mode must not render maximum for a builder-method PARAMETER-level"
                        + " @Max(5) either; document: " + withValidator);
    }

    // --- Parity control: a scoped field/getter already renders the family, byte-identical either way ---

    @Test
    @DisplayName("Parity control: a scoped field/getter already renders the Positive family identically with or"
            + " without a validator — the exact node text every other member kind must match once widened")
    void scopedFieldAndGetterRenderTheFamilyIdenticallyToTheFloor() {
        Validator validator = MetadataTestValidators.plain();
        JsonNode withoutValidator = walkDocument(ScopedPositiveDto.class);
        JsonNode withValidator = metadataDocument(ScopedPositiveDto.class, validator);

        JsonNode walkAmount = withoutValidator.at("/properties/amount");
        JsonNode metadataAmount = withValidator.at("/properties/amount");

        assertEquals(
                walkAmount.toString(),
                metadataAmount.toString(),
                "a scoped field/getter must render identically with or without a validator; without: "
                        + withoutValidator + "; with: " + withValidator);
        assertEquals(
                "{\"exclusiveMinimum\":0,\"type\":\"integer\"}",
                walkAmount.toString(),
                "sanity: the schema library's own Jakarta module must already render this exact text for a"
                        + " scoped @Positive int; document: " + withoutValidator);
    }

    // --- Type-use value position (coordinator ruling, P01 gate) ---

    @Test
    @DisplayName("Coordinator ruling: a Map<String, @Positive Integer> value position renders exclusiveMinimum: 0"
            + " on additionalProperties — red today, since forTypeUse lacks the family")
    void typeUseValueRendersThePositiveFamily() {
        JsonNode document = walkDocument(TypeUsePositiveFamilyHolder.class);
        JsonNode additionalProperties = document.at("/properties/counts/additionalProperties");

        assertEquals(
                "{\"exclusiveMinimum\":0,\"type\":\"integer\"}",
                additionalProperties.toString(),
                "counts.additionalProperties must describe the type-use-constrained Integer schema, matching"
                        + " the scoped-member parity control's own exact text; document: " + document);
    }

    // --- Security review MEDIUM: the family must not loosen a stricter declared bound ---
    //
    // WalkConstraintSource#translateWalkVocabulary runs the family's own unconditional `put` after
    // @DecimalMin/@DecimalMax, so a member carrying both a stricter declared bound and a family
    // annotation on the same keyword must keep the declared bound, not the family's own zero.

    @Test
    @DisplayName("Security review MEDIUM: @Positive must not loosen a stricter declared @DecimalMin"
            + " exclusiveMinimum — the family's own unconditional put must not overwrite a stricter"
            + " already-translated bound on the same keyword")
    void positiveDoesNotLoosenAStricterExclusiveMinimum() {
        assertKeywordValueBothModes(DecimalMinExclusivePositiveDto.class, "amount", "exclusiveMinimum", 10);
    }

    @Test
    @DisplayName("Security review MEDIUM: @PositiveOrZero must not loosen a stricter declared @DecimalMin"
            + " minimum — the family's own unconditional put must not overwrite a stricter"
            + " already-translated bound on the same keyword")
    void positiveOrZeroDoesNotLoosenAStricterInclusiveMinimum() {
        assertKeywordValueBothModes(DecimalMinStricterThanPositiveOrZeroDto.class, "amount", "minimum", 10);
    }

    @Test
    @DisplayName("Security review MEDIUM: @Negative must not loosen a stricter declared @DecimalMax"
            + " exclusiveMaximum — the family's own unconditional put must not overwrite a stricter"
            + " already-translated bound on the same keyword")
    void negativeDoesNotLoosenAStricterExclusiveMaximum() {
        assertKeywordValueBothModes(DecimalMaxExclusiveNegativeDto.class, "amount", "exclusiveMaximum", -10);
    }

    @Test
    @DisplayName("Security review MEDIUM: @NegativeOrZero must not loosen a stricter declared @DecimalMax"
            + " maximum — the family's own unconditional put must not overwrite a stricter"
            + " already-translated bound on the same keyword")
    void negativeOrZeroDoesNotLoosenAStricterInclusiveMaximum() {
        assertKeywordValueBothModes(DecimalMaxStricterThanNegativeOrZeroDto.class, "amount", "maximum", -10);
    }

    @Test
    @DisplayName("Security review MEDIUM: @PositiveOrZero must not loosen a stricter declared @Min"
            + " minimum — mirrors the @DecimalMin case above for existingAsBigDecimal's own Long branch"
            + " (a plain @Min/@Max value), not just its BigDecimal branch")
    void positiveOrZeroDoesNotLoosenAStricterMin() {
        assertKeywordValueBothModes(MinStricterThanPositiveOrZeroDto.class, "amount", "minimum", 10);
    }

    @Test
    @DisplayName("Security review MEDIUM: @NegativeOrZero must not loosen a stricter declared @Max"
            + " maximum — mirrors the @DecimalMax case above for existingAsBigDecimal's own Long branch"
            + " (a plain @Min/@Max value), not just its BigDecimal branch")
    void negativeOrZeroDoesNotLoosenAStricterMax() {
        assertKeywordValueBothModes(MaxStricterThanNegativeOrZeroDto.class, "amount", "maximum", -10);
    }

    @Test
    @DisplayName("Discriminator: the family must still tighten a looser declared bound in either direction —"
            + " proves the fix above only blocks loosening, never legitimate tightening")
    void theFamilyStillTightensALooserDeclaredBound() {
        // Same keyword ("minimum") both ways: the family's own zero is stricter than the declared -3, so
        // it must still win here, unlike the exclusiveMinimum/minimum cases above where the declared
        // bound is the stricter one.
        assertKeywordValueBothModes(DecimalMinLooserThanPositiveOrZeroDto.class, "amount", "minimum", 0);

        // Different keywords ("minimum" from @Min, "exclusiveMinimum" from @Positive): both must be kept,
        // since they never collide on the same JSON Schema keyword.
        Validator validator = MetadataTestValidators.plain();
        JsonNode withoutValidator = walkDocument(MinAndPositiveDifferentKeywordsDto.class);
        JsonNode withValidator = metadataDocument(MinAndPositiveDifferentKeywordsDto.class, validator);

        JsonNode walkAmount = withoutValidator.at("/properties/amount");
        JsonNode metadataAmount = withValidator.at("/properties/amount");

        assertEquals(
                -3,
                walkAmount.at("/minimum").asInt(Integer.MIN_VALUE),
                "walk-only mode must keep the declared @Min(-3) alongside @Positive's own"
                        + " exclusiveMinimum, a different keyword; document: " + withoutValidator);
        assertEquals(
                0,
                walkAmount.at("/exclusiveMinimum").asInt(Integer.MIN_VALUE),
                "walk-only mode must still render @Positive's own exclusiveMinimum: 0; document: " + withoutValidator);
        assertEquals(
                -3,
                metadataAmount.at("/minimum").asInt(Integer.MIN_VALUE),
                "validator-supplemented mode must keep the declared @Min(-3) alongside @Positive's own"
                        + " exclusiveMinimum, a different keyword; document: " + withValidator);
        assertEquals(
                0,
                metadataAmount.at("/exclusiveMinimum").asInt(Integer.MIN_VALUE),
                "validator-supplemented mode must still render @Positive's own exclusiveMinimum: 0;" + " document: "
                        + withValidator);
    }

    // --- Controls ---

    @Test
    @DisplayName("R16: @Email at a static-factory parameter is not translated by the floor (a format constraint,"
            + " excluded from this keyword family — stays undescribed, green before and after this task)")
    void emailIsNotTranslatedByTheFloor() {
        JsonNode document = walkDocument(EmailStaticFactoryDto.class);
        JsonNode email = document.at("/properties/email");

        assertFalse(email.isMissingNode(), "email must be published: the static-factory parameter binds it");
        assertFalse(
                email.has("format"),
                "the floor must never translate @Email into a format keyword; document: " + document);
        assertFalse(
                email.has("pattern"),
                "the floor must never translate @Email into a pattern keyword; document: " + document);
    }

    @Test
    @DisplayName("A Hibernate-only @Range at a static-factory parameter has no metadata source and stays"
            + " undescribed by the floor (documented gap, excluded from this task's own scope)")
    void hibernateOnlyRangeIsNotTranslatedByTheFloor() {
        JsonNode document = walkDocument(RangeStaticFactoryDto.class);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must be published: the static-factory parameter binds it");
        assertFalse(amount.has("minimum"), "the floor must never translate @Range; document: " + document);
        assertFalse(amount.has("maximum"), "the floor must never translate @Range; document: " + document);
        assertFalse(amount.has("exclusiveMinimum"), "the floor must never translate @Range; document: " + document);
        assertFalse(amount.has("exclusiveMaximum"), "the floor must never translate @Range; document: " + document);
    }

    /**
     * Pre-existing, inert rendering: the floor's translation is unconditional on the annotated position's
     * declared type, so {@code @Positive} on a {@code String} renders {@code exclusiveMinimum: 0} exactly
     * as {@code @Max}/{@code @Min} already do on the same shape — a JSON Schema numeric keyword simply has
     * no effect on a non-number instance. This is a hygiene follow-up for the module owner (should the
     * floor someday validate the annotated type before translating), not this task's own scope.
     */
    @Test
    @DisplayName("@Positive on a non-numeric static-factory parameter renders exclusiveMinimum: 0 exactly as"
            + " @Max already does on the same shape — pre-existing, inert (a JSON Schema numeric keyword"
            + " has no effect on a non-number instance); a hygiene follow-up for the owner, not this"
            + " task's own scope")
    void positiveOnANonNumericMemberRendersLikeTheExistingBoundKeywords() {
        JsonNode positiveDocument = walkDocument(PositiveOnStringStaticFactoryDto.class);
        JsonNode positiveAmount = positiveDocument.at("/properties/amount");

        assertFalse(positiveAmount.isMissingNode(), "amount must be published: the static-factory parameter binds it");
        assertEquals(
                "{\"exclusiveMinimum\":0,\"type\":\"string\"}",
                positiveAmount.toString(),
                "@Positive on a String static-factory parameter must render exactly as the existing bound"
                        + " keywords already do (translation is unconditional on the annotated position's"
                        + " declared type); document: " + positiveDocument);

        // Discriminator: @Max(5) on the same kind of String parameter already renders, unconditionally,
        // today — proving the family merely matches pre-existing floor behaviour rather than introducing
        // a new non-numeric rendering path.
        JsonNode maxDocument = walkDocument(MaxOnStringStaticFactoryDto.class);
        JsonNode maxAmount = maxDocument.at("/properties/amount");

        assertEquals(
                "{\"maximum\":5,\"type\":\"string\"}",
                maxAmount.toString(),
                "@Max(5) on a String static-factory parameter already renders unconditionally today,"
                        + " matching @Positive's own inert rendering above; document: " + maxDocument);
    }

    // --- Fixtures: C3p (static-factory creator parameter), one per family member ---

    static final class StaticFactoryPositiveDto {
        private final int amount;

        private StaticFactoryPositiveDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static StaticFactoryPositiveDto of(@JsonProperty("amount") @Positive int amount) {
            return new StaticFactoryPositiveDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    static final class StaticFactoryPositiveOrZeroDto {
        private final int amount;

        private StaticFactoryPositiveOrZeroDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static StaticFactoryPositiveOrZeroDto of(@JsonProperty("amount") @PositiveOrZero int amount) {
            return new StaticFactoryPositiveOrZeroDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    static final class StaticFactoryNegativeDto {
        private final int amount;

        private StaticFactoryNegativeDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static StaticFactoryNegativeDto of(@JsonProperty("amount") @Negative int amount) {
            return new StaticFactoryNegativeDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    static final class StaticFactoryNegativeOrZeroDto {
        private final int amount;

        private StaticFactoryNegativeOrZeroDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static StaticFactoryNegativeOrZeroDto of(@JsonProperty("amount") @NegativeOrZero int amount) {
            return new StaticFactoryNegativeOrZeroDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    // --- Fixture: C3c (constructor creator parameter) ---

    static final class ConstructorPositiveDto {
        private final int amount;

        @JsonCreator
        ConstructorPositiveDto(@JsonProperty("amount") @Positive int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }
    }

    // --- Fixture: setter ---

    /**
     * A setter backed by an annotated field — never a method-level constraint, which is BV-illegal on a
     * void setter (Bean Validation forbids a constraint annotation on a {@code void} return type,
     * JSR-380 §8.2, enforced at validator bootstrap as Hibernate HV000132; no such fixture can exist).
     * {@code amount}'s own {@code @Positive} sits on the backing field, joined through {@link
     * InputPropertyDescriber#borrowFieldAttributes}'s own scope-attribute borrow: Jackson's introspection
     * resolves this setter's backing field via {@code BeanPropertyDefinition#getField()}, and the borrow
     * routes the field's constraint through the schema library's own always-on Jakarta module — the same
     * scoped path {@link ScopedPositiveDto} exercises directly — rendering this family already, before
     * and independent of {@link WalkConstraintSource}'s own widening. This is therefore a
     * characterization (expected green in both constraint-source modes), documenting the one legal
     * placement for a void setter's own constraint besides the invisible parameter ({@link
     * #setterParameterAnnotationIsNotReadByTheFloor}).
     */
    static final class SetterFieldPositiveDto {

        @Positive
        private int amount;

        public void setAmount(int amount) {
            this.amount = amount;
        }
    }

    /**
     * Both {@code @Positive} and {@code @Max(5)} sit on the setter's own PARAMETER, never the field — the
     * shape {@link #setterParameterAnnotationIsNotReadByTheFloor} measures as invisible to the floor,
     * unlike {@link SetterFieldPositiveDto}'s own backing-field placement (the only legal placement for a
     * void setter's own constraint, since Bean Validation forbids a method-level constraint on a void
     * method, JSR-380 §8.2, Hibernate HV000132).
     */
    static final class SetterPositiveParameterOnlyDto {

        private int amount;

        @JsonSetter("amount")
        public void setAmount(@Positive @Max(5) int amount) {
            this.amount = amount;
        }
    }

    // --- Fixture: builder method ---

    /**
     * The builder method's own METHOD carries {@code @Positive} (never the built field, and never the
     * parameter): a getter-backed built field is also reachable through {@link InputPropertyDescriber
     * #borrowBuilderFieldAttributes}'s own unconditional borrow, which already routes through the
     * schema library's own always-on Jakarta module and would render this family regardless of {@link
     * WalkConstraintSource}'s own gap — masking exactly the seam this test exists to exercise. The built
     * field here carries no annotation of its own, so the borrow finds nothing to add; {@link
     * WalkConstraintSource#forUnscopedMember}'s own reading of the builder method's merged annotation
     * map is the only path that can render this family at all. The annotation sits on the method itself
     * because {@code AnnotatedMethod#getAnnotation(...)} never consults parameter annotations (unlike a
     * setter, a builder method is legal to annotate directly — it returns the builder, not {@code void},
     * so Bean Validation's method-level restriction does not apply here);
     * {@link #builderMethodParameterAnnotationIsNotReadByTheFloor} pins
     * the parameter-level shape as a separate, still-red characterization.
     */
    @JsonDeserialize(builder = BuilderPositiveDto.Builder.class)
    static final class BuilderPositiveDto {

        private final int amount;

        private BuilderPositiveDto(int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }

        @JsonPOJOBuilder(withPrefix = "with")
        static final class Builder {
            private int amount;

            @Positive
            public Builder withAmount(int amount) {
                this.amount = amount;
                return this;
            }

            public BuilderPositiveDto build() {
                return new BuilderPositiveDto(amount);
            }
        }
    }

    /**
     * Discriminator sibling of {@link BuilderPositiveDto}: a method-level {@code @Max(5)} on a builder
     * method, proving the floor already reads a builder method's own annotation for its pre-existing
     * keywords — {@code @Positive} above merely extends the family to that same, already-working read
     * path.
     */
    @JsonDeserialize(builder = BuilderMaxControlDto.Builder.class)
    static final class BuilderMaxControlDto {

        private final int amount;

        private BuilderMaxControlDto(int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }

        @JsonPOJOBuilder(withPrefix = "with")
        static final class Builder {
            private int amount;

            @Max(5)
            public Builder withAmount(int amount) {
                this.amount = amount;
                return this;
            }

            public BuilderMaxControlDto build() {
                return new BuilderMaxControlDto(amount);
            }
        }
    }

    /**
     * Both {@code @Positive} and {@code @Max(5)} sit on the builder method's own PARAMETER, never the
     * method — the shape {@link #builderMethodParameterAnnotationIsNotReadByTheFloor} measures as
     * invisible to the floor, unlike {@link BuilderPositiveDto}'s and {@link BuilderMaxControlDto}'s own
     * method-level placement.
     */
    @JsonDeserialize(builder = BuilderPositiveParameterOnlyDto.Builder.class)
    static final class BuilderPositiveParameterOnlyDto {

        private final int amount;

        private BuilderPositiveParameterOnlyDto(int amount) {
            this.amount = amount;
        }

        public int getAmount() {
            return amount;
        }

        @JsonPOJOBuilder(withPrefix = "with")
        static final class Builder {
            private int amount;

            public Builder withAmount(@Positive @Max(5) int amount) {
                this.amount = amount;
                return this;
            }

            public BuilderPositiveParameterOnlyDto build() {
                return new BuilderPositiveParameterOnlyDto(amount);
            }
        }
    }

    // --- Fixture: scoped field/getter parity control ---

    static final class ScopedPositiveDto {

        @Positive
        private int amount;

        public int getAmount() {
            return amount;
        }
    }

    // --- Fixture: type-use value position ---

    static final class TypeUsePositiveFamilyHolder {

        /** The type-use-constrained map value under test. */
        public Map<String, @Positive Integer> counts;
    }

    // --- Fixtures: security review — the family must not loosen a stricter declared bound ---

    static final class DecimalMinExclusivePositiveDto {
        private final int amount;

        private DecimalMinExclusivePositiveDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static DecimalMinExclusivePositiveDto of(
                @JsonProperty("amount") @DecimalMin(value = "10", inclusive = false) @Positive int amount) {
            return new DecimalMinExclusivePositiveDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    static final class DecimalMinStricterThanPositiveOrZeroDto {
        private final int amount;

        private DecimalMinStricterThanPositiveOrZeroDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static DecimalMinStricterThanPositiveOrZeroDto of(
                @JsonProperty("amount") @DecimalMin("10") @PositiveOrZero int amount) {
            return new DecimalMinStricterThanPositiveOrZeroDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    static final class DecimalMaxExclusiveNegativeDto {
        private final int amount;

        private DecimalMaxExclusiveNegativeDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static DecimalMaxExclusiveNegativeDto of(
                @JsonProperty("amount") @DecimalMax(value = "-10", inclusive = false) @Negative int amount) {
            return new DecimalMaxExclusiveNegativeDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    static final class DecimalMaxStricterThanNegativeOrZeroDto {
        private final int amount;

        private DecimalMaxStricterThanNegativeOrZeroDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static DecimalMaxStricterThanNegativeOrZeroDto of(
                @JsonProperty("amount") @DecimalMax("-10") @NegativeOrZero int amount) {
            return new DecimalMaxStricterThanNegativeOrZeroDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    /**
     * Mirrors {@link DecimalMinStricterThanPositiveOrZeroDto} for {@code existingAsBigDecimal}'s own
     * {@link Long} branch: the declared {@code @Min(10)} — not {@code @DecimalMin} — is the stricter
     * already-translated bound on the same keyword ({@code minimum}).
     */
    static final class MinStricterThanPositiveOrZeroDto {
        private final int amount;

        private MinStricterThanPositiveOrZeroDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static MinStricterThanPositiveOrZeroDto of(@JsonProperty("amount") @Min(10) @PositiveOrZero int amount) {
            return new MinStricterThanPositiveOrZeroDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    /**
     * Mirrors {@link DecimalMaxStricterThanNegativeOrZeroDto} for {@code existingAsBigDecimal}'s own
     * {@link Long} branch: the declared {@code @Max(-10)} — not {@code @DecimalMax} — is the stricter
     * already-translated bound on the same keyword ({@code maximum}).
     */
    static final class MaxStricterThanNegativeOrZeroDto {
        private final int amount;

        private MaxStricterThanNegativeOrZeroDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static MaxStricterThanNegativeOrZeroDto of(@JsonProperty("amount") @Max(-10) @NegativeOrZero int amount) {
            return new MaxStricterThanNegativeOrZeroDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    /**
     * Discriminator (looser direction): the declared {@code @DecimalMin("-3")} is looser than
     * {@code @PositiveOrZero}'s own zero on the same keyword ({@code minimum}), so the family must still
     * win here — the mirror image of {@link DecimalMinStricterThanPositiveOrZeroDto}, where the declared
     * bound is the stricter one and must win instead.
     */
    static final class DecimalMinLooserThanPositiveOrZeroDto {
        private final int amount;

        private DecimalMinLooserThanPositiveOrZeroDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static DecimalMinLooserThanPositiveOrZeroDto of(
                @JsonProperty("amount") @DecimalMin("-3") @PositiveOrZero int amount) {
            return new DecimalMinLooserThanPositiveOrZeroDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    /**
     * {@code @Min(-3)} and {@code @Positive} translate to two distinct keywords ({@code minimum} and
     * {@code exclusiveMinimum}) — they never collide on the same JSON Schema keyword, so both must be
     * kept, unlike the same-keyword collisions above.
     */
    static final class MinAndPositiveDifferentKeywordsDto {
        private final int amount;

        private MinAndPositiveDifferentKeywordsDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static MinAndPositiveDifferentKeywordsDto of(@JsonProperty("amount") @Min(-3) @Positive int amount) {
            return new MinAndPositiveDifferentKeywordsDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    // --- Fixture: @Email control ---

    static final class EmailStaticFactoryDto {
        private final String email;

        private EmailStaticFactoryDto(String email) {
            this.email = email;
        }

        @JsonCreator
        static EmailStaticFactoryDto of(@JsonProperty("email") @Email String email) {
            return new EmailStaticFactoryDto(email);
        }

        public String getEmail() {
            return email;
        }
    }

    // --- Fixture: Hibernate-only @Range control ---

    static final class RangeStaticFactoryDto {
        private final int amount;

        private RangeStaticFactoryDto(int amount) {
            this.amount = amount;
        }

        @JsonCreator
        static RangeStaticFactoryDto of(@JsonProperty("amount") @Range(min = 1, max = 10) int amount) {
            return new RangeStaticFactoryDto(amount);
        }

        public int getAmount() {
            return amount;
        }
    }

    // --- Fixture: @Positive misapplied to a non-numeric member ---

    static final class PositiveOnStringStaticFactoryDto {
        private final String amount;

        private PositiveOnStringStaticFactoryDto(String amount) {
            this.amount = amount;
        }

        @JsonCreator
        static PositiveOnStringStaticFactoryDto of(@JsonProperty("amount") @Positive String amount) {
            return new PositiveOnStringStaticFactoryDto(amount);
        }

        public String getAmount() {
            return amount;
        }
    }

    // --- Fixture: @Max discriminator, misapplied to a non-numeric member ---

    /**
     * Same shape as {@link PositiveOnStringStaticFactoryDto}, with the existing {@code @Max} keyword in
     * place of {@code @Positive} — the discriminator proving that keyword's own unconditional, inert
     * rendering on a non-numeric parameter predates this family and is not something new.
     */
    static final class MaxOnStringStaticFactoryDto {
        private final String amount;

        private MaxOnStringStaticFactoryDto(String amount) {
            this.amount = amount;
        }

        @JsonCreator
        static MaxOnStringStaticFactoryDto of(@JsonProperty("amount") @Max(5) String amount) {
            return new MaxOnStringStaticFactoryDto(amount);
        }

        public String getAmount() {
            return amount;
        }
    }
}
