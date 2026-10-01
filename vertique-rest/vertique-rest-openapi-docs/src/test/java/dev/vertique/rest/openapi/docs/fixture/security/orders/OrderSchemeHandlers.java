// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.orders;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.ApiKey;
import dev.vertique.rest.core.security.SecuritySchemeDescription;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import io.vertx.core.Future;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The fixture scheme handlers of the {@code orders} deployment, beside the real JWT handler that
 * registers {@value #BEARER_AUTH}. Every handler's authentication handler rejects each request with
 * {@code 401}, so the registrar finds a handler for each declared scheme; no request in the tests
 * reaches one. Every call of a described handler's {@code openApiDescription()} is counted per
 * scheme in a process-wide counter, which a test resets before deploying.
 */
public final class OrderSchemeHandlers {

    /** The scheme of the real JWT handler. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** The described API-key scheme. */
    public static final String API_KEY_AUTH = "apiKeyAuth";

    /** The header {@value #API_KEY_AUTH} is carried in. */
    public static final String API_KEY_HEADER = "X-Api-Key";

    /** The scheme only the hidden operation references; its handler describes nothing. */
    public static final String HIDDEN_ONLY = "hiddenOnly";

    /** The described scheme no operation references. */
    public static final String UNUSED = "unused";

    /** The description calls of the described handlers since the last reset, keyed by scheme name. */
    private static final ConcurrentMap<String, AtomicInteger> DESCRIPTION_CALLS = new ConcurrentHashMap<>();

    private OrderSchemeHandlers() {}

    /** Resets every described handler's description call counter to zero. */
    public static void resetDescriptionCalls() {
        DESCRIPTION_CALLS.clear();
    }

    /**
     * Returns how many times the described handler of a scheme was asked for its description since
     * the last reset.
     *
     * @param schemeName the scheme name of a described handler
     * @return the call count
     */
    public static int descriptionCalls(String schemeName) {
        AtomicInteger calls = DESCRIPTION_CALLS.get(schemeName);
        return calls == null ? 0 : calls.get();
    }

    /**
     * Returns the handler of {@value #API_KEY_AUTH}, describing an API key in the {@value
     * #API_KEY_HEADER} header.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler apiKeyAuth() {
        return new Described(API_KEY_AUTH, ApiKey.header(API_KEY_HEADER));
    }

    /**
     * Returns the handler of {@value #HIDDEN_ONLY}, which keeps the default description: none.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler hiddenOnly() {
        return new Rejecting(HIDDEN_ONLY);
    }

    /**
     * Returns the handler of {@value #UNUSED}, describing an API key in a cookie.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler unused() {
        return new Described(UNUSED, ApiKey.cookie("session"));
    }

    /** A handler that rejects every request and keeps the default, empty description. */
    private static class Rejecting implements SecuritySchemeHandler {

        private final String schemeName;

        Rejecting(String schemeName) {
            this.schemeName = schemeName;
        }

        @Override
        public String schemeName() {
            return schemeName;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            // A Vert.x-built handler, not a lambda: an OR of schemes chains its handlers, which
            // requires Vert.x's internal authentication handler type.
            AuthenticationHandler rejectAll = SimpleAuthenticationHandler.create()
                    .authenticate(ctx -> Future.failedFuture(new HttpException(401)));
            registry.authenticationHandler(rejectAll);
        }
    }

    /** A handler that rejects every request and describes its scheme. Counts each description call. */
    private static final class Described extends Rejecting {

        private final SecuritySchemeDescription description;

        Described(String schemeName, SecuritySchemeDescription description) {
            super(schemeName);
            this.description = description;
        }

        @Override
        public Optional<SecuritySchemeDescription> openApiDescription() {
            DESCRIPTION_CALLS
                    .computeIfAbsent(schemeName(), name -> new AtomicInteger())
                    .incrementAndGet();
            return Optional.of(description);
        }
    }
}
