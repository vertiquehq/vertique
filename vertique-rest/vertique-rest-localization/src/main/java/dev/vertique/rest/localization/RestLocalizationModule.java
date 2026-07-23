// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.localization.LocalizationModule;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import java.util.Set;

/**
 * Dagger module wiring inbound REST locale negotiation.
 *
 * <p>Includes {@link LocalizationModule} so that applications add only {@code RestLocalizationModule}
 * to their component and transitively receive {@code LocaleResolver}, {@code LocalizationConfig}, and
 * the localization propagation adapters — the built-in {@code AcceptLanguageLocaleSource} needs the
 * resolver and the interceptor needs the config.
 *
 * <p>Declares the {@code Set<LocaleSource>} multibinding, contributes the built-in
 * {@code Accept-Language} source, and contributes the {@code RequestLocaleInterceptor} to the request
 * interceptor chain.
 */
@Module(includes = LocalizationModule.class)
public abstract class RestLocalizationModule {

    /** Declares the (possibly empty) set of locale sources; applications contribute via {@code @IntoSet}. */
    @Multibinds
    abstract Set<LocaleSource> localeSources();

    /**
     * Contributes the built-in {@code Accept-Language} negotiation source.
     *
     * @param source the built-in source instance
     * @return the source, contributed into {@code Set<LocaleSource>}
     */
    @Provides
    @IntoSet
    static LocaleSource acceptLanguageLocaleSource(AcceptLanguageLocaleSource source) {
        return source;
    }

    /**
     * Contributes the inbound locale-binding interceptor to the request interceptor chain.
     *
     * @param interceptor the interceptor instance
     * @return the interceptor, contributed into {@code Set<RequestInterceptor>}
     */
    @Provides
    @IntoSet
    static RequestInterceptor requestLocaleInterceptor(RequestLocaleInterceptor interceptor) {
        return interceptor;
    }
}
