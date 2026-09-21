// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code InputPropertyDescriber#backingField} used to join a creator parameter to a field two ways: by
 * the parameter's wire name (Jackson's own statement that field and parameter are one logical
 * property — this stays), and, when the declaring class was compiled with {@code javac -parameters},
 * by the parameter's <em>compiled Java name</em> coinciding with an unrelated field's own name — a
 * coincidence the join could not tell apart from a real backing relationship, because doing so would
 * mean reading the constructor body. This class pins the removal of the second join and the survival
 * of the two joins design proof CR1c always meant to stay exact: the wire-name join (unaffected) and
 * the record-component join, where component {@code i} is parameter {@code i} by language definition,
 * never a name coincidence.
 *
 * <p>{@link #transformingConstructorFixture()}'s parameter needs its compiled Java name present at
 * runtime for the removed join to ever have had anything to bite on — the module's {@code
 * default-testCompile} execution in {@code pom.xml} compiles this file's test sources with {@code
 * -parameters} for exactly that reason. Main sources are compiled without it, unaffected.
 */
class CreatorParameterFieldJoinTest {

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
     * A transforming constructor: the parameter's compiled Java name ({@code "amount"}) coincides with
     * an unrelated field's own name, but the parameter's own wire name ({@code "amount_cents"}) does
     * not, and the constructor divides the incoming value before storing it. Before the fix, the
     * removed candidate joined this parameter to the {@code "amount"} field on the compiled-name
     * coincidence alone and borrowed its {@code @Max(10)}, publishing {@code maximum: 10} on {@code
     * amount_cents} and rejecting the valid body {@code {"amount_cents": 500}} — a body the
     * constructor happily turns into {@code amount = 5}.
     */
    static final class TransformingConstructorDto {
        // No getter on purpose: a public getAmount() would let Jackson pair the private field with a
        // getter-implied property "amount" of its own — a second, genuinely field-backed property this
        // fixture does not intend to exercise. Leaving the field reachable only through the (renamed)
        // creator parameter keeps the fixture to the one join under test.
        @Max(10)
        private final int amount;

        @JsonCreator
        TransformingConstructorDto(@JsonProperty("amount_cents") int amount) {
            this.amount = amount / 100;
        }
    }

    @Test
    @DisplayName("a transforming creator parameter's wire name is never constrained by an unrelated field of the same"
            + " compiled parameter name")
    void transformingConstructorParameterIsNotConstrainedByCoincidentalFieldName() {
        JsonNode document = document(TransformingConstructorDto.class);
        JsonNode amountCents = document.at("/properties/amount_cents");

        // Either outcome is sound (the module's own excluded-unjoinable-parameter rule may drop it
        // entirely, since it has no field, no accessor-derived property, and no constraint of its own);
        // what must never happen is a borrowed maximum on the wire name the constructor transforms.
        assertTrue(
                amountCents.isMissingNode() || amountCents.at("/maximum").isMissingNode(),
                "amount_cents must carry no borrowed maximum: the field named \"amount\" is a different"
                        + " logical property joined only by the parameter's compiled Java name, which the"
                        + " walk must never use as a join key; document: " + document);
        assertTrue(
                document.at("/properties/amount").isMissingNode(),
                "the unrelated field's own Java name must never appear as a published property on its own;"
                        + " document: " + document);
    }

    /**
     * A creator parameter whose wire name genuinely matches its backing field's own Java name: the
     * join this class does not touch. Kept beside the transforming fixture so a future change to
     * {@code backingField} cannot silently widen the wire-name candidate into a second, unsound one.
     */
    static final class GenuineWireNameJoinDto {
        @Size(min = 2, max = 5)
        private final String code;

        @JsonCreator
        GenuineWireNameJoinDto(@JsonProperty("code") String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    @Test
    @DisplayName(
            "a creator parameter whose wire name matches its field's own name still borrows the field's constraint")
    void wireNameMatchingFieldStillJoins() {
        JsonNode document = document(GenuineWireNameJoinDto.class);
        JsonNode code = document.at("/properties/code");

        assertFalse(code.isMissingNode(), "code must be published: it is a bound creator parameter");
        assertEquals(
                2, code.at("/minLength").asInt(), "the genuine wire-name join must still borrow the field's @Size");
        assertEquals(
                5, code.at("/maxLength").asInt(), "the genuine wire-name join must still borrow the field's @Size");
    }

    /**
     * A record whose sole component carries its own constraint: component {@code i} is parameter
     * {@code i} by language definition, so this join is exact and untouched by the removal above — it
     * never depended on a compiled parameter name or a field-name coincidence.
     */
    record ConstrainedRecordComponent(@Max(10) int amountCents) {}

    @Test
    @DisplayName(
            "a record component's own constraint still publishes after the compiled-parameter-name join is removed")
    void recordComponentConstraintStillPublishes() {
        JsonNode document = document(ConstrainedRecordComponent.class);
        JsonNode amountCents = document.at("/properties/amountCents");

        assertFalse(amountCents.isMissingNode(), "amountCents must be published: it is the record's sole component");
        assertEquals(
                10,
                amountCents.at("/maximum").asInt(),
                "a record component's own constraint must still be borrowed through the exact index join;"
                        + " document: " + document);
    }
}
