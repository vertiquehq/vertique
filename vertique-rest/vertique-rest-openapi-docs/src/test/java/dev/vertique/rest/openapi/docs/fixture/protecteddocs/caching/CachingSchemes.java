// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.core.security.scheme.ApiKey;
import dev.vertique.rest.core.security.scheme.MutualTls;
import dev.vertique.rest.core.security.scheme.OAuth2;
import dev.vertique.rest.core.security.scheme.OAuthFlows;
import dev.vertique.rest.core.security.scheme.OpenIdConnect;
import dev.vertique.rest.core.security.scheme.SecuritySchemeDescription;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.http.Cookie;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The security schemes of the caching fixture: the JWT bearer scheme the real JWT authentication
 * module registers, and one fixture handler per scheme kind. Each fixture handler describes its
 * scheme through {@link SecuritySchemeHandler#openApiDescription()} (the undescribed one describes
 * nothing) and authenticates a request carrying its one fixed credential, rejecting every other
 * request with {@code 401}.
 *
 * <p>An accepted request gets a Vert.x {@link User} and one {@link AuthenticationEvidence} entry
 * carrying a subject, so the framework resolves a non-anonymous identity for it. The authentication
 * handler is built with {@link SimpleAuthenticationHandler}, never as a lambda, as the framework's
 * authentication chain requires.
 *
 * <p>The mutual TLS handler cannot see a client certificate, since the tests run over plain HTTP; it
 * accepts a fixed marker header in its place. That header is a fixture stand-in, not a credential the
 * scheme kind defines.
 */
public final class CachingSchemes {

    /** The scheme of the real JWT handler, which protects the {@code management} document. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** The role the {@code management} document requires. */
    public static final String ADMIN_ROLE = "admin";

    /** The scheme whose handler describes nothing. */
    public static final String UNDESCRIBED = "undescribedAuth";

    /** The scheme of an API key in a request header. */
    public static final String HEADER_KEY = "headerKeyAuth";

    /** The scheme of an API key in a cookie. */
    public static final String COOKIE_KEY = "cookieKeyAuth";

    /** The scheme of an API key in a query parameter. */
    public static final String QUERY_KEY = "queryKeyAuth";

    /** The OAuth 2 scheme. */
    public static final String OAUTH2 = "oauthAuth";

    /** The OpenID Connect scheme. */
    public static final String OPEN_ID_CONNECT = "openIdAuth";

    /** The mutual TLS scheme. */
    public static final String MUTUAL_TLS = "mutualTlsAuth";

    /** The header the header API key travels in. */
    public static final String API_KEY_HEADER = "X-Api-Key";

    /** The cookie the cookie API key travels in. */
    public static final String API_KEY_COOKIE = "session";

    /** The query parameter the query API key travels in. */
    public static final String API_KEY_QUERY = "api_key";

    /** The header that stands in for a client certificate of the mutual TLS scheme. */
    public static final String MUTUAL_TLS_MARKER_HEADER = "X-Fixture-Client-Certificate";

    /** The only {@code Authorization} value the undescribed scheme accepts. */
    public static final String UNDESCRIBED_AUTHORIZATION = "Bearer undescribed-credential";

    /** The only header API key value accepted. */
    public static final String HEADER_KEY_VALUE = "header-key-credential";

    /** The only cookie API key value accepted. */
    public static final String COOKIE_KEY_VALUE = "cookie-key-credential";

    /** The only query API key value accepted. */
    public static final String QUERY_KEY_VALUE = "query-key-credential";

    /** The only {@code Authorization} value the OAuth 2 scheme accepts. */
    public static final String OAUTH2_AUTHORIZATION = "Bearer oauth-credential";

    /** The only {@code Authorization} value the OpenID Connect scheme accepts. */
    public static final String OPEN_ID_CONNECT_AUTHORIZATION = "Bearer openid-credential";

    /** The only marker header value the mutual TLS scheme accepts. */
    public static final String MUTUAL_TLS_MARKER = "fixture-client-certificate";

    /** The subject every fixture handler's evidence carries. */
    public static final String FIXTURE_SUBJECT = "fixture-caller";

    private CachingSchemes() {}

