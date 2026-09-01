// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.exception.RateLimitRequestException;
import dev.vertique.ratelimit.exception.RateLimitRequestFailure;
import dev.vertique.security.ClientRef;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.SecurityIdentity;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One shared, hand-roll-free seam every framework adapter ({@code @RateLimited}, the REST edge
 * limiter, and future MCP tools) builds on to resolve a handle and frame an identity-based key
 * component (contracts/rate-limit-runtime.md, "Framework adapter seam"; {@code spec.md} §5.2).
 *
 * <p>{@link #subjectKey} distinguishes two situations that must never collapse into one:
 *
 * <ul>
 *   <li><b>anonymous caller</b> (no {@code SecurityIdentity} at all): governed by {@link
 *       AnonymousRateLimitPolicy} — {@code SHARED_BUCKET} frames one shared anonymous component;
 *       {@code BYPASS} yields {@link Optional#empty()} and the caller proceeds unlimited.
 *   <li><b>identity present but the requested facet absent</b> (e.g. {@code subject = CLIENT} under
 *       an auth scheme that never populates a client identity): a hard {@link
 *       RateLimitRequestException} ({@link RateLimitRequestFailure#SUBJECT_UNRESOLVABLE}) thrown
 *       synchronously — never a silent fall-through into the anonymous bucket, which would let
 *       distinct authenticated callers merge into one quota bucket.
 * </ul>
 */
@Singleton
public final class RateLimitAdapterSupport {

    /**
     * Distinct anonymous-bucket marker. Framed via {@link RateLimitKey}'s enum scalar encoding,
     * which embeds this private enum's fully qualified name, so it can never collide with a
     * facet-marker component drawn from {@link RateLimitSubject}.
     */
    private enum AnonymousMarker {
        SHARED
    }

    private final RateLimiters rateLimiters;
    private final RateLimitSubjectResolver subjectResolver;

    public RateLimitAdapterSupport(RateLimiters rateLimiters, RateLimitSubjectResolver subjectResolver) {
        this.rateLimiters = Objects.requireNonNull(rateLimiters, "rateLimiters");
        this.subjectResolver = Objects.requireNonNull(subjectResolver, "subjectResolver");
    }

    /**
     * Resolves the handle for {@code policyName}, delegating to the same {@link
     * RateLimiters#limiter(String)} resolution — no second resolution path or caching layer.
     */
    public RateLimiter limiter(String policyName) {
        return rateLimiters.limiter(policyName);
    }

    /**
     * Frames {@code subject}'s resolved identity dimension, if any, alongside {@code
     * extraComponents} into one canonical {@link RateLimitKey}.
     *
     * @param subject the identity dimension to resolve
     * @param anonymous the policy governing an anonymous caller (no identity at all)
     * @param extraComponents caller-supplied components framed alongside the identity dimension
     * @return the framed key, or {@link Optional#empty()} only when no identity is present and
     *     {@code anonymous} is {@link AnonymousRateLimitPolicy#BYPASS}
     * @throws RateLimitRequestException with reason {@link RateLimitRequestFailure#SUBJECT_UNRESOLVABLE}
     *     when identity is present but the requested facet is absent
     */
    public Optional<RateLimitKey> subjectKey(
            RateLimitSubject subject, AnonymousRateLimitPolicy anonymous, List<Object> extraComponents) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(anonymous, "anonymous");
        Objects.requireNonNull(extraComponents, "extraComponents");
        if (subject == RateLimitSubject.NONE) {
            return Optional.of(keyOf(extraComponents));
        }
        Optional<SecurityIdentity> identity = subjectResolver.current();
        if (identity.isPresent()) {
            return Optional.of(keyOf(withLeading(facetComponents(subject, identity.get()), extraComponents)));
        }
        return switch (anonymous) {
            case SHARED_BUCKET -> Optional.of(keyOf(withLeading(List.of(AnonymousMarker.SHARED), extraComponents)));
            case BYPASS -> Optional.empty();
        };
    }

    private static List<Object> facetComponents(RateLimitSubject subject, SecurityIdentity identity) {
        return switch (subject) {
            case ACTOR -> principalComponents(subject, identity.actor());
            case EFFECTIVE_PRINCIPAL ->
                principalComponents(subject, identity.subject().orElse(identity.actor()));
            case CLIENT -> {
                ClientRef client = identity.client()
                        .orElseThrow(() -> new RateLimitRequestException(RateLimitRequestFailure.SUBJECT_UNRESOLVABLE));
                yield List.of(subject, client.clientId());
            }
            case NONE ->
                throw new IllegalStateException("unreachable: subjectKey short-circuits NONE before resolving a facet");
        };
    }

    private static List<Object> principalComponents(RateLimitSubject subject, PrincipalRef principal) {
        return List.of(subject, principal.type(), principal.id());
    }

    private static List<Object> withLeading(List<Object> leading, List<Object> extraComponents) {
        List<Object> components = new ArrayList<>(leading.size() + extraComponents.size());
        components.addAll(leading);
        components.addAll(extraComponents);
        return components;
    }

    private static RateLimitKey keyOf(List<Object> components) {
        if (components.isEmpty()) {
            return RateLimitKey.global();
        }
        return RateLimitKey.of(
                components.get(0), components.subList(1, components.size()).toArray());
    }
}
