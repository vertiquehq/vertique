// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.security.CredentialRejectionReporter;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.verification.JwksVerificationSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link OperationHandlerContributor} that adds a per-route handler to invoke the
 * application-provided {@link JwtClaimsValidator} after JWT authentication succeeds.
 *
 * <p>This contributor is only registered when a {@link JwtClaimsValidator} is present in the
 * Dagger graph (via the optional binding in {@link JwtAuthModule}). It adds a lightweight
 * routing handler that:
 * <ol>
 *   <li>Reads the authenticated {@link io.vertx.ext.auth.User} from the routing context</li>
 *   <li>Passes the decoded principal claims to {@link JwtClaimsValidator#validate(java.util.Map)}</li>
 *   <li>On failure: emits a {@link dev.vertique.security.events.CredentialRejectedEvent} via
 *       {@link CredentialRejectionReporter} with reason code {@code JWT_CLAIMS_INVALID}, then
 *       fails the request with {@code 401 Unauthorized}</li>
 * </ol>
 *
 * <p>Priority: 50 — runs before authorization (100) and SecurityContext bridging (200),
 * ensuring invalid tokens are rejected before any further processing.
 *
 * <p>The rejection event emission mirrors the pattern used by
 * {@link JwtBearerSecuritySchemeHandler} for token-level failures, so claims validation
 * failures are observable through the same audit/observability channel — including on
 * action-only routes where authentication is performed by the
 * {@link dev.vertique.rest.security.ActionGateAuthenticationContributor} lane.
 */
@Slf4j
public class JwtClaimsValidatorContributor implements OperationHandlerContributor {

    /** Stable reason code emitted when custom JWT claims validation rejects a token. */
    static final String REASON_CODE = "JWT_CLAIMS_INVALID";

    private final JwtClaimsValidator claimsValidator;
    private final CredentialRejectionReporter rejectionReporter;
    private final JwtValidationConfig validationConfig;

    /**
     * Creates a new contributor that runs the given validator on every authenticated request
     * and emits a {@link dev.vertique.security.events.CredentialRejectedEvent} via the
     * reporter when validation fails.
     *
     * @param claimsValidator   the custom claims validator; must not be {@code null}
     * @param rejectionReporter the reporter used to emit rejection events; must not be {@code null}
     * @param validationConfig  JWT validation config used to build the
     *                          {@link JwksVerificationSource} on the emitted event; must not be
     *                          {@code null}
     */
    public JwtClaimsValidatorContributor(
            JwtClaimsValidator claimsValidator,
            CredentialRejectionReporter rejectionReporter,
            JwtValidationConfig validationConfig) {
        this.claimsValidator = Objects.requireNonNull(claimsValidator, "claimsValidator");
        this.rejectionReporter = Objects.requireNonNull(rejectionReporter, "rejectionReporter");
        this.validationConfig = Objects.requireNonNull(validationConfig, "validationConfig");
    }

    /**
     * Returns the priority of this contributor.
     * Runs at priority 50 — before identity resolution (80) and authorization (100).
     *
     * @return {@code 50}
     */
    @Override
    public int priority() {
        return 50;
    }

    /**
     * Adds a routing handler to the operation that invokes the {@link JwtClaimsValidator}
     * against the authenticated user's principal claims.
     *
     * <p>When the validator throws, a {@link dev.vertique.security.events.CredentialRejectedEvent}
     * is emitted with reason code {@value #REASON_CODE} before {@code ctx.fail(401, e)} is called,
     * so the rejection is observable through the same audit channel as token-level failures.
     *
     * @param context the operation registration context providing route and metadata access
     */
    @Override
    public void contribute(OperationRegistrationContext context) {
        log.debug("operationId={}: Adding JwtClaimsValidator handler", context.operationId());
        context.route().addHandler(ctx -> {
            var user = ctx.user();
            if (user != null && user.principal() != null) {
                try {
                    claimsValidator.validate(user.principal().getMap());
                } catch (Exception e) {
                    log.debug("JWT claims validation failed: {}", e.getMessage());
                    // Emit a rejection event before failing the context, so the failure is
                    // observable through the audit/observability channel — consistent with the
                    // token-level rejection path in JwtBearerSecuritySchemeHandler.
                    rejectionReporter.report(
                            ctx,
                            DefaultAuthMethod.jwt(),
                            Optional.empty(),
                            Optional.of(buildVerificationSource()),
                            REASON_CODE,
                            Map.of());
                    ctx.fail(401, e);
                    return;
                }
            }
            ctx.next();
        });
    }

    // --- Private helpers ---

    /**
     * Builds a {@link JwksVerificationSource} from the validation config, matching the pattern
     * used by {@link JwtBearerSecuritySchemeHandler#buildVerificationSource()}.
     *
     * @return a verification source populated from config
     */
    private JwksVerificationSource buildVerificationSource() {
        Optional<String> issuer = Optional.ofNullable(validationConfig.issuer());
        Optional<String> jwksUri = issuer.map(iss -> {
            String base = iss.endsWith("/") ? iss.substring(0, iss.length() - 1) : iss;
            return base + "/.well-known/jwks.json";
        });
        return new JwksVerificationSource(issuer, jwksUri, Optional.empty(), Optional.empty());
    }
}
