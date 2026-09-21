// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F2 (security review round 1, HIGH): a {@code @JsonUnwrapped} child's alias spellings and hidden
 * members are neither published nor reserved on an any-setter parent, so a client-submitted key
 * spelling one of them binds through the extras bucket unconstrained.
 *
 * <p>Probe (from the review): {@code Parent{@JsonAnySetter Map extras; @JsonUnwrapped Child child}},
 * {@code Child{@JsonAlias("tok") String token}}; body {@code {"tok":"abcdef"}} bound {@code
 * child.token = "abcdef"} while the generated document neither published {@code "tok"} under {@code
 * properties} nor refused it via {@code propertyNames.not.enum} — it fell through to {@code
 * additionalProperties}, an unconstrained value, even though the binder routed it straight into
 * {@code token}'s own {@code @Size(max = 3)}. Same shape for a {@code @Schema(hidden = true)} member.
 */
class UnwrappedAnySetterFoldingTest {

    private static JsonMapperProfile plainProfile() {
        return JsonMapperProfiles.of(JsonProfileId.of("unwrapped-any-setter-folding-test"), new ObjectMapper());
    }

    private static JsonNode inputDocument(Type type) {
        return assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(plainProfile()).generateCanonical(type));
    }

    // --- Alias spelling ---

    static final class AliasChild {
        @Size(max = 3)
        @JsonAlias("tok")
        public String token;
    }

    static final class AliasParent {
        @JsonUnwrapped
        public AliasChild child;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    @Test
    @DisplayName("F2: an unwrapped child's alias spelling is published under the child member's own"
            + " constraint, never left unconstrained in the extras bucket")
    void unwrappedChildAliasIsPublishedWithTheChildMembersConstraint() {
        // CHARACTERIZATION NOTE, checked empirically against this project's pinned Jackson 2.21.4:
        // for a *bare* @JsonUnwrapped (no prefix/suffix), Jackson's own unwrappingDeserializer already
        // iterates the alias spelling as a distinct bound property alongside the canonical name — so
        // boundProperties(unwrapped, null) already contains "tok" before F2's own fold runs, and this
        // specific assertion does not discriminate pre-fix from post-fix code on this Jackson version
        // (confirmed: reverting the F2 change and re-running this exact test still passes). It is kept
        // as a positive regression guard for that already-correct behavior, and because
        // foldUnwrappedChildIntoParentPlan must not corrupt it (a duplicate or conflicting alias-plan
        // entry) when it runs anyway. The discriminating proof for F2 is the hidden-member test below,
        // which is unambiguously broken before this fix and fixed after, confirmed both in the
        // generated document and against the real Jackson binder.
        JsonNode document = inputDocument(AliasParent.class);

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("token").toString(),
                "document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("tok").toString(),
                "the unwrapped child's alias spelling \"tok\" must be published with the same constraint as"
                        + " \"token\", or a submitted \"tok\" value longer than 3 characters would bind"
                        + " straight into the constrained member while validating as an unconstrained extra;"
                        + " document: " + document);
    }

    // --- Hidden member ---

    static final class HiddenChild {
        @Schema(hidden = true)
        @Size(max = 3)
        public String token;
    }

    static final class HiddenParent {
        @JsonUnwrapped
        public HiddenChild child;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    @Test
    @DisplayName("F2: an unwrapped child's hidden member is reserved on the parent, refusing a key spelling it"
            + " instead of leaving it to the extras bucket")
    void unwrappedChildHiddenMemberIsReserved() {
        JsonNode document = inputDocument(HiddenParent.class);

        assertFalse(
                document.path("properties").has("token"),
                "a @Schema(hidden = true) member must stay unpublished, as before; document: " + document);
        JsonNode reservedNames = document.path("propertyNames").path("not").path("enum");
        boolean reserved = false;
        if (reservedNames.isArray()) {
            for (JsonNode entry : reservedNames) {
                if ("token".equals(entry.asText())) {
                    reserved = true;
                }
            }
        }
        assertTrue(
                reserved,
                "the unwrapped child's hidden member \"token\" must be reserved, refusing a key spelling it"
                        + " outright, rather than letting it fall through to additionalProperties as an"
                        + " unconstrained extra that the real, constrained member never validates; document: "
                        + document);
    }

    /**
     * The real-binder half of the hidden-member probe, checked directly against the profile's own
     * mapper (empirically confirmed, not assumed): {@code {"token":"toolongvalue"}} binds straight into
     * {@code child.token} — 12 characters against the field's own {@code @Size(max = 3)} — proving this
     * is a genuine constraint bypass, not only a description gap. The gate-level counterpart (this
     * document's {@code propertyNames} rule refusing the key outright) is proven above.
     *
     * @throws Exception when the binder itself throws
     */
    @Test
    @DisplayName("F2 premise: the real binder routes a key spelling an unwrapped child's hidden member straight"
            + " into the constrained field, oversized value included")
    void jacksonBinderRoutesTheHiddenKeyIntoTheConstrainedField() throws Exception {
        HiddenParent bound = plainProfile().mapper().readValue("{\"token\":\"toolongvalue\"}", HiddenParent.class);

        assertTrue(
                bound.child != null && "toolongvalue".equals(bound.child.token),
                "the binder must route \"token\" straight into the hidden, constrained field for this"
                        + " premise to hold — the schema's own propertyNames refusal is what stops it at"
                        + " the gate; bound.child="
                        + (bound.child == null ? null : bound.child.token));
    }

    // --- Nested unwrap refusal ---

    static final class Grandchild {
        public String leaf;
    }

    static final class NestedChild {
        @JsonUnwrapped
        public Grandchild grandchild;
    }

    static final class NestedAnySetterParent {
        @JsonUnwrapped
        public NestedChild child;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    @Test
    @DisplayName("F2: a nested @JsonUnwrapped chain on an any-setter type is refused with a bounded diagnostic")
    void nestedUnwrappedChainOnAnAnySetterTypeIsRefused() {
        JsonSchemaGenerationException failure =
                assertThrows(JsonSchemaGenerationException.class, () -> inputDocument(NestedAnySetterParent.class));

        assertTrue(
                failure.getMessage().contains("nested"),
                "the diagnostic must name what is refused; was: " + failure.getMessage());
        assertTrue(failure.getMessage().length() <= Diagnostics.MAX_MESSAGE_LENGTH, "was: " + failure.getMessage());
    }
}
