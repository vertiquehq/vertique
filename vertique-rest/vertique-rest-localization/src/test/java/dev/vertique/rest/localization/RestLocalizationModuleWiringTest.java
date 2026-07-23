// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.localization.config.LocalizationConfig;
import dev.vertique.localization.locale.LocaleResolver;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger boot test for {@link RestLocalizationModule}.
 *
 * <p>Proves the module self-containment claim: an application adds <strong>only</strong>
 * {@code RestLocalizationModule} (which {@code @Module(includes = LocalizationModule.class)}) and the
 * Dagger graph resolves {@link LocalizationConfig}, {@link LocaleResolver}, the {@code Set<LocaleSource>}
 * (with the built-in {@link AcceptLanguageLocaleSource}), and the {@code Set<RequestInterceptor>} (with
 * {@link RequestLocaleInterceptor} and its {@link ContextHolder} dependency) — without the component
 * explicitly listing {@code LocalizationModule}.
 *
 * <p>{@link TestSupportModule} stands in for what {@code RestCoreModule}/{@code VertxModule} supply in a
 * real application: the {@code @VertxConfig} JSON, the {@link ContextHolder} binding, and the empty
 * {@code @Multibinds} seeds for the context-propagation SPI families and the request-interceptor set.
 */
class RestLocalizationModuleWiringTest {

    @Singleton
    @Component(modules = {RestLocalizationModule.class, ConfigParsingModule.class, TestSupportModule.class})
    interface RestLocalizationTestComponent {
        LocalizationConfig config();

        LocaleResolver resolver();

        Set<LocaleSource> localeSources();

        Set<RequestInterceptor> requestInterceptors();
    }

    @Module
    abstract static class TestSupportModule {

        @Provides
        @VertxConfig
        @Singleton
        static JsonObject vertxConfig() {
            return new JsonObject()
                    .put(
                            "localization",
                            new JsonObject()
                                    .put("defaultLocale", "en")
                                    .put("supportedLocales", List.of("en", "fi", "sv")));
        }

        @Provides
        @Singleton
        static ContextHolder contextHolder() {
            return new DefaultContextHolder();
        }

        @Multibinds
        abstract Set<RequestInterceptor> requestInterceptors();

        @Multibinds
        abstract Set<ServiceDispatchContextEncoder<?>> serviceDispatchContextEncoders();

        @Multibinds
        abstract Set<ServiceDispatchContextDecoder<?>> serviceDispatchContextDecoders();

        @Multibinds
        abstract Set<DurableContextMetadataEncoder<?>> durableContextMetadataEncoders();

        @Multibinds
        abstract Set<DurableContextMetadataDecoder<?>> durableContextMetadataDecoders();
    }

    private final RestLocalizationTestComponent component =
            DaggerRestLocalizationModuleWiringTest_RestLocalizationTestComponent.create();

    @Test
    @DisplayName("LocalizationConfig + LocaleResolver resolve via the includes chain (no explicit LocalizationModule)")
    void localizationBindingsResolveTransitively() {
        LocalizationConfig config = component.config();
        assertNotNull(config);
        assertEquals(Locale.forLanguageTag("en"), config.defaultLocale());

        LocaleResolver resolver = component.resolver();
        assertNotNull(resolver);
        assertEquals(Locale.forLanguageTag("fi"), resolver.resolveLanguageRange("fi-FI"));
    }

    @Test
    @DisplayName("the built-in Accept-Language source is contributed to Set<LocaleSource>")
    void builtInLocaleSourceContributed() {
        assertTrue(
                component.localeSources().stream().anyMatch(AcceptLanguageLocaleSource.class::isInstance),
                "AcceptLanguageLocaleSource must be in Set<LocaleSource>");
    }

    @Test
    @DisplayName("the locale interceptor (with its ContextHolder dependency) is contributed to Set<RequestInterceptor>")
    void requestLocaleInterceptorContributed() {
        assertTrue(
                component.requestInterceptors().stream().anyMatch(RequestLocaleInterceptor.class::isInstance),
                "RequestLocaleInterceptor must be in Set<RequestInterceptor>");
    }
}
