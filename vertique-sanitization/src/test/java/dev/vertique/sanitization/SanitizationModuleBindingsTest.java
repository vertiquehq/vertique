// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dagger.Component;
import dev.vertique.core.sanitization.CanonicalizerBinding;
import dev.vertique.core.sanitization.SanitizerBinding;
import dev.vertique.sanitization.canonicalize.CollapseWhitespaceCanonicalizer;
import dev.vertique.sanitization.canonicalize.LowerCaseCanonicalizer;
import dev.vertique.sanitization.canonicalize.NfcCanonicalizer;
import dev.vertique.sanitization.canonicalize.NfkcCanonicalizer;
import dev.vertique.sanitization.canonicalize.NormalizeLineEndingsCanonicalizer;
import dev.vertique.sanitization.canonicalize.RemoveIdentifierSeparatorsCanonicalizer;
import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
import dev.vertique.sanitization.canonicalize.UpperCaseCanonicalizer;
import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies the binding sets {@link SanitizationModule} contributes (AC-014): the eight built-in
 * canonicalizers and only the control-character sanitizer, with no HTML sanitizer bound.
 */
class SanitizationModuleBindingsTest {

    @Singleton
    @Component(modules = SanitizationModule.class)
    interface BindingsComponent {
        Set<CanonicalizerBinding> canonicalizerBindings();

        Set<SanitizerBinding> sanitizerBindings();
    }

    @Test
    void bindsTheEightCanonicalizersAndOnlyStripControlCharsSanitizer() {
        // Given a component containing only the sanitization module
        BindingsComponent component = DaggerSanitizationModuleBindingsTest_BindingsComponent.create();

        // When the contributed binding sets are read
        Set<Class<?>> canonicalizerKeys = component.canonicalizerBindings().stream()
                .map(CanonicalizerBinding::type)
                .collect(Collectors.toSet());
        Set<Class<?>> sanitizerKeys = component.sanitizerBindings().stream()
                .map(SanitizerBinding::type)
                .collect(Collectors.toSet());

        // Then the canonicalizer keys are the eight built-ins and the sanitizer keys are only the
        // control-character sanitizer
        assertEquals(
                Set.of(
                        TrimCanonicalizer.class,
                        NfkcCanonicalizer.class,
                        NfcCanonicalizer.class,
                        NormalizeLineEndingsCanonicalizer.class,
                        CollapseWhitespaceCanonicalizer.class,
                        UpperCaseCanonicalizer.class,
                        LowerCaseCanonicalizer.class,
                        RemoveIdentifierSeparatorsCanonicalizer.class),
                canonicalizerKeys);
        assertEquals(Set.of(StripControlCharsSanitizer.class), sanitizerKeys);
    }
}
