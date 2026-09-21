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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code InputPropertyDescriber#methodSchema}'s builder-method borrow used to scan the built type's
 * declared fields by raw name for a match against either the builder method's own Java name or the
 * property's wire name. It is tightened here (not removed: {@code main}'s field walk published exactly
 * these constraints for a Lombok builder type, so dropping the borrow would loosen against {@code
 * main}) to borrow from the built type's own <em>Jackson-introspected</em> property of that wire name
 * instead — Jackson's own semantics, rather than a coincidental name scan.
 *
 * <p>The tightening does not close the shape-level gap {@code module.md}'s "Builder borrow assumption"
 * documents: a builder method is assumed to set the built property of the same wire name, which is
 * guaranteed by construction for a Lombok {@code @Builder @Jacksonized} type, but not for a
 * hand-written builder whose method transforms the value before assigning it — this class still
 * borrows that field's constraint, which can render a stricter schema than the binder actually
 * accepts. {@link #handWrittenTransformingBuilderFixture()} pins that documented, accepted behavior so
 * a future change makes it visible rather than silent.
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
     * assigning it to the built field of the same wire name. Jackson's own introspection still finds
     * the built type's {@code amount} property and this borrow still applies its {@code @Max(10)} to
     * the wire property {@code amount} — over-strict relative to what the binder actually accepts
     * ({@code {"amount": 500}} binds to {@code amount = 5}, well under the published ceiling of 10, but
     * a client sending {@code amount = 11} would be rejected by the schema even though it binds to
     * {@code amount = 0}). This is the documented gap, not a defect: the walk has no way to read a
     * builder method's body, so it cannot tell this shape apart from an honest one-to-one setter.
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

    @Test
    @DisplayName("PIN: a hand-written builder method that transforms its value still borrows the built field's"
            + " constraint (documented, accepted gap — module.md)")
    void handWrittenTransformingBuilderFixture() {
        JsonNode document = document(TransformingBuilderDto.class);
        JsonNode amount = document.at("/properties/amount");

        assertFalse(amount.isMissingNode(), "amount must be published: the builder method binds it");
        assertEquals(
                10,
                amount.at("/maximum").asInt(),
                "the built field's @Max(10) must still be borrowed onto the builder-bound wire property,"
                        + " exactly as it was before the borrow was tightened to Jackson's own introspected"
                        + " property lookup — only the lookup mechanism changed, not this documented"
                        + " consequence; document: " + document);
    }
}
