// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

/**
 * Codec-neutral projection from a <strong>wire</strong> property name to the <strong>Java</strong>
 * property name that declares its input policies.
 *
 * <p>Both execution paths key their per-field metadata on the Java property name: the reflective
 * walker looks a field up by {@code Field#getName()} and a generated {@code {DTO}_InputProcessor}
 * switches on the same literal. An intermediate parsed from the wire, however, is keyed by whatever
 * the codec published — {@code @JsonProperty("user_name")}, a {@code SNAKE_CASE} naming strategy, or
 * a {@code @JsonAlias}. Without a projection between the two, a declared
 * {@link Canonicalize}/{@link Sanitize} on a renamed field silently never runs. This contract is
 * the seam that closes that gap while keeping both this package and the input-processing engine
 * free of any codec dependency: each codec-backed implementation lives in the module that already
 * owns that codec — the Jackson-backed one in {@code vertique-json}.
 *
 * <p><strong>Direction.</strong> The projection maps wire → Java, which is the only direction able
 * to express an alias's many-to-one mapping: several wire names may resolve to one Java property,
 * while one Java property has no single wire name.
 *
 * <p><strong>Totality.</strong> The function is total. A {@code wireName} the projection does not
 * recognize — an extra key the DTO does not declare, a key belonging to a {@code Map}-typed field,
 * or a name that is already the Java property name — is returned <em>unchanged</em>. An
 * implementation never returns {@code null} and never throws: a projection that could fail would
 * reinstate exactly the silently-dropped policy this contract exists to prevent.
 *
 * <p><strong>Threading and cost.</strong> Implementations are stateless (or effectively immutable),
 * reentrant, and safe for concurrent use from several event-loop threads. They are consulted on the
 * request path, once per intermediate key, so an implementation must not block and should serve
 * every call from a precomputed projection rather than introspecting per call.
 *
 * <p>{@link #IDENTITY} is the correct choice for any transport whose intermediate keys are already
 * Java property names — including every call site that processes a bare {@code String}, where there
 * is no object whose fields could be renamed.
 *
 * <p>The projection is consulted by the input-processing engine's traversal context
 * ({@code dev.vertique.input.processing.InputTraversalContext#logicalFieldName(Class, String)}),
 * which resolves each intermediate key before looking its policies up.
 *
 * @see InputValueContext
 */
@FunctionalInterface
public interface InputFieldNameResolver {

    /** Projection that returns every wire name unchanged, for transports that do not rename. */
    InputFieldNameResolver IDENTITY = (ownerType, wireName) -> wireName;

    /**
     * Returns the Java property name that {@code wireName} binds to on {@code ownerType}.
     *
     * @param ownerType the type declaring the property set the intermediate is keyed against;
     *                  never {@code null}
     * @param wireName  the key as it appeared in the intermediate; never {@code null}
     * @return the Java property name, or {@code wireName} unchanged when the projection does not
     *         recognize it; never {@code null}
     */
    String logicalName(Class<?> ownerType, String wireName);

    /**
     * Composes and caches the projection for {@code ownerType} now, so {@link #logicalName} serves it
     * without introspecting on the request path. The default is a no-op, for a resolver that needs no
     * per-class state.
     *
     * <p>Called at registration by {@code InputObjectProcessor#precomputeFieldNameResolution}, once per
     * owner type. Implementations must be idempotent and may fail fast: a failure here is a startup
     * failure, which is the point.
     *
     * @param ownerType the type whose projection to compose; must not be {@code null}
     */
    default void precompute(Class<?> ownerType) {}
}
