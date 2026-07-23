// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import dev.vertique.core.context.ContextValue;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral descriptor of the invocation/transport boundary an {@link AuthorizationRequest}
 * was raised through — the authz-time counterpart to the pre-auth network envelope carried by
 * {@code SecurityContext.origin()} ({@link dev.vertique.security.origin.RequestOrigin}). The two are
 * distinct concepts: {@code RequestOrigin} captures network-envelope facts (peer IP, scheme, TLS)
 * before authentication runs; {@code InvocationOrigin} identifies *which ingress kind* dispatched the
 * request into authorization, so a policy may discriminate on it (e.g. deny a sensitive action when
 * invoked via an unattended integration boundary, permit it from an interactive REST session).
 *
 * <p>{@code kind} is a transport-neutral ingress identifier drawn from
 * {@link dev.vertique.core.context.DispatchBoundary} values (e.g. {@code "rest"}, {@code "camel"},
 * {@code "delayed-job"}, {@code "kafka"}, {@code "workflow"}) — centralizing the string constants
 * there avoids typos and keeps the set of known boundaries discoverable. A caller that does not seed
 * an origin gets {@link #unspecified()}.
 *
 * <p>Implements {@link ContextValue} so an ingress boundary can install a real
 * {@code InvocationOrigin} as the ambient invocation origin on {@link dev.vertique.core.context.ContextHolder}
 * (e.g. {@code IdentityResolutionMiddleware} binds {@link #of(String)} {@code "rest"} at REST
 * ingress) and downstream authorization/snapshot-capture code reads it back via
 * {@code contextHolder.current(InvocationOrigin.class)} — identity-002 P2.S5b-i.
 *
 * <p><strong>{@code attributes} is advisory input, never a trust source</strong> — mirroring
 * {@link AuthorizationRequest#context()}. It carries caller-supplied ingress hints only; a policy
 * MUST NOT treat it as an authority claim, and callers MUST NOT place sensitive data (tokens,
 * credentials, PII) in it, since the engine may surface it in audit output.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code kind} is required (non-null, non-blank)</li>
 *   <li>A null {@code attributes} map is treated as {@link Map#of()} (empty)</li>
 *   <li>The {@code attributes} map is defensively copied</li>
 *   <li>{@code attributes} is bounded to at most {@link #MAX_ATTRIBUTES} entries, audit-safe and
 *       advisory only — never enforced as a trust boundary. Exceeding it throws
 *       {@link IllegalArgumentException}.</li>
 *   <li>Each attribute value's {@link String#valueOf(Object)} form is bounded to at most
 *       {@link #MAX_VALUE_LENGTH} characters. Exceeding it throws {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * @param kind       the transport-neutral ingress identifier; must not be blank
 * @param attributes optional ingress-time attributes; may be {@code null} (treated as empty);
 *                   advisory input only — must not be used as a trust source and must not carry
 *                   sensitive data; bounded to {@link #MAX_ATTRIBUTES} entries of at most
 *                   {@link #MAX_VALUE_LENGTH} characters each
 */
public record InvocationOrigin(String kind, Map<String, Object> attributes) implements ContextValue {

    /** Maximum number of {@code attributes} entries permitted; exceeding it is rejected. */
    public static final int MAX_ATTRIBUTES = 16;

    /**
     * Maximum length of an attribute value's {@link String#valueOf(Object)} form; exceeding it is
     * rejected.
     */
    public static final int MAX_VALUE_LENGTH = 1024;

    /** The {@link #kind} used by {@link #unspecified()} for callers that don't seed an origin. */
    public static final String UNSPECIFIED_KIND = "unspecified";

    /**
     * Compact constructor — validates {@code kind}, defensively copies {@code attributes}, and
     * enforces the {@link #MAX_ATTRIBUTES} / {@link #MAX_VALUE_LENGTH} bounds.
     */
    public InvocationOrigin {
        Objects.requireNonNull(kind, "kind");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        Map<String, Object> copy = Map.copyOf(attributes == null ? Map.of() : attributes);
        if (copy.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException(
                    "attributes exceeds MAX_ATTRIBUTES (" + MAX_ATTRIBUTES + "): " + copy.size() + " entries");
        }
        for (Map.Entry<String, Object> entry : copy.entrySet()) {
            int length = String.valueOf(entry.getValue()).length();
            if (length > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("attribute \"" + entry.getKey()
                        + "\" value exceeds MAX_VALUE_LENGTH (" + MAX_VALUE_LENGTH + "): " + length + " chars");
            }
        }
        attributes = copy;
    }

    /**
     * Creates an {@code InvocationOrigin} of the given {@code kind} with no attributes.
     *
     * @param kind the transport-neutral ingress identifier; must not be blank
     * @return a new {@code InvocationOrigin} with empty {@link #attributes()}
     */
    public static InvocationOrigin of(String kind) {
        return new InvocationOrigin(kind, Map.of());
    }

    /**
     * Returns the default {@code InvocationOrigin} for callers that don't seed one — kind
     * {@value #UNSPECIFIED_KIND}, no attributes.
     *
     * @return the unspecified-origin sentinel
     */
    public static InvocationOrigin unspecified() {
        return new InvocationOrigin(UNSPECIFIED_KIND, Map.of());
    }
}
