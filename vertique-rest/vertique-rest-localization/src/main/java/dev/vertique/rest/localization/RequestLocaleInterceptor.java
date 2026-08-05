// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import dev.vertique.context.WarningThrottle;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.localization.config.LocalizationConfig;
import dev.vertique.localization.context.LocalizationContext;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Binds a typed {@link LocalizationContext} at REST inbound by resolving the request locale through
 * an ordered {@link LocaleSource} chain.
 *
 * <p>In {@link #beforeRequest(RoutingContext)} the interceptor walks the registered sources sorted
 * by the {@link OrderedExtension} contract (phase → priority ascending → orderKey) and binds the
 * first non-empty {@link ResolvedLocale}. If every source defers, the configured
 * {@code defaultLocale} is bound with {@code localeSource = "default-locale"}. A source that throws is
 * caught (WARN throttled per source class), treated as a deferral, and the chain continues — so a
 * misbehaving source or an unparseable header can never reject the request.
 *
 * <p>The bound {@link ContextHolder.Scope} is registered with
 * {@link RequestContextLifecycle#fromRoutingContext(RoutingContext)} so it is closed at request end
 * (LIFO, after every other end handler). If that registration fails — a framework-wiring defect where
 * the ROOT {@code RequestContextLifecycle} middleware did not run — the scope is closed and the
 * returned {@link io.vertx.core.Future} is failed (fail-fast); this branch is unreachable in a
 * correctly wired application. The bound {@link LocalizationContext} in {@link ContextHolder} is the
 * single source of truth; handler code reads it via {@code ContextHolder.current(LocalizationContext.class)}.
 *
 * <p>Runs at {@link #REQUEST_LOCALE_PRIORITY} ({@code Integer.MIN_VALUE + 1000}), before every other
 * request interceptor, so authentication and identity-resolution code paths can read the negotiated
 * locale.
 */
@Slf4j
@Singleton
public final class RequestLocaleInterceptor implements RequestInterceptor {

    /**
     * Priority constant: {@code Integer.MIN_VALUE + 1000}. Lower runs first, so this resolves the
     * locale before any other request interceptor. A future v2 response-localization interceptor must
     * use a distinct, numerically higher constant to keep its order relative to this one defined.
     */
    public static final int REQUEST_LOCALE_PRIORITY = Integer.MIN_VALUE + 1000;

    private static final String DEFAULT_LOCALE_SOURCE = "default-locale";
    private static final String DEFAULT_ZONE_SOURCE = "default-zone";

    private final ContextHolder holder;
    private final LocalizationConfig config;

    /** Sources sorted by {@link OrderedExtension#comparator()} (phase → priority → orderKey) once at construction. */
    private final List<LocaleSource> sources;

    /** Once-per-source-class WARN throttle for sources that throw during resolution. */
    private final WarningThrottle throwingSourceWarnThrottle = new WarningThrottle();

    /**
     * Creates the interceptor.
     *
     * @param holder  the request-scoped context holder (never {@code null})
     * @param sources the contributed locale sources (never {@code null}; may be empty)
     * @param config  the localization configuration supplying the default locale and zone
     */
    @Inject
    public RequestLocaleInterceptor(ContextHolder holder, Set<LocaleSource> sources, LocalizationConfig config) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.config = Objects.requireNonNull(config, "config");
        this.sources = sources.stream().sorted(OrderedExtension.comparator()).toList();
    }

    @Override
    public int priority() {
        return REQUEST_LOCALE_PRIORITY;
    }

    @Override
    public Future<Void> beforeRequest(RoutingContext rc) {
        ResolvedLocale resolved = resolve(rc);
        LocalizationContext context = new LocalizationContext(
                resolved.locale(),
                config.defaultZone(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                resolved.source(),
                DEFAULT_ZONE_SOURCE);
        // Runs on the request's duplicated Vert.x context, so holder.bind() satisfies its
        // duplicated-context write precondition. The duplication is Vert.x Web's doing — it
        // duplicates the context per request before any handler runs. RequestContextLifecycle plays
        // no part in it: its handle() only stores a Handle and registers an end handler, and it never
        // calls duplicate().
        //
        // The genuine dependency on RequestContextLifecycle is the separate fromRoutingContext(rc)
        // lookup below, which needs that ROOT middleware to have run for this request.
        ContextHolder.Scope scope = holder.bind(LocalizationContext.class, context);
        try {
            RequestContextLifecycle.fromRoutingContext(rc).onClose(scope);
        } catch (RuntimeException e) {
            // Registration failed — a framework-wiring defect (the ROOT RequestContextLifecycle
            // middleware did not run). Close the just-bound scope so the binding does not outlive this
            // call unregistered, and fail the request via the Future contract (not a sync throw).
            scope.close();
            return Future.failedFuture(e);
        }
        return Future.succeededFuture();
    }

    /**
     * Walks the source chain and returns the resolved locale, or the configured default when every
     * source defers. A source that throws {@link RuntimeException} is caught and treated as a
     * deferral. Package-private for unit testing the chain logic without binding.
     *
     * @param rc the current routing context
     * @return the resolved locale and source label; never {@code null}
     */
    ResolvedLocale resolve(RoutingContext rc) {
        for (LocaleSource source : sources) {
            Optional<ResolvedLocale> result;
            try {
                result = source.resolve(rc);
            } catch (RuntimeException e) {
                throwingSourceWarnThrottle.once(
                        source.getClass().getName(),
                        key -> log.warn(
                                "LocaleSource {} threw during locale resolution; deferring to the next source. "
                                        + "Further failures from this source are suppressed.",
                                key,
                                e));
                continue;
            }
            if (result != null && result.isPresent()) {
                return result.get();
            }
        }
        return ResolvedLocale.of(config.defaultLocale(), DEFAULT_LOCALE_SOURCE);
    }
}
