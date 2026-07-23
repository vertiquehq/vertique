// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;

/**
 * A single contributor to inbound locale resolution.
 *
 * <p>{@code RequestLocaleInterceptor} walks the registered {@code Set<LocaleSource>} sorted by the
 * {@link OrderedExtension} contract — ascending phase, then ascending {@link #priority()}, then
 * {@link #orderKey()} (fully-qualified class name) as a stable tie-break — and binds the first
 * non-empty {@link ResolvedLocale}. If every source defers, the configured default locale is bound
 * as the last resort. Sources are contributed via Dagger {@code @IntoSet}.
 *
 * <p>Application sources at the default phase ({@link dev.vertique.core.extension.ExtensionPhase#APPLICATION})
 * and the default priority {@code 0} (cookie, query parameter, custom header) run before the
 * built-in {@link AcceptLanguageLocaleSource}, which runs late at priority
 * {@link AcceptLanguageLocaleSource#PRIORITY} — so the browser header is a low-precedence fallback
 * rather than a default. The post-authentication user-profile case is intentionally
 * <em>not</em> a {@code LocaleSource} — the principal is unknown when {@code beforeRequest} runs;
 * applications re-bind a richer {@code LocalizationContext} after authentication instead.
 *
 * <p>A {@code LocaleSource} SHOULD NOT throw: the interceptor catches any {@link RuntimeException},
 * logs it (throttled per source class), and treats it as an empty result so a misbehaving source
 * cannot fail the request.
 *
 * @see OrderedExtension
 */
public interface LocaleSource extends OrderedExtension {

    /**
     * Resolves a locale for the current request, or {@link Optional#empty()} to defer to the next
     * source in the chain.
     *
     * @param rc the current routing context
     * @return the resolved locale and its source label, or empty to defer
     */
    Optional<ResolvedLocale> resolve(RoutingContext rc);
}
