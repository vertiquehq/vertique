// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.AuthMethodKind;
import io.vertx.ext.auth.User;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Binds a token-derived {@link dev.vertique.core.correlation.CorrelationSessionRef} onto the live
 * {@link dev.vertique.core.correlation.CorrelationContext} after JWT authentication succeeds.
 *
 * <p>Priority {@value #PRIORITY} places this after {@link JwtClaimsValidatorContributor} (50) and
 * before {@code IdentityResolutionContributor} (80), so session enrichment uses validated claims
 * and is visible to identity resolution / audit projection for the same request.
 *
 * <p>Enrichment runs only when {@link RestAuthenticationEvidence} contains a verified JWT entry.
 * An ambient {@code ctx.user()} from another scheme or a public route is ignored.
 */
@Slf4j
public final class JwtCorrelationSessionContributor implements OperationHandlerContributor {

    /** Runs after optional claims validation and before identity resolution. */
    public static final int PRIORITY = 55;

    private final JwtCorrelationSessionEnricher enricher;
    private final CorrelationContextMutator mutator;

    /**
     * @param enricher claim → session-ref builder; must not be {@code null}
     * @param mutator  live correlation mutator; must not be {@code null}
     */
    public JwtCorrelationSessionContributor(JwtCorrelationSessionEnricher enricher, CorrelationContextMutator mutator) {
        this.enricher = Objects.requireNonNull(enricher, "enricher");
        this.mutator = Objects.requireNonNull(mutator, "mutator");
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public void contribute(OperationRegistrationContext context) {
        context.route().addHandler(ctx -> {
            if (hasVerifiedJwtEvidence(ctx)) {
                User user = ctx.user();
                if (user != null && user.principal() != null) {
                    Map<String, Object> claims = user.principal().getMap();
                    if (claims != null) {
                        enricher.enrich(claims).ifPresent(ref -> {
                            try {
                                mutator.setSession(ref);
                            } catch (IllegalStateException missingCorrelation) {
                                log.debug(
                                        "CorrelationContext not bound; skipping JWT session enrichment: {}",
                                        missingCorrelation.getMessage());
                            }
                        });
                    }
                }
            }
            ctx.next();
        });
    }

    /**
     * Returns {@code true} when this request carries at least one framework evidence entry whose
     * method kind is JWT — i.e. the JWT scheme handler verified a credential for this request.
     *
     * <p>Ambient {@code ctx.user()} principals (public routes, alternate schemes) must not be
     * treated as JWT claims for session enrichment.
     */
    private static boolean hasVerifiedJwtEvidence(io.vertx.ext.web.RoutingContext ctx) {
        return RestAuthenticationEvidence.get(ctx).stream()
                .anyMatch(ev -> ev.method().normalizedKind() == AuthMethodKind.JWT);
    }
}
