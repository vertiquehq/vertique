// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PropertyCondition} and {@link PropertyCondition#matchesAll}.
 *
 * <p>Verifies the AND semantics of multi-condition evaluation, the {@code matchIfMissing} branches,
 * scalar-type coercion via {@link String#valueOf}, {@link ConfigurationException} paths for
 * invalid shapes and non-scalar leaves, and vacuous-truth for empty/null condition arrays.
 *
 * <p>Critical guard: tests explicitly verify that a {@code MISSING + matchIfMissing=true} first
 * condition does NOT short-circuit the evaluation loop — subsequent conditions must still be
 * checked before a final result is produced.
 */
class PropertyConditionTest {

    // --- Factory helpers ---

    private static PropertyCondition cond(String name, String havingValue, boolean matchIfMissing) {
        return new PropertyCondition(name, havingValue, matchIfMissing);
    }

    private static PropertyCondition cond(String name) {
        return new PropertyCondition(name, "true", false);
    }

    // --- Vacuous-truth (null / empty array) ---

    @Nested
    @DisplayName("Vacuous truth — null or empty condition arrays")
    class VacuousTrue {

        @Test
        @DisplayName("null conditions array returns true unconditionally")
        void nullConditionsArray_returnsTrue() {
            assertTrue(PropertyCondition.matchesAll(new JsonObject(), null));
        }

        @Test
        @DisplayName("empty conditions array returns true unconditionally")
        void emptyConditionsArray_returnsTrue() {
            assertTrue(PropertyCondition.matchesAll(new JsonObject(), new PropertyCondition[0]));
        }
    }

    // --- Single-condition / MISSING paths ---

    @Nested
    @DisplayName("Single condition — MISSING property")
    class SingleMissing {

        @Test
        @DisplayName("MISSING + matchIfMissing=false returns false")
        void singleConditionMissing_matchIfMissingFalse_returnsFalse() {
            JsonObject config = new JsonObject();
            assertFalse(PropertyCondition.matchesAll(
                    config, new PropertyCondition[] {cond("feature.enabled", "true", false)}));
        }

        @Test
        @DisplayName("MISSING + matchIfMissing=true returns true")
        void singleConditionMissing_matchIfMissingTrue_returnsTrue() {
            JsonObject config = new JsonObject();
            assertTrue(PropertyCondition.matchesAll(
                    config, new PropertyCondition[] {cond("feature.enabled", "true", true)}));
        }
    }

    // --- AND semantics: no short-circuit on matchIfMissing=true ---

    @Nested
    @DisplayName("AND semantics — no short-circuit on first matchIfMissing=true")
    class AndSemantics {

        @Test
        @DisplayName("first MISSING+matchIfMissing=true, second MISSING+matchIfMissing=false → false")
        void twoConditions_firstMissingMatchIfMissingTrue_secondMissingMatchIfMissingFalse_returnsFalse() {
            // This test guards against the footgun of returning true early when matchIfMissing=true
            // is satisfied — the remaining conditions must STILL be evaluated.
            JsonObject config = new JsonObject();
            PropertyCondition[] conditions = {
                cond("opt.feature", "true", true), // MISSING, matchIfMissing=true → continue
                cond("required.feature", "true", false) // MISSING, matchIfMissing=false → false
            };
            assertFalse(PropertyCondition.matchesAll(config, conditions));
        }

        @Test
        @DisplayName("first MISSING+matchIfMissing=true, second present scalar mismatch → false")
        void twoConditions_firstMissingMatchIfMissingTrue_secondPresentScalarMismatch_returnsFalse() {
            JsonObject config = new JsonObject().put("mode", "prod");
            PropertyCondition[] conditions = {
                cond("opt.feature", "true", true), // MISSING, matchIfMissing=true → continue
                cond("mode", "dev", false) // PRESENT but value "prod" != "dev" → false
            };
            assertFalse(PropertyCondition.matchesAll(config, conditions));
        }

        @Test
        @DisplayName("all conditions MISSING+matchIfMissing=true returns true")
        void twoConditions_bothMissingMatchIfMissingTrue_returnsTrue() {
            JsonObject config = new JsonObject();
            PropertyCondition[] conditions = {cond("opt.a", "true", true), cond("opt.b", "true", true)};
            assertTrue(PropertyCondition.matchesAll(config, conditions));
        }
    }

    // --- All conditions present and matching ---

    @Nested
    @DisplayName("All conditions present and matching")
    class AllPresent {

        @Test
        @DisplayName("all present and matching returns true")
        void allPresentAndMatching_returnsTrue() {
            JsonObject config = new JsonObject().put("a", "yes").put("b", "no");
            PropertyCondition[] conditions = {cond("a", "yes", false), cond("b", "no", false)};
            assertTrue(PropertyCondition.matchesAll(config, conditions));
        }

        @Test
        @DisplayName("any one scalar mismatch returns false")
        void anyScalarMismatch_returnsFalse() {
            JsonObject config = new JsonObject().put("a", "yes").put("b", "no");
            PropertyCondition[] conditions = {
                cond("a", "yes", false), cond("b", "yes", false) // mismatch: config has "no"
            };
            assertFalse(PropertyCondition.matchesAll(config, conditions));
        }
    }

    // --- Scalar-type coercion ---

    @Nested
    @DisplayName("Scalar-type coercion via String.valueOf")
    class ScalarCoercion {

        @Test
        @DisplayName("boolean scalar true with havingValue=\"true\" matches")
        void booleanScalar_stringEqualsTrue() {
            JsonObject config = new JsonObject().put("flag", true);
            assertTrue(PropertyCondition.matchesAll(config, new PropertyCondition[] {cond("flag", "true", false)}));
        }

        @Test
        @DisplayName("boolean scalar false with havingValue=\"true\" does not match")
        void booleanScalar_false_doesNotMatchTrue() {
            JsonObject config = new JsonObject().put("flag", false);
            assertFalse(PropertyCondition.matchesAll(config, new PropertyCondition[] {cond("flag", "true", false)}));
        }

        @Test
        @DisplayName("integer scalar 42 with havingValue=\"42\" matches")
        void numberScalar_stringEqualsValue() {
            JsonObject config = new JsonObject().put("n", 42);
            assertTrue(PropertyCondition.matchesAll(config, new PropertyCondition[] {cond("n", "42", false)}));
        }

        @Test
        @DisplayName("integer scalar 42 with havingValue=\"43\" does not match")
        void numberScalar_mismatch_returnsFalse() {
            JsonObject config = new JsonObject().put("n", 42);
            assertFalse(PropertyCondition.matchesAll(config, new PropertyCondition[] {cond("n", "43", false)}));
        }
    }

    // --- ConfigurationException paths ---

    @Nested
    @DisplayName("ConfigurationException — invalid shapes and non-scalar leaves")
    class Exceptions {

        @Test
        @DisplayName("INVALID_SHAPE (scalar at intermediate position) throws ConfigurationException")
        void invalidShape_throwsConfigurationException() {
            // path "a.b" where "a" is a leaf scalar → INVALID_SHAPE
            JsonObject config = new JsonObject().put("a", "leaf");
            assertThrows(
                    ConfigurationException.class,
                    () -> PropertyCondition.matchesAll(config, new PropertyCondition[] {cond("a.b", "true", false)}));
        }

        @Test
        @DisplayName("JsonObject leaf throws ConfigurationException (must resolve to a scalar)")
        void objectLeaf_throwsConfigurationException() {
            JsonObject config = new JsonObject().put("section", new JsonObject().put("k", "v"));
            assertThrows(
                    ConfigurationException.class,
                    () -> PropertyCondition.matchesAll(
                            config, new PropertyCondition[] {cond("section", "true", false)}));
        }

        @Test
        @DisplayName("JsonArray leaf throws ConfigurationException (must resolve to a scalar)")
        void arrayLeaf_throwsConfigurationException() {
            JsonObject config = new JsonObject().put("items", new JsonArray().add("x"));
            assertThrows(
                    ConfigurationException.class,
                    () -> PropertyCondition.matchesAll(config, new PropertyCondition[] {cond("items", "true", false)}));
        }
    }

    // --- Null root ---

    @Nested
    @DisplayName("Null root config")
    class NullRoot {

        @Test
        @DisplayName("null root with matchIfMissing=true returns true (all conditions satisfied)")
        void nullRoot_matchIfMissingTrue_returnsTrue() {
            assertTrue(PropertyCondition.matchesAll(null, new PropertyCondition[] {cond("feature", "true", true)}));
        }

        @Test
        @DisplayName("null root with matchIfMissing=false returns false")
        void nullRoot_matchIfMissingFalse_returnsFalse() {
            assertFalse(PropertyCondition.matchesAll(null, new PropertyCondition[] {cond("feature", "true", false)}));
        }
    }
}
