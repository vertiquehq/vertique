// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.ClientRef;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.IdentityResolutionError;
import dev.vertique.security.resolver.IdentityResolutionException;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Framework-provided default implementation of {@link SecurityIdentityResolver}.
 *
 * <p>Runs last among framework defaults at {@link #priority()} {@code 100}. Application resolvers
 * SHOULD pick a priority below {@code 100} to run first (e.g., custom delegation or PSD2
 * classification logic at priority {@code 50}).
 *
 * <h3>Resolution rules</h3>
 * <ul>
 *   <li><b>Empty evidence</b> → {@link SecurityIdentity#anonymous()}. This is the framework's
 *       anonymous fallback: a request that passed no recognised credential is anonymous.</li>
 *   <li><b>Non-empty evidence</b> → classifies the actor as {@link PrincipalType#USER},
 *       {@link PrincipalType#SERVICE}, or {@link PrincipalType#ANONYMOUS} based on
 *       {@link AuthMethodKind} and safe-attribute hints ({@code sub}, {@code client_id},
 *       {@code azp}).</li>
 *   <li><b>Underivable id</b> — when evidence would otherwise classify as {@code USER} or
 *       {@code SERVICE} but neither {@code sub} nor {@code client_id}/{@code azp} is present, this
 *       resolver <b>fails resolution</b> (a failed {@link Future} carrying an
 *       {@link IdentityResolutionException}) — never anonymous, never a placeholder. Falling back
 *       to anonymous or fabricating a shared literal id (e.g. {@code "unknown"}) would silently
 *       hide a genuine verified-credential-without-stable-id condition, and a fabricated shared id
 *       would additionally collide across distinct principals with no derivable identity, violating
 *       the trust-domain-unique {@code (type, id)} guarantee (PRD identity-002 FR-ID-CA-012).</li>
 * </ul>
 *
 * <h3>Applicability — single-issuer trust domains only</h3>
 * <p>This default resolver is appropriate only when {@code sub}/{@code client_id} identifiers are
 * already unique within the snapshot HMAC trust domain. Multi-issuer, multi-tenant, or
 * realm-local deployments MUST install a higher-priority {@link SecurityIdentityResolver} that
 * produces qualified ids (e.g. namespaced with an issuer or tenant prefix) before this resolver
 * runs, per FR-ID-CA-012.</p>
 *
 * <h3>Classification heuristic</h3>
 * <ul>
 *   <li>{@link AuthMethodKind#JWT}, {@link AuthMethodKind#BASIC} — {@code USER} when a {@code sub}
 *       claim is present; {@code SERVICE} otherwise (client-credentials flow).</li>
 *   <li>{@link AuthMethodKind#API_KEY}, {@link AuthMethodKind#HMAC},
 *       {@link AuthMethodKind#MTLS} — always {@code SERVICE}.</li>
 *   <li>{@link AuthMethodKind#NONE}, {@link AuthMethodKind#UNKNOWN},
 *       {@link AuthMethodKind#CUSTOM} — {@code ANONYMOUS}.</li>
 * </ul>
 *
 * <h3>Trust-domain uniqueness of the derived {@code (type, id)}</h3>
 * <p>The {@code (type, id)} pair this resolver derives from a raw {@code sub} claim is
 * trust-domain-unique only within a <b>single-issuer</b> trust domain — {@code sub} values are
 * guaranteed unique by one issuer, not across issuers. In a multi-issuer deployment, two different
 * issuers can legally mint the same {@code sub} value for two different principals, which would
 * collide under this resolver's derivation alone. Multi-issuer deployments MUST supply a
 * higher-priority {@link SecurityIdentityResolver} that qualifies {@code id} with an
 * issuer-scoped namespace or URN before this resolver runs. This resolver treats {@code id} as an
 * opaque string end-to-end and never composes issuer qualification itself.
 *
 * <h3>AC-CO-3 defense-in-depth invariant</h3>
 * <p>Framework-provided resolution MUST NOT produce {@link PrincipalType#SYSTEM} from inbound
 * evidence. Even hostile claims ({@code sub=SYSTEM}, {@code type=SYSTEM}, {@code system=true})
 * are never elevated to {@code SYSTEM}; the literal string {@code "SYSTEM"} is preserved as the
 * actor id but the type remains {@code USER} or {@code SERVICE}. The invariant is asserted at
 * runtime via an {@link IllegalStateException} guard.
 *
 * <h3>ClientRef population</h3>
 * <p>When the primary evidence carries a {@code client_id} or {@code azp} safe attribute, a
 * {@link ClientRef} is constructed and attached to the identity. The {@code client_id} claim
 * takes precedence over {@code azp}.
 *
 * <h3>Async design</h3>
 * <p>This implementation performs no I/O. Every call returns an already-completed
 * {@link Future}. Application resolvers with lower priority that require network-bound work
 * (e.g., OAuth introspection) should implement {@link SecurityIdentityResolver} separately.
 */
@Singleton
public final class DefaultSecurityIdentityResolver implements SecurityIdentityResolver {

    /**
     * Constructs a new {@code DefaultSecurityIdentityResolver}. Invoked by Dagger.
     */
    @Inject
    DefaultSecurityIdentityResolver() {}

    /**
     * Returns {@code 100} — this resolver runs last among framework defaults.
     *
     * <p>Application resolvers SHOULD pick a priority {@code < 100} to run first.
     *
     * @return the resolver priority
     */
    @Override
    public int priority() {
        return 100;
    }

    /**
     * Resolves a {@link SecurityIdentity} from the given resolution context.
     *
     * <p>Empty evidence resolves to {@link SecurityIdentity#anonymous()}. Non-empty evidence
     * classifies the actor as {@code USER}, {@code SERVICE}, or {@code ANONYMOUS} and optionally
     * attaches a {@link ClientRef} when a client-id claim is present. When the evidence classifies
     * as {@code USER}/{@code SERVICE} but no stable id can be derived from it, the returned
     * {@link Future} fails with an {@link IdentityResolutionException} (FR-ID-CA-012) — see
     * {@link #buildIdentityFromEvidence(AuthenticationEvidence)}.
     *
     * @param context the resolution context carrying accumulated authentication evidence; must not
     *                be {@code null}
     * @return an already-completed {@link Future} containing the resolved identity, or a failed
     *         {@link Future} carrying an {@link IdentityResolutionException} when the evidence
     *         classifies as {@code USER}/{@code SERVICE} with no derivable id
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Override
    public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
        Objects.requireNonNull(context, "context");

        // No evidence → ANONYMOUS. Framework anonymous fallback resolution path.
        if (context.evidence().isEmpty()) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }

        // Primary evidence drives actor classification; subsequent entries may layer claims
        // (e.g., mTLS + JWT). Multi-evidence client-id conflict resolution is not yet implemented;
        // the first evidence entry's client id wins.
        AuthenticationEvidence primary = context.evidence().get(0);
        try {
            return Future.succeededFuture(Optional.of(buildIdentityFromEvidence(primary)));
        } catch (IdentityResolutionException e) {
            // Underivable id (FR-ID-CA-012) — fail resolution explicitly rather than letting the
            // exception escape as a synchronous throw from an async-contract method.
            return Future.failedFuture(e);
        }
    }

    // --- Private helpers ---

    /**
     * Builds a {@link SecurityIdentity} from the primary authentication evidence entry.
     *
     * <p>Classification is based on {@link AuthMethodKind} and safe-attribute hints.
     * The AC-CO-3 invariant is asserted: this method MUST NOT return a {@code SYSTEM} identity.
     *
     * @param primary the primary (first) evidence entry for this request
     * @return a resolved {@link SecurityIdentity} with type {@code USER}, {@code SERVICE}, or
     *         {@code ANONYMOUS}
     * @throws IdentityResolutionException if the evidence classifies as {@code USER}/{@code SERVICE}
     *                                      but no stable id ({@code sub}/{@code client_id}/
     *                                      {@code azp}) can be derived from it (FR-ID-CA-012)
     */
    private SecurityIdentity buildIdentityFromEvidence(AuthenticationEvidence primary) {
        AuthMethodKind kind = primary.method().normalizedKind();
        Map<String, Object> safeAttrs = primary.safeAttributes();

        Optional<String> subjectId = Optional.ofNullable(getStringAttr(safeAttrs, "sub"));
        Optional<String> clientIdFromEvidence = Optional.ofNullable(getStringAttr(safeAttrs, "client_id"))
                .or(() -> Optional.ofNullable(getStringAttr(safeAttrs, "azp")));

        // Classification heuristic (mirrors prior DefaultAuditIdentityResolver):
        // - JWT / BASIC: USER when sub present (human user flow); SERVICE otherwise
        //   (client-credentials / machine-only flow).
        // - API_KEY / HMAC / MTLS: always SERVICE — these auth methods are machine-to-machine.
        // - NONE / UNKNOWN / CUSTOM: ANONYMOUS — no verified identity claim.
        PrincipalType actorType =
                switch (kind) {
                    case JWT, BASIC -> subjectId.isPresent() ? PrincipalType.USER : PrincipalType.SERVICE;
                    case API_KEY, HMAC, MTLS -> PrincipalType.SERVICE;
                    case NONE, UNKNOWN, CUSTOM -> PrincipalType.ANONYMOUS;
                };

        // AC-CO-3 defense-in-depth: framework-provided resolution NEVER produces SYSTEM.
        // PrincipalType.SYSTEM can only result from explicit SystemIdentities factory calls.
        if (actorType == PrincipalType.SYSTEM) {
            throw new IllegalStateException(
                    "Framework-provided resolution must not produce SYSTEM from inbound evidence");
        }

        if (actorType == PrincipalType.ANONYMOUS) {
            return SecurityIdentity.anonymous();
        }

        // Subject id: prefer `sub` claim; fall back to client_id / azp. When neither is present the
        // id is underivable — per FR-ID-CA-012, a durable principal identity is trust-domain-unique
        // by (type, id); fabricating a shared literal id (e.g. "unknown") would collide across
        // distinct principals lacking evidence, defeating that uniqueness guarantee, and silently
        // downgrading to ANONYMOUS would hide a genuine verified-credential-without-stable-id
        // condition. Fail resolution instead — never anonymous, never a placeholder. The message
        // names the auth method kind only; it never echoes credential material.
        Optional<String> derivedId = subjectId.or(() -> clientIdFromEvidence);
        if (derivedId.isEmpty()) {
            throw new IdentityResolutionException(
                    IdentityResolutionError.UNDERIVABLE_PRINCIPAL_ID,
                    "Cannot derive a stable principal id from " + kind
                            + " evidence: neither `sub` nor `client_id`/`azp` is present");
        }
        PrincipalRef actor = new PrincipalRef(actorType, derivedId.get(), Map.of());

        // Attach ClientRef when the evidence carries a client identity claim.
        Optional<ClientRef> client =
                clientIdFromEvidence.map(cid -> new ClientRef(cid, determineClientSource(primary), Map.of()));

        return new SecurityIdentity(actor, Optional.empty(), Optional.empty(), client);
    }

    /**
     * Determines the {@link ClientRef#source()} label based on which safe attribute supplied the
     * client id.
     *
     * <p>Returns {@code "jwt-azp"} when the value came from the {@code azp} claim, and
     * {@code "jwt-client_id"} when it came from the {@code client_id} claim. For non-JWT methods
     * the source reflects the auth-method name.
     *
     * @param evidence the evidence entry whose safe attributes contain the client id
     * @return a non-blank source label for the {@link ClientRef}
     */
    private String determineClientSource(AuthenticationEvidence evidence) {
        Map<String, Object> attrs = evidence.safeAttributes();
        // Use the same blank-filtered read as the id derivation, so a blank client_id that fell
        // back to azp is labeled with the claim that actually supplied the id.
        if (getStringAttr(attrs, "client_id") != null) {
            return evidence.method().id() + "-client_id";
        }
        if (getStringAttr(attrs, "azp") != null) {
            return evidence.method().id() + "-azp";
        }
        return evidence.method().id();
    }

    /**
     * Reads a string-valued safe attribute, treating a blank value as absent.
     *
     * <p>A blank claim (e.g. {@code "sub":""}) is malformed, not a genuine identity: it is never a
     * derivable id and must not be treated as present. Filtering it here collapses every downstream
     * blank-claim path onto the same {@link IdentityResolutionError#UNDERIVABLE_PRINCIPAL_ID} failure
     * used for a wholly-absent claim, rather than letting a blank value (a) misclassify the actor
     * type based on a non-derivable id, (b) reach {@link PrincipalRef}'s or {@link ClientRef}'s
     * blank-id validation and throw {@link IllegalArgumentException} synchronously out of
     * {@link #resolve(SecurityIdentityResolutionContext)}, or (c) mask a valid fallback claim (e.g. a
     * blank {@code sub} hiding a present {@code client_id}) via {@link Optional#or}.
     *
     * @param attrs the safe attribute map to read from
     * @param key   the attribute key to look up
     * @return the non-blank string value, or {@code null} when absent, non-string, or blank
     */
    private static String getStringAttr(Map<String, Object> attrs, String key) {
        Object val = attrs.get(key);
        return val instanceof String s && !s.isBlank() ? s : null;
    }
}
