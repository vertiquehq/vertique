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

    // --- C1 (spike/deserializer-driven-schema round 4, CRITICAL): sibling-ordered unwrapped pair ---

    /**
     * The first unwrapped sibling, carrying no any-setter of its own: an aliased, constrained member and
     * a hidden, constrained member — the exact shape {@link HiddenChild} and {@link AliasChild} probe
     * individually, combined onto one class so the fold has something to fold for the *first*-processed
     * sibling.
     */
    static final class SiblingA {
        @JsonAlias("ak")
        @Size(max = 3)
        public String aname;

        @Schema(hidden = true)
        @Size(max = 3)
        public String secret;
    }

    /** The second unwrapped sibling: the any-setter lives here, not on {@link SiblingA} or the parent. */
    static final class SiblingB {
        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /**
     * C1: two {@code @JsonUnwrapped} siblings declared in this order, where only the *second* ({@link
     * SiblingB}) carries the {@code @JsonAnySetter}. {@code populateObjectSchema}'s unwrapped loop only
     * learns a type is any-setter-shaped once it has processed the child that actually declares the
     * any-setter — so when the first sibling ({@link SiblingA}) is processed, the loop's own
     * {@code anySetterType} signal is still {@code false}, and {@link
     * InputPropertyDescriber#foldUnwrappedChildIntoParentPlan} is skipped for it entirely, regardless of
     * iteration order.
     */
    static final class SiblingOrderedParent {
        @JsonUnwrapped
        public SiblingA a;

        @JsonUnwrapped
        public SiblingB b;
    }

    @Test
    @DisplayName("C1 CHARACTERIZATION: a sibling unwrapped child's alias is published under its own constraint"
            + " even though the any-setter is declared on a *different* sibling — does not discriminate the"
            + " fold's own order bug on this Jackson version")
    void firstSiblingsAliasIsPublishedEvenThoughTheAnySetterIsOnTheSecondSibling() {
        // CHARACTERIZATION NOTE, checked empirically (measured against this run): exactly as
        // unwrappedChildAliasIsPublishedWithTheChildMembersConstraint documents for the single-child
        // shape, Jackson's own unwrappingDeserializer already iterates a *bare* @JsonUnwrapped member's
        // alias spelling as a distinct bound property, independent of any sibling or any-setter at all —
        // so "ak" is published here through the ordinary child-property loop before
        // foldUnwrappedChildIntoParentPlan ever runs, regardless of which sibling carries the any-setter
        // or which sibling is processed first. This assertion is kept as a positive regression guard, not
        // as C1's own discriminating proof — the hidden-member test below is that proof, exactly as it is
        // for the single-child F2 probe.
        JsonNode document = inputDocument(SiblingOrderedParent.class);

        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("aname").toString(),
                "document: " + document);
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("ak").toString(),
                "the alias \"ak\" of the first-processed unwrapped sibling (SiblingA) must be published with"
                        + " the same constraint as \"aname\"; document: " + document);
    }

    @Test
    @DisplayName("C1: a sibling unwrapped child's hidden member is reserved even though the any-setter is"
            + " declared on a different sibling, processed later")
    void firstSiblingsHiddenMemberIsReservedEvenThoughTheAnySetterIsOnTheSecondSibling() {
        JsonNode document = inputDocument(SiblingOrderedParent.class);

        assertFalse(
                document.path("properties").has("secret"),
                "a @Schema(hidden = true) member must stay unpublished; document: " + document);
        JsonNode reservedNames = document.path("propertyNames").path("not").path("enum");
        boolean reserved = false;
        if (reservedNames.isArray()) {
            for (JsonNode entry : reservedNames) {
                if ("secret".equals(entry.asText())) {
                    reserved = true;
                }
            }
        }
        assertTrue(
                reserved,
                "C1 DECISIVE: the first-processed unwrapped sibling's hidden member \"secret\" must be"
                        + " reserved even though the any-setter is declared on the second sibling, exactly as"
                        + " it is reserved when the any-setter sits on that same sibling (see"
                        + " unwrappedChildHiddenMemberIsReserved above); document: " + document);
    }

    /**
     * The real-binder half of the C1 probe: {@code {"ak":"abcdefgh"}} binds straight into
     * {@code a.aname} — 8 characters against the field's own {@code @Size(max = 3)} — regardless of
     * which sibling carries the any-setter, so a document that leaves "ak" unpublished and unreserved is
     * a genuine constraint bypass, not only a description gap.
     *
     * @throws Exception when the binder itself throws
     */
    @Test
    @DisplayName("C1 premise: the real binder routes the first sibling's alias key straight into its"
            + " constrained field, oversized value included")
    void jacksonBinderRoutesTheSiblingAliasKeyIntoTheConstrainedField() throws Exception {
        SiblingOrderedParent bound =
                plainProfile().mapper().readValue("{\"ak\":\"abcdefgh\"}", SiblingOrderedParent.class);

        assertTrue(
                bound.a != null && "abcdefgh".equals(bound.a.aname),
                "the binder must route \"ak\" straight into the first sibling's constrained field for this"
                        + " premise to hold; bound.a=" + (bound.a == null ? null : bound.a.aname));
    }

    // --- S1 (spike/deserializer-driven-schema round 4): alias folding under a non-trivial unwrap prefix ---

    /** A member carrying an alias, unwrapped under a non-no-op transformer ({@code prefix = "p_"}). */
    static final class PrefixedAliasChild {
        @JsonAlias("tok")
        @Size(max = 3)
        public String token;
    }

    static final class PrefixedAliasParent {
        @JsonUnwrapped(prefix = "p_")
        public PrefixedAliasChild child;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /**
     * S1 (owner ruling): under a non-no-op unwrap transformer, {@code foldUnwrappedChildIntoParentPlan}
     * still folds the child's alias — hand-transforming the alias's own local spelling
     * ({@code "tok"} &rarr; {@code "p_tok"}) — into the parent's alias plan, which the generator then
     * materializes as a published {@code "p_tok"} property carrying {@code token}'s own schema. The
     * owner ruling records that this is measured to be over-description: neither the plain alias
     * spelling ({@code "tok"}) nor the hand-transformed one ({@code "p_tok"}) actually binds through
     * Jackson's own unwrapping deserializer once a real prefix is in play, so the published spelling is
     * validated against a constraint a key of that name never actually reaches at the binder. This test
     * reports the current state (not forced to red or green) and the binder measurement it rests on.
     */
    @Test
    @DisplayName("S1: under a non-trivial unwrap prefix, current state of whether an alias spelling is folded"
            + " into the plan — reported against the measured binder behavior")
    void prefixedUnwrapAliasFoldingCurrentState() throws Exception {
        JsonNode document = inputDocument(PrefixedAliasParent.class);

        // The canonical (transformed) child property is expected to be published either way.
        assertEquals(
                "{\"maxLength\":3,\"type\":\"string\"}",
                document.path("properties").path("p_token").toString(),
                "the canonical transformed property must be published; document: " + document);

        boolean plainAliasPublished = document.path("properties").has("tok");
        boolean transformedAliasPublished = document.path("properties").has("p_tok");

        // Measured against the real binder: does either spelling actually reach the constrained field?
        PrefixedAliasParent boundPlain =
                plainProfile().mapper().readValue("{\"tok\":\"abcdefgh\"}", PrefixedAliasParent.class);
        boolean plainSpellingBinds = boundPlain.child != null && "abcdefgh".equals(boundPlain.child.token);
        PrefixedAliasParent boundTransformed =
                plainProfile().mapper().readValue("{\"p_tok\":\"abcdefgh\"}", PrefixedAliasParent.class);
        boolean transformedSpellingBinds =
                boundTransformed.child != null && "abcdefgh".equals(boundTransformed.child.token);

        assertFalse(
                plainSpellingBinds,
                "S1 MEASUREMENT: the owner ruling's premise is that neither alias spelling binds once a"
                        + " real unwrap prefix is in play — if this fails, the ruling's premise for \"tok\""
                        + " does not hold on this Jackson version");
        assertFalse(
                transformedSpellingBinds,
                "S1 MEASUREMENT: the owner ruling's premise is that neither alias spelling binds once a"
                        + " real unwrap prefix is in play — if this fails, the ruling's premise for \"p_tok\""
                        + " does not hold on this Jackson version");

        assertFalse(
                plainAliasPublished,
                "S1 CURRENT STATE: the untransformed alias spelling \"tok\" must never be folded into the"
                        + " plan — it never carried the prefix to begin with, so this is not the shape the"
                        + " fold could have produced either way; document: " + document);
        assertFalse(
                transformedAliasPublished,
                "S1 CURRENT STATE (owner ruling target): the hand-transformed alias spelling \"p_tok\" must"
                        + " not be folded into the plan, since the measurement above shows it never actually"
                        + " binds through the real deserializer — if this assertion fails, the fold still"
                        + " runs unconditionally for a non-no-op transformer (pre-fix state); document: "
                        + document);
    }
}
