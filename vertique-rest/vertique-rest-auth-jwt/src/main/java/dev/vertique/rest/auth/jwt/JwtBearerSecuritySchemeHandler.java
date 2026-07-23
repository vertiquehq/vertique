// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.security.CredentialRejectionReporter;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.JwksVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.ChainAuthHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.impl.AuthenticationHandlerInternal;
import io.vertx.ext.web.impl.UserContextInternal;
import java.security.SignatureException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configures a {@link JWTAuthHandler} for a named OpenAPI bearer authentication security scheme,
 * and wraps its success/failure paths to emit typed security evidence and rejection events.
 *
 * <p>This handler validates incoming JWT bearer tokens and populates the routing context with
 * the authenticated user principal. It is contributed to the framework's
 * {@link SecuritySchemeHandler} multibinding by {@link JwtAuthModule}.
 *
 * <h3>Authentication approach</h3>
 * Rather than delegating to {@link JWTAuthHandler} (which internally casts the routing context to
 * {@code RoutingContextInternal}, a Vert.x-internal interface), this handler:
 * <ol>
 *   <li>Parses the {@code Authorization} header directly.</li>
 *   <li>Calls {@link JWTAuth#authenticate(JsonObject)} with the extracted token.</li>
 *   <li>On success: sets the verified {@link User} on the context, appends
 *       {@link AuthenticationEvidence}, and calls {@code ctx.next()}.</li>
 *   <li>On failure: derives a stable reason code, reports the rejection, and calls
 *       {@code ctx.fail(statusCode, cause)}.</li>
 * </ol>
 *
 * <p>The credential extraction and verification (steps 1–2 plus issuer/audience enforcement and
 * rejection-event emission) live in {@link #authenticate(RoutingContext)}, which returns the
 * verified {@link User} <em>without</em> mutating the routing context. {@link #handle(RoutingContext)}
 * calls it and applies the routing-context side effects ({@code setUser}/{@code next} on success,
 * {@code fail} on failure). This split lets the scheme be one alternative in a Vert.x
 * {@link io.vertx.ext.web.handler.ChainAuthHandler} OR chain (used when an operation declares two or
 * more alternative bearer {@code @SecurityRequirement}s): the chain invokes only {@code authenticate}
 * on each member and owns the side effects. See {@code DelegatingJwtAuthHandler} for the
 * deliberate, version-pinned dependency on the Vert.x {@code impl} interface that makes the scheme
 * chain-composable.
 *
 * <h3>Success path</h3>
 * After {@link JWTAuth#authenticate} succeeds, this handler:
 * <ol>
 *   <li>Enforces the configured {@code issuer} and {@code audience} from {@link JwtValidationConfig}
 *       against the token's {@code iss}/{@code aud} claims (see <em>Issuer/audience enforcement</em>
 *       below). A failed check rejects the request and stops here.</li>
 *   <li>Sets the verified {@link User} principal on the routing context.</li>
 *   <li>Builds an {@link AuthenticationEvidence} with a {@link JwksVerificationSource} (issuer
 *       and JWKS URI derived from {@link JwtValidationConfig}), the {@code exp} claim mapped to
 *       {@code notAfter}, and {@code sub} in safe attributes (no raw token material).</li>
 *   <li>Stashes the evidence via {@link RestAuthenticationEvidence#append}.</li>
 *   <li>Calls {@code ctx.next()} to continue the handler chain.</li>
 * </ol>
 *
 * <h3>Issuer/audience enforcement (defense-in-depth)</h3>
 * After the underlying {@link JWTAuth} verifies the token's signature and time-based claims, this
 * handler additionally <strong>enforces</strong> the configured {@code issuer} and {@code audience}
 * from {@link JwtValidationConfig} post-authentication, rejecting with reason code
 * {@code JWT_ISSUER_INVALID} or {@code JWT_AUDIENCE_INVALID} (HTTP 401) on a mismatch. This is
 * defense-in-depth: only {@link JwtAuthFactory#fromJwks(io.vertx.core.Vertx, String,
 * JwtValidationConfig)} configures issuer/audience as {@code JWTOptions} on the {@link JWTAuth}, so
 * an app that built its {@link JWTAuth} via {@code fromSymmetricKey}/{@code fromPublicKey} (or the
 * no-config {@code fromJwks}) would otherwise get no issuer/audience enforcement even when
 * {@code jwt.validation} is configured. Enforcing here means {@code jwt.validation.issuer} and
 * {@code jwt.validation.audience} are honored regardless of how the {@link JWTAuth} was built.
 *
 * <p>Audience enforcement uses <em>any-match</em> semantics: per RFC 7519 the {@code aud} claim may
 * be a single string or an array, and the token is accepted iff its audience set overlaps the
 * configured set.
 *
 * <p>The {@code clockSkewSeconds} value and the time-claim ({@code exp}/{@code nbf}) validation it
 * governs are applied by {@link JWTAuth} at construction (via {@link JwtAuthFactory}), <em>not</em>
 * here: this handler runs after Vert.x has already performed the time-claim check, so there is no
 * post-hoc clock-skew enforcement.
 *
 * <h3>Failure path</h3>
 * When authentication fails, this handler:
 * <ol>
 *   <li>Inspects the failure exception to derive a stable {@code reasonCode}
 *       ({@code BEARER_MISSING}, {@code BEARER_MALFORMED}, {@code JWT_EXPIRED},
 *       {@code JWT_SIGNATURE_INVALID}, {@code JWT_AUDIENCE_INVALID}, {@code JWT_ISSUER_INVALID},
 *       {@code JWT_ALG_UNSUPPORTED}, or {@code JWT_INVALID}).</li>
 *   <li>Calls {@link CredentialRejectionReporter#report} with a pre-auth
 *       {@link JwksVerificationSource} (issuer/JWKS URI from config; kid/alg empty since the JWT
 *       header is not available before verification completes).</li>
 *   <li>Delegates the original {@code ctx.fail(...)} to continue Vert.x error processing.</li>
 * </ol>
 *
 * <p>For custom post-authentication claim validation, provide a {@link JwtClaimsValidator}
 * binding via {@link JwtAuthModule}'s optional binding. The validator is applied per-route
 * by {@link JwtClaimsValidatorContributor}, which runs after the JWT auth handler.
 *
 * <p>The scheme name defaults to {@code "bearerAuth"} when the {@code "jwt"} config section omits
 * {@code schemeName} and no application-supplied {@link JwtAuthConfig} override is bound (see
 * {@link JwtAuthModule}).
 *
 * @see JwtAuthModule
 * @see JwtValidationConfig
 * @see RestAuthenticationEvidence
 * @see CredentialRejectionReporter
 */
public class JwtBearerSecuritySchemeHandler implements SecuritySchemeHandler, Handler<RoutingContext> {

    /**
     * Routing-context key under which {@link #authenticate(RoutingContext)} stashes the built
     * {@link AuthenticationEvidence} after a successful verification, for the caller to append once
     * {@code ctx.user()} is set.
     *
     * <p>This indirection is what keeps the OR-chain and single-scheme evidence identical:
     * {@code authenticate(...)} cannot append the evidence itself (it must not mutate the routing
     * context — the chain owns {@code setUser}/{@code next}), so it stashes the fully-built evidence
     * here. The single-scheme path ({@link #handle(RoutingContext)}) reads and appends it after
     * {@code setUser}; the OR-chain path ({@link DelegatingJwtAuthHandler#postAuthentication(RoutingContext)})
     * reads and appends it after the chain has set the user. Exactly one of those two paths runs per
     * request, so the evidence is appended exactly once.
     */
    private static final String EVIDENCE_KEY = JwtBearerSecuritySchemeHandler.class.getName() + ".jwtEvidence";

    private final String schemeName;
    private final JWTAuth jwtAuth;
    private final JwtValidationConfig validationConfig;
    private final CredentialRejectionReporter rejectionReporter;

    /**
     * Creates a new handler for the given security scheme name, JWT auth provider, validation
     * config, and rejection reporter.
     *
     * @param schemeName        the OpenAPI security scheme name (e.g., {@code "bearerAuth"})
     * @param jwtAuth           the Vert.x JWT authentication provider
     * @param validationConfig  JWT validation configuration (issuer, audience, clock skew)
     * @param rejectionReporter SPI for reporting credential rejections with structured reason codes
     */
    public JwtBearerSecuritySchemeHandler(
            String schemeName,
            JWTAuth jwtAuth,
            JwtValidationConfig validationConfig,
            CredentialRejectionReporter rejectionReporter) {
        this.schemeName = Objects.requireNonNull(schemeName, "schemeName");
        this.jwtAuth = Objects.requireNonNull(jwtAuth, "jwtAuth");
        this.validationConfig = Objects.requireNonNull(validationConfig, "validationConfig");
        this.rejectionReporter = Objects.requireNonNull(rejectionReporter, "rejectionReporter");
    }

    /**
     * Package-private factory used by tests to inject a mock {@link JWTAuth}, bypassing real
     * Vert.x JWT auth processing. Tests mock {@link JWTAuth#authenticate(JsonObject)} to control
     * success and failure paths without a live router.
     *
     * @param schemeName        the OpenAPI security scheme name
     * @param jwtAuth           the mock or stub JWT auth provider
     * @param validationConfig  JWT validation configuration
     * @param rejectionReporter SPI for reporting credential rejections
     * @return a handler instance wired with the given JWT auth provider
     */
    static JwtBearerSecuritySchemeHandler forTesting(
            String schemeName,
            JWTAuth jwtAuth,
            JwtValidationConfig validationConfig,
            CredentialRejectionReporter rejectionReporter) {
        return new JwtBearerSecuritySchemeHandler(schemeName, jwtAuth, validationConfig, rejectionReporter);
    }

    // --- SecuritySchemeHandler ---

    /**
     * Returns the OpenAPI security scheme name this handler is bound to.
     *
     * @return the security scheme name
     */
    @Override
    public String schemeName() {
        return schemeName;
    }

    /**
     * Registers a {@link JWTAuthHandler} wrapper on the scheme-scoped {@link SecuritySchemeRegistry}.
     * The registered handler routes invocations through {@link #handle(RoutingContext)} so the
     * direct-authentication path emits typed evidence and rejection events.
     *
     * @param registry the scheme-scoped registry to configure
     */
    @Override
    public void configure(SecuritySchemeRegistry registry) {
        registry.authenticationHandler(new DelegatingJwtAuthHandler(JWTAuthHandler.create(jwtAuth)));
    }

    // --- Handler<RoutingContext> ---

    /**
     * Handles a routing context by parsing the {@code Authorization} header and calling
     * {@link JWTAuth#authenticate(JsonObject)} directly.
     *
     * <p>This avoids delegating to {@link JWTAuthHandler} which internally casts the routing
     * context to {@code RoutingContextInternal} (a Vert.x-internal interface), causing a
     * {@link ClassCastException} when a custom wrapper is passed.
     *
     * <p>On success the verified {@link User} is set on the context, evidence is appended, and
     * {@code ctx.next()} is called. On failure the rejection is reported and {@code ctx.fail()}
     * is called with the derived status code and cause.
     *
     * <p>This method delegates the actual credential extraction and verification to
     * {@link #authenticate(RoutingContext)}, then applies the success/failure routing-context
     * side effects ({@code setUser}/{@code next} on success, {@code fail} on failure). The split
     * exists so the same verification logic can be reused by
     * {@link DelegatingJwtAuthHandler#authenticate(RoutingContext)} when this scheme is composed
     * into a Vert.x {@link ChainAuthHandler} OR chain, where the chain — not this handler — owns
     * the routing-context side effects.
     *
     * @param ctx the current routing context
     */
    @Override
    public void handle(RoutingContext ctx) {
        authenticate(ctx).onComplete(ar -> {
            if (ar.succeeded()) {
                User user = ar.result();
                ((UserContextInternal) ctx.userContext()).setUser(user);
                // Single-scheme path: append the evidence authenticate(...) stashed (built from the
                // verified user), now that the user is set. The OR-chain path never reaches here — it
                // appends via DelegatingJwtAuthHandler.postAuthentication — so there is no double-append.
                appendStashedEvidence(ctx, user);
                ctx.next();
            } else {
                failContext(ctx, ar.cause());
            }
        });
    }

    /**
     * Reads the {@link AuthenticationEvidence} stashed by {@link #authenticate(RoutingContext)} under
     * {@link #EVIDENCE_KEY} and appends it via {@link RestAuthenticationEvidence#append}, clearing the
     * stash so a later read cannot append it twice. Falls back to rebuilding the evidence from the
     * given user when the stash is absent (defensive — every successful {@code authenticate} stashes).
     *
     * <p>Called from exactly one path per request: {@link #handle(RoutingContext)} on the single-scheme
     * route, or {@link DelegatingJwtAuthHandler#postAuthentication(RoutingContext)} on the OR-chain
     * route. Both run after {@code ctx.user()} is set, so the appended evidence matches the verified
     * principal identically across both paths.
     *
     * @param ctx  the current routing context (user already set)
     * @param user the verified principal, used only as a fallback evidence source
     */
    private void appendStashedEvidence(RoutingContext ctx, User user) {
        AuthenticationEvidence stashed = ctx.remove(EVIDENCE_KEY);
        RestAuthenticationEvidence.append(ctx, stashed != null ? stashed : buildEvidence(user));
    }

    /**
     * Routes an authentication failure from {@link #authenticate(RoutingContext)} onto the routing
     * context, preserving the exact pre-refactor {@code ctx.fail(...)} call shape for each failure
     * kind so the single-scheme route behaves byte-for-byte as before:
     * <ul>
     *   <li>missing / malformed {@code Authorization} header — the {@link HttpException} carries no
     *       cause, so this fails with the bare status code via {@code ctx.fail(statusCode)},
     *       matching the old {@code ctx.fail(401)};</li>
     *   <li>provider auth failure or claim-enforcement rejection — the {@link HttpException} carries
     *       a cause, so this fails with the {@link HttpException} itself via {@code ctx.fail(error)},
     *       matching the old {@code ctx.fail(new HttpException(...))}.</li>
     * </ul>
     *
     * @param ctx   the current routing context
     * @param error the failure from {@code authenticate(...)} (always an {@link HttpException})
     */
    private static void failContext(RoutingContext ctx, Throwable error) {
        if (error instanceof HttpException httpEx && httpEx.getCause() == null) {
            // Missing / malformed header: the HttpException carries only status 401 and no cause.
            // Preserve the old ctx.fail(401) (bare int) shape exactly.
            ctx.fail(401);
        } else {
            ctx.fail(error);
        }
    }

    /**
     * Extracts and verifies the bearer credential from the {@code Authorization} header WITHOUT
     * mutating the routing context, returning the verified {@link User} on success or a failed
     * {@link Future} (an {@link HttpException} carrying the intended status code) on failure.
     *
     * <p>This is the {@link ChainAuthHandler}-composable entry point. It performs exactly the same
     * token extraction, issuer/audience enforcement, evidence emission, and rejection-event
     * emission as the direct {@link #handle(RoutingContext)} path, but it does <strong>not</strong>
     * call {@code ctx.setUser(...)}, {@code ctx.next()}, or {@code ctx.fail(...)}: those side
     * effects are owned by the caller. When this scheme is one alternative in a
     * {@code ChainAuthHandler.any()} OR chain, the chain invokes only this method on each member,
     * advancing to the next alternative when the returned future fails with an {@link HttpException}.
     *
     * <p>The typed {@link AuthenticationEvidence} is built here but NOT appended — it is stashed on a
     * routing-context-local key ({@link #EVIDENCE_KEY}) so the caller can append it once
     * {@code ctx.user()} is set. The single-scheme route ({@link #handle(RoutingContext)}) appends it
     * after {@code setUser}; the OR-chain route
     * ({@link DelegatingJwtAuthHandler#postAuthentication(RoutingContext)}) appends it after the chain
     * sets the user. Exactly one of those two paths runs per request, so the evidence is appended
     * exactly once and is identical across both. Stashing (rather than appending) here is required
     * because this method must not mutate the routing context — the chain owns {@code setUser}, and
     * appending evidence before the user is set would diverge from the single-scheme ordering. On the
     * failure path this method emits the {@code CredentialRejected} event (via
     * {@link CredentialRejectionReporter}) before returning the failed future, so the rejection is
     * recorded regardless of whether the caller is a single-scheme route or an OR chain.
     *
     * @param ctx the current routing context (read-only; never mutated by this method)
     * @return a future completing with the verified {@link User} on success, or failing with an
     *     {@link HttpException} (status {@code 401}) on any authentication or claim-enforcement
     *     failure
     */
    public Future<User> authenticate(RoutingContext ctx) {
        String authorization = ctx.request().getHeader("Authorization");

        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            // No bearer token — report rejection and fail with 401.
            return reportAndFailedFuture(ctx, authorization == null ? "BEARER_MISSING" : "BEARER_MALFORMED");
        }

        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            return reportAndFailedFuture(ctx, "BEARER_MALFORMED");
        }

        // Authenticate the token via the Vert.x JWT provider.
        // Wrapped in try-catch: some JWTAuth implementations throw synchronously on malformed tokens
        // (e.g., base64 decode failure) before returning a Future.
        Future<User> authFuture;
        try {
            authFuture = jwtAuth.authenticate(new TokenCredentials(token));
        } catch (RuntimeException e) {
            return reportAndFailedFuture(ctx, e, 401);
        }
        return authFuture.transform(ar -> {
            if (ar.succeeded()) {
                User user = ar.result();

                // Defense-in-depth: enforce the configured issuer/audience post-authentication, so
                // jwt.validation is honored regardless of how the app built its JWTAuth (only
                // JwtAuthFactory.fromJwks(..., JwtValidationConfig) sets these as JWTOptions).
                JsonObject claims = user.principal() != null ? user.principal() : new JsonObject();
                Optional<String> claimReason = checkIssuer(claims).or(() -> checkAudience(claims));
                if (claimReason.isPresent()) {
                    return reportAndFailedFutureWithReason(ctx, claimReason.get());
                }

                // Stash the built evidence on a ctx-local key WITHOUT appending it: ctx.user() is not
                // set yet on this path, and appending is the caller's job once the user is bound. The
                // single-scheme path (handle) and the OR-chain path (postAuthentication) each read and
                // append this stash after setUser — exactly one of them runs per request.
                ctx.put(EVIDENCE_KEY, buildEvidence(user));
                return Future.succeededFuture(user);
            }
            return reportAndFailedFuture(ctx, ar.cause(), 401);
        });
    }

    // --- Post-authentication claim enforcement ---

    /**
     * Checks the configured issuer against the {@code iss} claim of an already-authenticated token,
     * returning a rejection reason code when the check fails. When {@link JwtValidationConfig#issuer()}
     * is {@code null}, no check is applied (returns empty). A missing or mismatched {@code iss}
     * yields {@code JWT_ISSUER_INVALID}.
     *
     * <p>This is a pure check with no side effects: it neither fails the routing context nor emits
     * a rejection event — the caller ({@link #authenticate(RoutingContext)}) does that with the
     * returned reason code, so the same logic serves both the direct route and the OR-chain path.
     *
     * @param claims the decoded JWT body (never {@code null})
     * @return the rejection reason code when the issuer is invalid; {@link Optional#empty()} when
     *     the issuer is valid or unconfigured
     */
    private Optional<String> checkIssuer(JsonObject claims) {
        String expected = validationConfig.issuer();
        if (expected == null) {
            return Optional.empty();
        }
        String iss = claims.getString("iss");
        if (iss == null || !expected.equals(iss)) {
            return Optional.of("JWT_ISSUER_INVALID");
        }
        return Optional.empty();
    }

    /**
     * Checks the configured audience against the {@code aud} claim of an already-authenticated token
     * using any-match semantics, returning a rejection reason code when the check fails. When
     * {@link JwtValidationConfig#audience()} is {@code null} or empty, no check is applied (returns
     * empty). Per RFC 7519 the {@code aud} claim may be a single string or a JSON array of strings;
     * the token is valid iff its audience set overlaps the configured set. A disjoint (including
     * absent/empty) token audience yields {@code JWT_AUDIENCE_INVALID}.
     *
     * <p>This is a pure check with no side effects: it neither fails the routing context nor emits
     * a rejection event — the caller ({@link #authenticate(RoutingContext)}) does that with the
     * returned reason code, so the same logic serves both the direct route and the OR-chain path.
     *
     * @param claims the decoded JWT body (never {@code null})
     * @return the rejection reason code when the audience is invalid; {@link Optional#empty()} when
     *     the audience overlaps or is unconfigured
     */
    private Optional<String> checkAudience(JsonObject claims) {
        List<String> expected = validationConfig.audience();
        if (expected == null || expected.isEmpty()) {
            return Optional.empty();
        }
        Set<String> tokenAud = extractAudiences(claims.getValue("aud"));
        boolean overlaps = expected.stream().anyMatch(tokenAud::contains);
        if (!overlaps) {
            return Optional.of("JWT_AUDIENCE_INVALID");
        }
        return Optional.empty();
    }

    /**
     * Extracts the audience values from the raw {@code aud} claim, which per RFC 7519 may be a
     * single string or a JSON array of strings. A {@code null}, absent, or non-string/non-array
     * value yields an empty set; array elements that are {@code null} are skipped.
     *
     * @param aud the raw {@code aud} claim value; may be {@code null}
     * @return the set of audience strings (never {@code null}, possibly empty)
     */
    private static Set<String> extractAudiences(Object aud) {
        if (aud instanceof String s) {
            return Set.of(s);
        }
        if (aud instanceof JsonArray array) {
            return array.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    /**
     * Reports a credential rejection whose reason code is derived from a provider exception, then
     * returns a failed {@link Future} carrying an {@link HttpException} with the intended status
     * code and the original cause. Used for the {@link JWTAuth#authenticate} failure path.
     *
     * <p>Wrapping the cause in an {@link HttpException} preserves the status code through the
     * framework's error pipeline: without it, a raw {@link RuntimeException} from
     * {@code jwtAuth.authenticate()} may be re-mapped to 400/500, losing the intended 401. When this
     * scheme is composed in a {@link ChainAuthHandler} OR chain, the {@code HttpException(401)}
     * signals an auth miss so the chain tries the next alternative.
     *
     * @param ctx        the current routing context (read-only)
     * @param cause      the provider exception driving the reason code
     * @param statusCode the HTTP status code to fail with (typically {@code 401})
     * @return a failed future carrying {@code HttpException(statusCode, cause)}
     */
    private Future<User> reportAndFailedFuture(RoutingContext ctx, Throwable cause, int statusCode) {
        rejectionReporter.report(
                ctx,
                DefaultAuthMethod.jwt(),
                Optional.empty(),
                Optional.of(buildVerificationSource()),
                deriveReasonCode(cause, statusCode),
                Map.of());
        return Future.failedFuture(new HttpException(statusCode, cause));
    }

    /**
     * Reports a credential rejection with an explicit reason code (rather than one derived from an
     * exception) and returns a failed {@link Future} carrying an {@link HttpException(401)}. Used
     * for the missing/empty/malformed {@code Authorization} header cases, where the reason code is
     * known directly ({@code BEARER_MISSING} / {@code BEARER_MALFORMED}) without an underlying
     * provider exception.
     *
     * <p>The failed future carries an {@link HttpException} with status {@code 401} and no cause —
     * matching the pre-refactor {@code ctx.fail(401)} behavior (a bare 401 with no body cause) while
     * still signalling an auth miss to a {@link ChainAuthHandler} OR chain.
     *
     * @param ctx        the current routing context (read-only)
     * @param reasonCode the explicit, stable rejection reason code
     * @return a failed future carrying {@code HttpException(401)}
     */
    private Future<User> reportAndFailedFuture(RoutingContext ctx, String reasonCode) {
        rejectionReporter.report(
                ctx,
                DefaultAuthMethod.jwt(),
                Optional.empty(),
                Optional.of(buildVerificationSource()),
                reasonCode,
                Map.of());
        return Future.failedFuture(new HttpException(401));
    }

    /**
     * Reports a credential rejection with an explicit reason code from the post-authentication
     * claim-enforcement gate ({@code JWT_ISSUER_INVALID} / {@code JWT_AUDIENCE_INVALID}) and returns
     * a failed {@link Future} carrying an {@link HttpException(401)} whose cause is a
     * {@link SecurityException} naming the reason code.
     *
     * <p>The {@link HttpException} preserves the 401 status through the error pipeline; the
     * {@link SecurityException} cause matches the pre-refactor behavior. When composed in a
     * {@link ChainAuthHandler} OR chain, the {@code HttpException(401)} signals an auth miss so the
     * chain tries the next alternative.
     *
     * @param ctx        the current routing context (read-only)
     * @param reasonCode the explicit claim-enforcement reason code
     * @return a failed future carrying {@code HttpException(401, SecurityException(reasonCode))}
     */
    private Future<User> reportAndFailedFutureWithReason(RoutingContext ctx, String reasonCode) {
        rejectionReporter.report(
                ctx,
                DefaultAuthMethod.jwt(),
                Optional.empty(),
                Optional.of(buildVerificationSource()),
                reasonCode,
                Map.of());
        return Future.failedFuture(new HttpException(401, new SecurityException(reasonCode)));
    }

    // --- Reason-code derivation ---

    /**
     * Derives a stable reason code from the exception produced by the JWT authentication call.
     *
     * <p>The Vert.x {@link JWTAuth} provider wraps authentication failures in an
     * {@link HttpException}. Parse-phase failures carry no cause; verification-phase failures
     * carry the original provider exception as the {@link Throwable#getCause() cause}.
     *
     * @param exception  the exception from the failed authenticate future; may be {@code null}
     * @param statusCode the HTTP status code to use when no exception is available
     * @return a stable, machine-readable reason code; never blank
     */
    private static String deriveReasonCode(Throwable exception, int statusCode) {
        if (exception == null) {
            return "JWT_INVALID";
        }

        // JWTAuth wraps all failures in HttpException.
        if (exception instanceof HttpException httpEx) {
            if (statusCode == 400) {
                // 400 indicates the Authorization header was syntactically malformed
                return "BEARER_MALFORMED";
            }
            // 401 with no cause: the Authorization header was missing or used the wrong scheme
            Throwable cause = httpEx.getCause();
            if (cause == null) {
                return "BEARER_MISSING";
            }
            return deriveReasonCodeFromCause(cause);
        }

        return deriveReasonCodeFromCause(exception);
    }

    /**
     * Derives a reason code from the underlying provider-level exception (the cause wrapped
     * inside the {@link HttpException} by the JWT auth provider).
     *
     * @param cause the provider-level exception; never {@code null}
     * @return a stable reason code
     */
    private static String deriveReasonCodeFromCause(Throwable cause) {
        if (cause instanceof SignatureException) {
            return "JWT_SIGNATURE_INVALID";
        }

        String message = cause.getMessage();
        if (message == null) {
            return "JWT_INVALID";
        }

        if (message.contains("token expired")) {
            return "JWT_EXPIRED";
        }
        if (message.startsWith("Invalid JWT audience")) {
            return "JWT_AUDIENCE_INVALID";
        }
        if (message.equals("Invalid JWT issuer")) {
            return "JWT_ISSUER_INVALID";
        }
        if (message.startsWith("Algorithm not supported/allowed")) {
            return "JWT_ALG_UNSUPPORTED";
        }
        if (message.contains("Invalid format for JWT")
                || message.contains("Not enough or too many segments")
                || message.contains("Too many segments in token")
                || message.contains("Invalid character in token")) {
            return "BEARER_MALFORMED";
        }

        return "JWT_INVALID";
    }

    // --- Evidence and verification source construction ---

    /**
     * Builds a {@link JwksVerificationSource} from the validation config.
     *
     * <p>The Vert.x JWT auth handler does not expose the original JWT header after verification,
     * so {@code kid} and {@code alg} are always {@link Optional#empty()} in the produced source.
     * The issuer and JWKS URI are derived from {@link JwtValidationConfig}.
     *
     * @return a verification source populated from config
     */
    private JwksVerificationSource buildVerificationSource() {
        Optional<String> issuer = Optional.ofNullable(validationConfig.issuer());
        Optional<String> jwksUri = deriveJwksUri(issuer);
        return new JwksVerificationSource(issuer, jwksUri, Optional.empty(), Optional.empty());
    }

    /**
     * Derives the JWKS URI from the issuer URL using the standard OIDC discovery convention
     * ({@code <issuer>/.well-known/jwks.json}).
     *
     * @param issuer the issuer from the validation config; may be empty
     * @return the derived JWKS URI, or {@link Optional#empty()} when the issuer is absent
     */
    private static Optional<String> deriveJwksUri(Optional<String> issuer) {
        return issuer.map(iss -> {
            String base = iss.endsWith("/") ? iss.substring(0, iss.length() - 1) : iss;
            return base + "/.well-known/jwks.json";
        });
    }

    /**
     * Builds an {@link AuthenticationEvidence} record from the verified {@link User} and the
     * configured validation metadata.
     *
     * <p>Safe attributes contain the {@code sub}, {@code client_id}, and {@code azp} claims when
     * present. These are non-secret OAuth claims required by
     * {@link dev.vertique.rest.security.DefaultSecurityIdentityResolver} to build a
     * {@link dev.vertique.security.ClientRef} and to classify client-credentials JWTs as
     * {@link dev.vertique.security.PrincipalType#SERVICE}. No raw token material is ever
     * included.
     *
     * @param user the verified user principal from the routing context; must not be {@code null}
     * @return a fully populated evidence record
     */
    private AuthenticationEvidence buildEvidence(User user) {
        // Read claims from user.principal() — the decoded JWT body — NOT user.attributes().
        // Vert.x JWTAuth only promotes registered claims (exp, iat, nbf) to attributes(); custom
        // claims like client_id and azp live solely in principal(). This matches how the rest of
        // the module reads claims (JwtClaimAuthorizationProvider, JwtClaimsValidatorContributor).
        io.vertx.core.json.JsonObject claims =
                user.principal() != null ? user.principal() : new io.vertx.core.json.JsonObject();

        // notAfter — exp claim as Unix epoch seconds, converted to Instant
        Optional<Instant> notAfter = Optional.ofNullable(claims.getLong("exp")).map(Instant::ofEpochSecond);

        // safeAttributes — sub, client_id, azp claims; no raw token material included.
        // client_id and azp are non-secret OAuth claims needed by DefaultSecurityIdentityResolver
        // to build a ClientRef and classify client-credentials JWTs as SERVICE principals.
        Map<String, Object> safeAttributes = new HashMap<>();
        String sub = claims.getString("sub");
        if (sub != null) {
            safeAttributes.put("sub", sub);
        }
        String clientId = claims.getString("client_id");
        if (clientId != null) {
            safeAttributes.put("client_id", clientId);
        }
        String azp = claims.getString("azp");
        if (azp != null) {
            safeAttributes.put("azp", azp);
        }

        return new AuthenticationEvidence(
                DefaultAuthMethod.jwt(),
                Optional.empty(),
                Instant.now(),
                notAfter,
                buildVerificationSource(),
                safeAttributes);
    }

    // --- DelegatingJwtAuthHandler ---

    /**
     * A {@link JWTAuthHandler} wrapper that delegates scope-related methods to an inner
     * {@link JWTAuthHandler}, but routes {@link #handle(RoutingContext)} and
     * {@link #authenticate(RoutingContext)} through the outer
     * {@link JwtBearerSecuritySchemeHandler} so they use the direct authentication path that avoids
     * the {@code RoutingContextInternal} cast.
     *
     * <p>This wrapper is registered through the neutral {@link SecuritySchemeRegistry} as a
     * {@link JWTAuthHandler}, satisfying the transport's type requirement while routing invocations
     * back through the outer class's direct authentication logic.
     *
     * <h3>Deliberate dependency on a Vert.x {@code impl} interface</h3>
     * This wrapper additionally implements
     * {@link io.vertx.ext.web.handler.impl.AuthenticationHandlerInternal} — a Vert.x-internal
     * ({@code impl}-package) interface. This is a deliberate, version-pinned dependency on
     * <strong>vertx-web 5.1.2</strong>: Vert.x's {@code ChainAuthHandlerImpl.add(...)}
     * <em>unconditionally</em> casts each member to {@code AuthenticationHandlerInternal}, so a
     * member that implements only {@link JWTAuthHandler} (which does not extend the internal
     * interface) triggers a {@link ClassCastException} at router build when an operation declares
     * two or more alternative bearer security requirements (OR composition). Implementing the
     * internal interface here is what makes this framework scheme composable into a
     * {@link ChainAuthHandler}.
     *
     * <p>During a request the chain calls only {@link #authenticate(RoutingContext)} on each member,
     * advancing to the next alternative when the returned future fails with an {@link HttpException}
     * (401/403/etc.); it never calls {@link #handle(RoutingContext)} on members. {@code authenticate}
     * therefore must NOT mutate the routing context — the chain owns {@code setUser}/{@code next}/
     * {@code fail}. After the chain sets {@code ctx.user()} from the winning member it calls only that
     * member's {@link #postAuthentication(RoutingContext)}, which this wrapper overrides to append the
     * framework {@link AuthenticationEvidence} (stashed by {@code authenticate}) and then advance via
     * {@code ctx.next()} — without that override the OR path would never append evidence and the
     * request would resolve to an anonymous framework identity. The {@code setAuthenticateHeader} and
     * {@code performsRedirect} defaults from the internal interface are inherited unchanged: the JWT
     * bearer scheme does not set a {@code WWW-Authenticate} challenge header today (the single-scheme
     * route fails with a bare {@code ctx.fail(401)}), so the default {@code setAuthenticateHeader}
     * returning {@code false} preserves that observable behavior.
     */
    private final class DelegatingJwtAuthHandler implements JWTAuthHandler, AuthenticationHandlerInternal {

        private final JWTAuthHandler delegate;

        /**
         * Creates a delegating wrapper around the given JWT auth handler.
         *
         * @param delegate the real JWT auth handler to delegate scope/scope-delimiter methods to
         */
        DelegatingJwtAuthHandler(JWTAuthHandler delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /**
         * Routes the handling call through the outer class's direct authentication logic,
         * bypassing the {@link JWTAuthHandler} to avoid the {@code RoutingContextInternal} cast.
         *
         * <p>This is the single-scheme path: it applies the success/failure routing-context side
         * effects ({@code setUser}/{@code next} or {@code fail}) itself.
         *
         * @param ctx the routing context from the OpenAPI router
         */
        @Override
        public void handle(RoutingContext ctx) {
            JwtBearerSecuritySchemeHandler.this.handle(ctx);
        }

        /**
         * Routes the OR-chain authentication call through the outer class's direct authentication
         * logic, returning the verified {@link User} without mutating the routing context (the
         * {@link ChainAuthHandler} owns the {@code setUser}/{@code next}/{@code fail} side effects).
         *
         * @param ctx the routing context from the chain
         * @return a future completing with the verified user, or failing with an
         *     {@link HttpException} so the chain tries the next alternative
         */
        @Override
        public Future<User> authenticate(RoutingContext ctx) {
            return JwtBearerSecuritySchemeHandler.this.authenticate(ctx);
        }

        /**
         * Appends the framework {@link AuthenticationEvidence} on the OR-chain success path, then
         * advances the route. {@code ChainAuthHandler.any()} calls {@code authenticate(ctx)} on each
         * member, sets {@code ctx.user()} from the winner, and then calls only the winning member's
         * {@code postAuthentication(ctx)} — it never calls {@link #handle(RoutingContext)}. The
         * inherited default simply calls {@code ctx.next()}; overriding it here is what appends the
         * evidence {@link #authenticate(RoutingContext)} stashed, so an OR-authenticated request
         * resolves to the authenticated framework identity instead of anonymous.
         *
         * <p>This runs ONLY on the chain path (single-scheme routes go through {@code handle}), so the
         * append happens exactly once per request. The default advance via {@code ctx.next()} is
         * preserved so the route continues to the next handler.
         *
         * @param ctx the routing context, with {@code ctx.user()} already set by the chain
         */
        @Override
        public void postAuthentication(RoutingContext ctx) {
            appendStashedEvidence(ctx, ctx.user());
            ctx.next();
        }

        @Override
        public JWTAuthHandler scopeDelimiter(String delimiter) {
            return delegate.scopeDelimiter(delimiter);
        }

        @Override
        public JWTAuthHandler withScope(String scope) {
            return delegate.withScope(scope);
        }

        @Override
        public JWTAuthHandler withScopes(List<String> scopes) {
            return delegate.withScopes(scopes);
        }
    }
}
