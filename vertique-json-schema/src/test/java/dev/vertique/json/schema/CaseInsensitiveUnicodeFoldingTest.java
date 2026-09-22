// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonMapperProfiles;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C1 (review finding): on a case-insensitively bound type where extras are described, Jackson folds a
 * property name with {@code String#toLowerCase()} (no explicit {@link java.util.Locale}), and a
 * non-ASCII code point can fold to an ASCII letter — U+212A KELVIN SIGN folds to ASCII {@code 'k'}
 * regardless of locale. A JSON key spelled with U+212A therefore binds to the same member an ASCII
 * {@code 'k'} would (Jackson's own case-insensitive lookup, confirmed here against the real binder),
 * while the generated document's ASCII-only {@code patternProperties} fold and the reserved-name
 * pattern both miss it — so the key falls through to {@code additionalProperties} (the extras bucket)
 * in the *schema's* view, even though the *binder* routes it straight into a real, constrained member.
 * A malformed or out-of-range value for that member sent under the confusable spelling would validate
 * as a permissive "extra" instead of being checked against the member's own constraint.
 *
 * <p>Fix: a case-insensitively bound type with extras described gets a {@code propertyNames} rule
 * rejecting any key containing a non-ASCII code unit, so such a key is refused outright at the gate
 * instead of silently routed to the extras bucket.
 */
class CaseInsensitiveUnicodeFoldingTest {

    /** KELVIN SIGN: folds to ASCII 'k' under Java's locale-independent Unicode case mapping. */
    private static final String KELVIN_SIGN = "K";

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CaseInsensitiveWithExtras {
        @Size(max = 3)
        public String key;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** F3 (security review round 1, HIGH): closed — no any-setter, so extras are never described. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class ClosedCaseInsensitive {
        @Size(max = 3)
        public String key;
    }

    private static JsonMapperProfile caseInsensitiveProfile() {
        ObjectMapper mapper = new ObjectMapper();
        return JsonMapperProfiles.of(JsonProfileId.of("ci-unicode-fold-test"), mapper);
    }

    private static JsonNode inputDocument(Type type) {
        return assertCanonicalForm(AnnotationJsonSchemaGenerator.forInputProfile(caseInsensitiveProfile())
                .generateCanonical(type));
    }

    @Test
    @DisplayName("premise: the Kelvin sign folds to ASCII 'k' under Java's default toLowerCase, no explicit locale")
    void kelvinSignFoldsToAsciiK() {
        assertTrue((KELVIN_SIGN + "ey").toLowerCase().equals("key"), "Unicode premise for this finding must hold");
    }

    @Test
    @DisplayName("premise: Jackson's own case-insensitive binder routes the Kelvin-sign spelling to the real member")
    void jacksonBindsKelvinSignSpellingToTheRealMember() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        CaseInsensitiveWithExtras bound =
                mapper.readValue("{\"" + KELVIN_SIGN + "ey\":\"abcd\"}", CaseInsensitiveWithExtras.class);
        assertTrue(bound.key != null, "the confusable spelling must bind to the real, constrained member");
        assertTrue(bound.extras.isEmpty(), "the confusable spelling must not land as an ordinary extra");
    }

    @Test
    @DisplayName(
            "C1: a key containing a non-ASCII code unit is refused by propertyNames on a case-insensitive, extras-described type")
    void nonAsciiKeyRefusedByPropertyNames() {
        JsonNode document = inputDocument(CaseInsensitiveWithExtras.class);
        JsonNode rule = document.path("propertyNames").path("not").path("pattern");
        assertFalse(rule.isMissingNode(), "a case-insensitive, extras-described type must carry a propertyNames rule");
        Pattern pattern = Pattern.compile(rule.asText());
        assertTrue(
                pattern.matcher(KELVIN_SIGN + "ey").find(),
                () -> "the confusable spelling must match the refusal pattern " + rule.asText());
        assertFalse(pattern.matcher("key").find(), "the canonical ASCII spelling must not be refused by the same rule");
    }

    @Test
    @DisplayName("F3: a *closed* case-insensitive type (no any-setter, no extras) still carries a propertyNames"
            + " non-ASCII fold refusal — REST has no additionalProperties closure to fall back on")
    void closedCaseInsensitiveTypeStillRefusesNonAsciiKey() throws Exception {
        JsonNode document = inputDocument(ClosedCaseInsensitive.class);
        JsonNode rule = document.path("propertyNames").path("not").path("pattern");
        assertFalse(
                rule.isMissingNode(),
                "a closed case-insensitive type must still carry a propertyNames rule (C1 applies unconditionally)");
        Pattern pattern = Pattern.compile(rule.asText());
        assertTrue(
                pattern.matcher(KELVIN_SIGN + "ey").find(),
                () -> "the confusable spelling must match the refusal pattern " + rule.asText());
        assertFalse(pattern.matcher("key").find(), "the canonical ASCII spelling must not be refused by the same rule");

        // The binder-level premise, mirrored for the closed type: the confusable spelling still binds to
        // the real, constrained member — there is no extras bucket for it to fall into instead.
        ObjectMapper mapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
        ClosedCaseInsensitive bound =
                mapper.readValue("{\"" + KELVIN_SIGN + "ey\":\"abcd\"}", ClosedCaseInsensitive.class);
        assertTrue(bound.key != null, "the confusable spelling must bind to the real, constrained member");
    }
}
