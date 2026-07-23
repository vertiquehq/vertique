// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.localization.config.LocalizationConfig;
import dev.vertique.localization.context.LocalizationContext;
import dev.vertique.localization.locale.LocaleResolver;
import dev.vertique.localization.message.MessageSourceFactory;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger boot test for {@link LocalizationModule}.
 *
 * <p>Verifies that all bindings contributed by {@link LocalizationModule} resolve correctly
 * through a real Dagger component, including:
 * <ul>
 *   <li>Substrate-free bindings: {@code LocalizationConfig}, {@code LocaleResolver},
 *       {@code MessageSourceFactory}.</li>
 *   <li>Context-propagation multibinding contributions: the service-dispatch encoder/decoder pair
 *       and the durable encoder/decoder pair for {@link LocalizationContext}.</li>
 * </ul>
 *
 * <p>{@link TestSupportModule} declares the {@code @Multibinds} empty-set seeds for the four SPI
 * families so the component compiles without depending on {@code ContextRuntimeModule} from
 * {@code vertique-context}.
 */
class LocalizationModuleWiringTest {

    @Module
    abstract static class TestSupportModule {

        @Provides
        @VertxConfig
        @Singleton
        static JsonObject vertxConfig() {
            // Minimal localization config — supportedLocales must include defaultLocale per FR-LOC-045.
            return new JsonObject()
                    .put(
                            "localization",
                            new JsonObject()
                                    .put("defaultLocale", "en")
                                    .put("supportedLocales", List.of("en", "fi", "sv")));
        }

        /**
         * Declares the empty multibinding seed for service-dispatch encoders so the component
         * compiles without pulling in {@code ContextRuntimeModule}.
         *
         * @return the empty set declaration
         */
        @Multibinds
        abstract Set<ServiceDispatchContextEncoder<?>> serviceDispatchContextEncoders();

        /**
         * Declares the empty multibinding seed for service-dispatch decoders.
         *
         * @return the empty set declaration
         */
        @Multibinds
        abstract Set<ServiceDispatchContextDecoder<?>> serviceDispatchContextDecoders();

        /**
         * Declares the empty multibinding seed for durable context metadata encoders.
         *
         * @return the empty set declaration
         */
        @Multibinds
        abstract Set<DurableContextMetadataEncoder<?>> durableContextMetadataEncoders();

        /**
         * Declares the empty multibinding seed for durable context metadata decoders.
         *
         * @return the empty set declaration
         */
        @Multibinds
        abstract Set<DurableContextMetadataDecoder<?>> durableContextMetadataDecoders();
    }

    private final LocalizationTestComponent component = DaggerLocalizationTestComponent.create();

    @Test
    @DisplayName("LocalizationConfig binding resolves via Dagger with parsed values")
    void configBinding() {
        LocalizationConfig config = component.config();

        assertNotNull(config);
        assertEquals(Locale.forLanguageTag("en"), config.defaultLocale());
        assertEquals(ZoneId.of("UTC"), config.defaultZone());
        assertEquals(
                List.of(Locale.forLanguageTag("en"), Locale.forLanguageTag("fi"), Locale.forLanguageTag("sv")),
                config.supportedLocales());
    }

    @Test
    @DisplayName("LocaleResolver binding resolves and uses the parsed config")
    void resolverBinding() {
        LocaleResolver resolver = component.resolver();

        assertNotNull(resolver);
        assertEquals(Locale.forLanguageTag("en"), resolver.defaultLocale());
        assertEquals(3, resolver.supportedLocales().size());
        // Sanity check that it actually resolves
        assertEquals(Locale.forLanguageTag("fi"), resolver.resolveLanguageRange("fi-FI"));
    }

    @Test
    @DisplayName("MessageSourceFactory binding resolves and creates a usable MessageSource")
    void factoryBinding() {
        MessageSourceFactory factory = component.factory();

        assertNotNull(factory);
        // Factory is usable end-to-end — pick a known key from messages_en.properties (test resources).
        assertEquals("Plain text", factory.create("messages").getMessage("plain", Locale.forLanguageTag("en")));
    }

    @Test
    @DisplayName("FR-LOC-011/012: LocalizationModule source declares no MessageSource binding or multibinding")
    void noMessageSourceBindingInSource() throws Exception {
        Path moduleSource = Path.of("src/main/java/dev/vertique/localization/LocalizationModule.java");
        String code = stripCommentsAndStrings(Files.readString(moduleSource));

        // After stripping Javadoc/line-comments/string literals, the source must not contain
        // a Set<MessageSource> multibinding (FR-LOC-012) or a @Multibinds declaration.
        assertFalse(code.contains("Set<MessageSource>"), "Set<MessageSource> multibinding is forbidden (FR-LOC-012)");
        assertFalse(code.contains("@Multibinds"), "@Multibinds must not appear in LocalizationModule");
        // Sanity: we found the module file
        assertTrue(code.contains("LocalizationModule"));
    }

    /**
     * Strips block comments (Javadoc), line comments, and double-quoted string literals from
     * Java source so source-grep assertions match only real code identifiers, not text in
     * documentation or example blocks.
     *
     * @param source the raw Java source text
     * @return the source with comments and string literals replaced
     */
    private static String stripCommentsAndStrings(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    @Test
    @DisplayName("LocalizationModule is an abstract class (no constructor args, Dagger-required for @Binds)")
    void moduleIsAbstract() {
        assertTrue(Modifier.isAbstract(LocalizationModule.class.getModifiers()), "abstract @Module supports @Binds");
    }

    // --- Context-propagation multibinding wiring ---

    @Test
    @DisplayName("service-dispatch encoder set contains the LocalizationContext encoder")
    void serviceDispatchEncoderContributed() {
        assertTrue(
                component.serviceDispatchEncoders().stream().anyMatch(e -> e.type() == LocalizationContext.class),
                "service-dispatch encoder for LocalizationContext must be in the set");
    }

    @Test
    @DisplayName("service-dispatch decoder set contains the LocalizationContext decoder")
    void serviceDispatchDecoderContributed() {
        assertTrue(
                component.serviceDispatchDecoders().stream().anyMatch(d -> d.type() == LocalizationContext.class),
                "service-dispatch decoder for LocalizationContext must be in the set");
    }

    @Test
    @DisplayName("durable encoder set contains exactly one LocalizationContext encoder")
    void durableEncoderContributed() {
        assertEquals(
                1L,
                component.durableEncoders().stream()
                        .filter(e -> e.type() == LocalizationContext.class)
                        .count(),
                "exactly one durable encoder for LocalizationContext");
    }

    @Test
    @DisplayName("durable decoder set contains exactly one LocalizationContext decoder")
    void durableDecoderContributed() {
        assertEquals(
                1L,
                component.durableDecoders().stream()
                        .filter(d -> d.type() == LocalizationContext.class)
                        .count(),
                "exactly one durable decoder for LocalizationContext");
    }
}