    /**
     * Returns the handler of {@value #UNDESCRIBED}: no description; accepts {@value
     * #UNDESCRIBED_AUTHORIZATION} in {@code Authorization}.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler undescribed() {
        return new FixtureSchemeHandler(
                UNDESCRIBED,
                Optional.empty(),
                DefaultAuthMethod.jwt(),
                ctx -> UNDESCRIBED_AUTHORIZATION.equals(ctx.request().getHeader("Authorization")));
    }

    /**
     * Returns the handler of {@value #HEADER_KEY}: an API key in the {@value #API_KEY_HEADER} header;
     * accepts {@value #HEADER_KEY_VALUE}.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler headerKey() {
        return new FixtureSchemeHandler(
                HEADER_KEY,
                Optional.of(ApiKey.header(API_KEY_HEADER)),
                DefaultAuthMethod.apiKey(),
                ctx -> HEADER_KEY_VALUE.equals(ctx.request().getHeader(API_KEY_HEADER)));
    }

    /**
     * Returns the handler of {@value #COOKIE_KEY}: an API key in the {@value #API_KEY_COOKIE} cookie;
     * accepts {@value #COOKIE_KEY_VALUE}.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler cookieKey() {
        return new FixtureSchemeHandler(
                COOKIE_KEY, Optional.of(ApiKey.cookie(API_KEY_COOKIE)), DefaultAuthMethod.apiKey(), ctx -> {
                    Cookie cookie = ctx.request().getCookie(API_KEY_COOKIE);
                    return cookie != null && COOKIE_KEY_VALUE.equals(cookie.getValue());
                });
    }

    /**
     * Returns the handler of {@value #QUERY_KEY}: an API key in the {@value #API_KEY_QUERY} query
     * parameter; accepts {@value #QUERY_KEY_VALUE}.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler queryKey() {
        return new FixtureSchemeHandler(
                QUERY_KEY,
                Optional.of(ApiKey.query(API_KEY_QUERY)),
                DefaultAuthMethod.apiKey(),
                ctx -> QUERY_KEY_VALUE.equals(ctx.request().getParam(API_KEY_QUERY)));
    }

    /**
     * Returns the handler of {@value #OAUTH2}: an OAuth 2 client-credentials flow; accepts {@value
     * #OAUTH2_AUTHORIZATION} in {@code Authorization}.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler oauth2() {
        OAuthFlows flows = OAuthFlows.builder()
                .clientCredentials(
                        URI.create("https://auth.example.test/oauth/token"), Map.of("docs.read", "Read documents"))
                .build();
        return new FixtureSchemeHandler(
                OAUTH2,
                Optional.of(OAuth2.of(flows)),
                DefaultAuthMethod.jwt(),
                ctx -> OAUTH2_AUTHORIZATION.equals(ctx.request().getHeader("Authorization")));
    }

    /**
     * Returns the handler of {@value #OPEN_ID_CONNECT}: an OpenID Connect discovery URL; accepts
     * {@value #OPEN_ID_CONNECT_AUTHORIZATION} in {@code Authorization}.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler openIdConnect() {
        return new FixtureSchemeHandler(
                OPEN_ID_CONNECT,
                Optional.of(OpenIdConnect.of(URI.create("https://id.example.test/.well-known/openid-configuration"))),
                DefaultAuthMethod.jwt(),
                ctx -> OPEN_ID_CONNECT_AUTHORIZATION.equals(ctx.request().getHeader("Authorization")));
    }

    /**
     * Returns the handler of {@value #MUTUAL_TLS}: mutual TLS; accepts the marker header {@value
     * #MUTUAL_TLS_MARKER_HEADER} with {@value #MUTUAL_TLS_MARKER} in place of a client certificate.
     *
     * @return the handler
     */
    public static SecuritySchemeHandler mutualTls() {
        return new FixtureSchemeHandler(
                MUTUAL_TLS,
                Optional.of(MutualTls.of()),
                DefaultAuthMethod.mtls(),
                ctx -> MUTUAL_TLS_MARKER.equals(ctx.request().getHeader(MUTUAL_TLS_MARKER_HEADER)));
    }

    /** A fixture handler accepting one fixed credential and describing its scheme as given. */
    private static final class FixtureSchemeHandler implements SecuritySchemeHandler {

        private final String schemeName;
        private final Optional<SecuritySchemeDescription> description;
        private final AuthMethod method;
        private final Predicate<RoutingContext> accepts;

        FixtureSchemeHandler(
                String schemeName,
                Optional<SecuritySchemeDescription> description,
                AuthMethod method,
                Predicate<RoutingContext> accepts) {
            this.schemeName = schemeName;
            this.description = description;
            this.method = method;
            this.accepts = accepts;
        }

        @Override
        public String schemeName() {
            return schemeName;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            AuthenticationHandler handler = SimpleAuthenticationHandler.create().authenticate(this::authenticate);
            registry.authenticationHandler(handler);
        }

        @Override
        public Optional<SecuritySchemeDescription> openApiDescription() {
            return description;
        }

        private Future<User> authenticate(RoutingContext ctx) {
            if (!accepts.test(ctx)) {
                return Future.failedFuture(new HttpException(401));
            }
            // One scheme guards each route here, so the evidence is appended exactly once.
            RestAuthenticationEvidence.append(
                    ctx,
                    new AuthenticationEvidence(
                            method,
                            Optional.of(schemeName),
                            Instant.now(),
                            Optional.empty(),
                            new CustomVerificationSource("caching-fixture", Map.of()),
                            Map.of("sub", FIXTURE_SUBJECT)));
            return Future.succeededFuture(User.create(new JsonObject().put("sub", FIXTURE_SUBJECT)));
        }
    }
}
