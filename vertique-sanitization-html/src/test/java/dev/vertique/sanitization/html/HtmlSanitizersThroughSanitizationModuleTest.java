// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.sanitization.SanitizationModule;
import dev.vertique.sanitization.Sanitize;
import dev.vertique.sanitization.Sanitizer;
import dev.vertique.sanitization.SanitizerBinding;
import dev.vertique.sanitization.processing.EffectiveInputPolicies;
import dev.vertique.sanitization.processing.InputObjectProcessor;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the HTML sanitizers apply through {@link SanitizationModule} with no binding of
 * their own (AC-013), and that a binding keyed by an HTML sanitizer class replaces the default
 * instance.
 */
class HtmlSanitizersThroughSanitizationModuleTest {

    private static final String RAW = "<p>Hi</p><script>alert('xss')</script>";

    @Singleton
    @Component(modules = SanitizationModule.class)
    interface DefaultComponent {
        InputObjectProcessor inputObjectProcessor();
    }

    @Module
    static class ContributedBasicHtmlBindingModule {
        @Provides
        @IntoSet
        static SanitizerBinding basicHtmlBinding() {
            Sanitizer marker = (value, context) -> "contributed-instance";
            return new SanitizerBinding(BasicHtmlSanitizer.class, marker);
        }
    }

    @Singleton
    @Component(modules = {SanitizationModule.class, ContributedBasicHtmlBindingModule.class})
    interface ContributedBindingComponent {
        InputObjectProcessor inputObjectProcessor();
    }

    static class HtmlDto {
        @Sanitize(StripAllHtmlSanitizer.class)
        String stripAll;

        @Sanitize(BasicHtmlSanitizer.class)
        String basic;

        @Sanitize(LinksHtmlSanitizer.class)
        String links;

        @Sanitize(RichTextHtmlSanitizer.class)
        String richText;
    }

    private static Map<String, Object> process(InputObjectProcessor processor) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stripAll", RAW);
        input.put("basic", RAW);
        input.put("links", RAW);
        input.put("richText", RAW);
        Object result = processor.processInput(
                input, HtmlDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY, InputFieldNameResolver.IDENTITY);
        @SuppressWarnings("unchecked")
        Map<String, Object> processed = (Map<String, Object>) result;
        return processed;
    }

    @Test
    void appliesEachHtmlSanitizerWithoutABinding() {
        // Given an engine from a component containing only the sanitization module
        InputObjectProcessor processor = DaggerHtmlSanitizersThroughSanitizationModuleTest_DefaultComponent.create()
                .inputObjectProcessor();

        // When a DTO with each HTML sanitizer on its own field is processed
        Map<String, Object> result = process(processor);

        // Then every field keeps its text, loses the script, and the strip-all field loses markup
        for (String field : new String[] {"stripAll", "basic", "links", "richText"}) {
            String value = (String) result.get(field);
            assertTrue(value.contains("Hi"), field + " keeps the text: " + value);
            assertFalse(value.contains("<script"), field + " has no script element: " + value);
        }
        assertFalse(((String) result.get("stripAll")).contains("<p"), "stripAll removes all markup");
    }

    @Test
    void bindingKeyedByAnHtmlSanitizerReplacesTheDefaultInstance() {
        // Given a component whose extra binding is keyed by BasicHtmlSanitizer
        InputObjectProcessor processor =
                DaggerHtmlSanitizersThroughSanitizationModuleTest_ContributedBindingComponent.create()
                        .inputObjectProcessor();

        // When the DTO is processed
        Map<String, Object> result = process(processor);

        // Then the field declaring BasicHtmlSanitizer is produced by the contributed instance
        assertEquals("contributed-instance", result.get("basic"));
        assertFalse(((String) result.get("richText")).contains("<script"), "other sanitizers still apply");
    }
}
