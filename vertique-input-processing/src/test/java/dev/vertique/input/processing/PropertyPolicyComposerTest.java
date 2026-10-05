// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link InputObjectProcessor#resolvePropertyPolicies} composition order: invocation baseline,
 * then type-level, then field-level — the same append order {@code processInput} uses for a string
 * property, without transforming a value.
 */
class PropertyPolicyComposerTest {

    static final class BaselineSanit implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext ctx) {
            return value;
        }
    }

    static final class TypeSanit implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext ctx) {
            return value;
        }
    }

    static final class FieldSanit implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext ctx) {
            return value;
        }
    }

    static final class FieldCanon implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext ctx) {
            return value;
        }
    }

    @Sanitize(TypeSanit.class)
    static class MixedBean {
        @Sanitize(FieldSanit.class)
        @Canonicalize(FieldCanon.class)
        public String page;
    }

    @Test
    @DisplayName("baseline + type + field compose once in append order")
    void resolve_composesBaselineTypeThenField() {
        EffectiveInputPolicies baseline = new EffectiveInputPolicies(List.of(), List.of(BaselineSanit.class));

        EffectiveInputPolicies resolved =
                InputObjectProcessor.resolvePropertyPolicies(MixedBean.class, "page", baseline);

        assertEquals(List.of(FieldCanon.class), resolved.canonicalizers());
        assertEquals(
                List.of(BaselineSanit.class, TypeSanit.class, FieldSanit.class),
                resolved.sanitizers(),
                "invocation, then type, then field — single composed chain");
    }

    @Test
    @DisplayName("empty baseline still surfaces type and field metadata")
    void resolve_emptyBaseline_keepsTypeAndField() {
        EffectiveInputPolicies resolved =
                InputObjectProcessor.resolvePropertyPolicies(MixedBean.class, "page", EffectiveInputPolicies.NONE);

        assertEquals(List.of(FieldCanon.class), resolved.canonicalizers());
        assertEquals(List.of(TypeSanit.class, FieldSanit.class), resolved.sanitizers());
    }
}
