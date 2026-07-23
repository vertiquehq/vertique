// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.context;

import dev.vertique.core.context.ContextHolder;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

/**
 * Typed facade over {@link ContextHolder} for {@link LocalizationContext} bindings.
 *
 * <p>All methods delegate to the provided {@link ContextHolder} instance. The facade exists so
 * call sites do not need to repeat the {@code Class<LocalizationContext>} token at every call
 * site, reducing boilerplate and making it harder to accidentally pass the wrong type literal.
 *
 * <p>This class is a stateless utility — it holds no state and cannot be instantiated.
 *
 * <pre>{@code
 * // Instead of:
 * Optional<LocalizationContext> ctx = holder.current(LocalizationContext.class);
 *
 * // Use:
 * Optional<LocalizationContext> ctx = LocalizationContextHolder.current(holder);
 * }</pre>
 */
public final class LocalizationContextHolder {

    private LocalizationContextHolder() {}

    /**
     * Returns the currently bound {@link LocalizationContext} for the active Vert.x context, or
     * empty if no context is bound or the call is made outside a Vert.x-associated context.
     *
     * <p>Delegates to {@link ContextHolder#current(Class) holder.current(LocalizationContext.class)}.
     *
     * @param holder the context holder to delegate to; must not be {@code null}
     * @return the bound {@link LocalizationContext}, or {@link Optional#empty()}
     */
    public static Optional<LocalizationContext> current(ContextHolder holder) {
        return holder.current(LocalizationContext.class);
    }

    /**
     * Binds the given {@link LocalizationContext} for the active Vert.x context scope. Returns a
     * {@link ContextHolder.Scope} that restores the previous binding when closed.
     *
     * <p>Delegates to {@link ContextHolder#bind(Class, Object) holder.bind(LocalizationContext.class, ctx)}.
     *
     * @param holder the context holder to delegate to; must not be {@code null}
     * @param ctx    the context value to bind; must not be {@code null}
     * @return a scope that restores the prior binding on close
     * @throws NullPointerException  if {@code holder} or {@code ctx} is {@code null}
     * @throws IllegalStateException if called outside a Vert.x-associated context
     */
    public static ContextHolder.Scope bind(ContextHolder holder, LocalizationContext ctx) {
        return holder.bind(LocalizationContext.class, ctx);
    }

    /**
     * Returns the {@link Locale} from the currently bound {@link LocalizationContext}, or
     * {@code fallback} if no context is bound.
     *
     * @param holder   the context holder to delegate to; must not be {@code null}
     * @param fallback the locale to return when no context is bound; may be {@code null}
     * @return the locale from the bound context, or {@code fallback}
     */
    public static Locale locale(ContextHolder holder, Locale fallback) {
        return current(holder).map(LocalizationContext::locale).orElse(fallback);
    }

    /**
     * Returns the {@link ZoneId} from the currently bound {@link LocalizationContext}, or
     * {@code fallback} if no context is bound.
     *
     * @param holder   the context holder to delegate to; must not be {@code null}
     * @param fallback the zone to return when no context is bound; may be {@code null}
     * @return the zone from the bound context, or {@code fallback}
     */
    public static ZoneId zone(ContextHolder holder, ZoneId fallback) {
        return current(holder).map(LocalizationContext::zone).orElse(fallback);
    }
}
