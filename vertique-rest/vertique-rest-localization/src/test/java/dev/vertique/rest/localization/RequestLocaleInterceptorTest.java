// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.localization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.localization.config.LocalizationConfig;
import dev.vertique.localization.context.LocalizationContext;
import dev.vertique.localization.locale.DefaultLocaleResolver;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.internal.ContextInternal;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies {@link RequestLocaleInterceptor}: the ordered chain selection ({@code resolve}) and the
 * end-to-end binding of {@link LocalizationContext} plus scope cleanup in {@code beforeRequest}.
 *
 * <p>The one request-driven case uses a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load a body read can succeed with zero bytes while the status code is
 * correct (issue #167). That case only drains the response to sequence the post-request assertions,
 * so it cannot flake on that today — the raw idiom is latent here, and would become a live race the
 * moment anyone asserted on the body, with no diff to hint why. A {@link WebClient} aggregates the
 * body into its {@code HttpResponse} before completing the send, so the hazard is removed by
 * construction rather than by every author remembering an idiom.
 */
class RequestLocaleInterceptorTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale SV = Locale.forLanguageTag("sv");
    private static final Locale FI = Locale.forLanguageTag("fi");

    private static LocalizationConfig config() {
        return new LocalizationConfig(EN, ZoneId.of("UTC"), List.of(EN, SV, FI), false, false, false, -1L);
    }

    private static AcceptLanguageLocaleSource acceptLanguageSource() {
        return new AcceptLanguageLocaleSource(new DefaultLocaleResolver(config()));
    }

    private static RoutingContext rcWithHeader(String value) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn(value);
        RoutingContext rc = mock(RoutingContext.class);
        when(rc.request()).thenReturn(request);
        return rc;
    }

    private static RequestLocaleInterceptor interceptor(ContextHolder holder, Set<LocaleSource> sources) {
        return new RequestLocaleInterceptor(holder, sources, config());
    }

    /** A locale source that always returns a fixed result. */
    private static final class FixedSource implements LocaleSource {
        private final ResolvedLocale value;
        private final int priority;

        FixedSource(ResolvedLocale value, int priority) {
            this.value = value;
            this.priority = priority;
        }

        @Override
        public Optional<ResolvedLocale> resolve(RoutingContext rc) {
            return Optional.ofNullable(value);
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    /** A locale source that always throws. */
    private static final class ThrowingSource implements LocaleSource {
        private final int priority;

        ThrowingSource(int priority) {
            this.priority = priority;
        }

        @Override
        public Optional<ResolvedLocale> resolve(RoutingContext rc) {
            throw new IllegalStateException("boom");
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    @Nested
    @DisplayName("resolve() — ordered chain selection")
    class Resolve {

        @Test
        @DisplayName("priority() equals REQUEST_LOCALE_PRIORITY (Integer.MIN_VALUE + 1000)")
        void priorityConstant() {
            RequestLocaleInterceptor interceptor = interceptor(mock(ContextHolder.class), Set.of());
            assertEquals(RequestLocaleInterceptor.REQUEST_LOCALE_PRIORITY, interceptor.priority());
            assertEquals(Integer.MIN_VALUE + 1000, RequestLocaleInterceptor.REQUEST_LOCALE_PRIORITY);
        }

        @Test
        @DisplayName("a default-priority app source wins over the late Accept-Language source")
        void appSourceWinsOverAcceptLanguage() {
            FixedSource app = new FixedSource(ResolvedLocale.of(FI, "query-param"), 0);
            RequestLocaleInterceptor interceptor =
                    interceptor(mock(ContextHolder.class), Set.of(app, acceptLanguageSource()));

            ResolvedLocale resolved = interceptor.resolve(rcWithHeader("sv-SE"));

            assertEquals(FI, resolved.locale());
            assertEquals("query-param", resolved.source());
        }

        @Test
        @DisplayName("with only the built-in source, Accept-Language is honored")
        void builtInResolvesAcceptLanguage() {
            RequestLocaleInterceptor interceptor =
                    interceptor(mock(ContextHolder.class), Set.of(acceptLanguageSource()));

            ResolvedLocale resolved = interceptor.resolve(rcWithHeader("sv-SE"));

            assertEquals(SV, resolved.locale());
            assertEquals("rest-accept-language", resolved.source());
        }

        @Test
        @DisplayName("when every source defers, the configured default locale is chosen")
        void noSourceMatchesBindsDefault() {
            RequestLocaleInterceptor interceptor =
                    interceptor(mock(ContextHolder.class), Set.of(acceptLanguageSource()));

            ResolvedLocale resolved = interceptor.resolve(rcWithHeader(null));

            assertEquals(EN, resolved.locale());
            assertEquals("default-locale", resolved.source());
        }

        @Test
        @DisplayName("a wildcard-only Accept-Language resolves to the configured default locale")
        void wildcardHeaderBindsDefault() {
            RequestLocaleInterceptor interceptor =
                    interceptor(mock(ContextHolder.class), Set.of(acceptLanguageSource()));

            ResolvedLocale resolved = interceptor.resolve(rcWithHeader("*"));

            assertEquals(EN, resolved.locale());
            assertEquals("default-locale", resolved.source());
        }

        @Test
        @DisplayName("a throwing source is caught and the chain continues to the next source")
        void throwingSourceIsCaughtAndChainContinues() {
            FixedSource next = new FixedSource(ResolvedLocale.of(FI, "query-param"), 10);
            RequestLocaleInterceptor interceptor =
                    interceptor(mock(ContextHolder.class), Set.of(new ThrowingSource(0), next));

            // Twice, to exercise the per-source-class WARN throttle without surfacing the exception.
            assertEquals(FI, interceptor.resolve(rcWithHeader("sv-SE")).locale());
            assertEquals(FI, interceptor.resolve(rcWithHeader("sv-SE")).locale());
        }
    }

    @Nested
    @ExtendWith(VertxExtension.class)
    @DisplayName("beforeRequest() — binds LocalizationContext and cleans up at request end")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    class BeforeRequest {

        /** Bound on its own statement (never inlined into a chain) so teardown can always close it. */
        private WebClient client;

        /**
         * Closes the {@link WebClient} created by the test that just ran.
         *
         * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns
         * once the underlying client has been asked to close, so there is no future to chain off and
         * no reason to keep the close inside the request chain where a failed request would skip it.
         */
        @AfterEach
        void closeClient() {
            if (client != null) {
                client.close();
            }
        }

        @Test
        @DisplayName("binds the resolved locale and closes the scope at request end")
        void bindsAndCleansUp(Vertx vertx, VertxTestContext testContext) {
            ContextHolder holder = new DefaultContextHolder();
            FixedSource app = new FixedSource(ResolvedLocale.of(FI, "query-param"), 0);
            RequestLocaleInterceptor interceptor = interceptor(holder, Set.of(app, acceptLanguageSource()));

            AtomicReference<Locale> boundDuringRequest = new AtomicReference<>();
            AtomicBoolean clearedAfterEnd = new AtomicBoolean(false);

            Router router = Router.router(vertx);
            router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
            router.route("/test").handler(rc -> {
                RequestContextLifecycle.fromRoutingContext(rc)
                        .afterClose(() -> clearedAfterEnd.set(
                                holder.current(LocalizationContext.class).isEmpty()));
                interceptor.beforeRequest(rc).onComplete(ar -> {
                    boundDuringRequest.set(holder.current(LocalizationContext.class)
                            .map(LocalizationContext::locale)
                            .orElse(null));
                    rc.response().end();
                });
            });

            // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .compose(server -> client.get(server.actualPort(), "127.0.0.1", "/test")
                            .putHeader(HttpHeaders.ACCEPT_LANGUAGE.toString(), "sv-SE")
                            .send()
                            .compose(resp ->
                                    io.vertx.core.Future.<Void>future(p -> vertx.setTimer(50, id -> p.complete())))
                            .eventually(() -> server.close()))
                    .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                        assertEquals(FI, boundDuringRequest.get(), "app source (priority 0) wins over Accept-Language");
                        assertTrue(clearedAfterEnd.get(), "scope closed at request end (binding cleared)");
                        testContext.completeNow();
                    })));
        }

        @Test
        @DisplayName("fails the request and closes the bound scope when lifecycle registration fails")
        void failsAndClosesScopeWhenLifecycleRegistrationFails(Vertx vertx, VertxTestContext testContext) {
            ContextHolder holder = new DefaultContextHolder();
            RequestLocaleInterceptor interceptor = interceptor(holder, Set.of(acceptLanguageSource()));
            ContextInternal duplicated = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            duplicated.runOnContext(v -> testContext.verify(() -> {
                // The mock RoutingContext has no RequestContextLifecycle Handle, so onClose registration
                // throws IllegalStateException after the holder has already been bound.
                RoutingContext rc = rcWithHeader("sv-SE");
                io.vertx.core.Future<Void> result = interceptor.beforeRequest(rc);
                assertTrue(result.failed(), "registration failure fails the request via the Future contract");
                assertInstanceOf(IllegalStateException.class, result.cause());
                assertTrue(
                        holder.current(LocalizationContext.class).isEmpty(),
                        "the just-bound scope is closed after a failed registration");
                testContext.completeNow();
            }));
        }
    }
}
