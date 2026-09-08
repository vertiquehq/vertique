// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Returns the keys of {@code ownerType} that are bound into a field declared on a
     * <em>different</em> type, mapped to where that field lives.
     *
     * <p>Some codecs promote a nested type's fields into the enclosing object, so they arrive as
     * keys of the enclosing object rather than inside a nested one — Jackson's
     * {@code @JsonUnwrapped} is the case this exists for. The engine keys its per-field policy
     * metadata on the type that <em>declares</em> the field, so for such a key the enclosing type's
     * metadata has no entry at all and the field's declared policies would silently never run.
     *
     * <p><strong>The map is keyed by the wire name, and a promoted key is never added to the
     * projection.</strong> {@link #logicalName} returns such a key unchanged, as its totality
     * contract already requires for any name it does not recognize, so the owner's metadata misses
     * and the engine falls through to {@link #promotedField}. Keying by the Java name the promoted
     * field carries would not work: that name is local to the inner type, so two promoted members of
     * different types sharing a field name would collide even when a prefix already gives them
     * distinct wire keys, and a promoted key could shadow a same-named field of the owner.
     *
     * <p>A resolver whose codec matches keys case-insensitively stores its keys already folded and
     * overrides {@link #promotedField} to fold its argument, which is what keeps every wire-side
     * concern inside the projection.
     *
     * <p>The default is an empty map, for a projection whose codec promotes nothing. Implementations
     * compose the map in {@link #precompute} and serve it from cache: it is read at registration to
     * enumerate the promoted keys, and {@link #promotedField} may be consulted on the request path.
     *
     * @param ownerType the type the intermediate is keyed against; must not be {@code null}
     * @return the promoted keys of {@code ownerType}, keyed by wire name; never {@code null}
     */
    default Map<String, PromotedField> promotedFields(Class<?> ownerType) {
        return Map.of();
    }

    /**
     * Returns where a single wire key of {@code ownerType} is bound, or {@code null} when the key is
     * not one the codec promoted.
     *
     * <p>The engine consults this only after the owner's own metadata has no entry for the key, so
     * an ordinary field costs nothing. The argument is the <em>wire</em> key, not the value
     * {@link #logicalName} returned, because resolving a promoted key is the projection's own job:
     * an implementation that folds case, or applies any other wire-side rule, applies it here too.
     *
     * @param ownerType the type the intermediate is keyed against; must not be {@code null}
     * @param wireName  the key as it appeared in the intermediate; must not be {@code null}
     * @return where the key is bound, or {@code null} when nothing was promoted under it
     */
    @Nullable
    default PromotedField promotedField(Class<?> ownerType, String wireName) {
        return promotedFields(ownerType).get(wireName);
    }

    /**
     * Returns the Java property names the codec binds wire keys of {@code ownerType} into, or
     * {@code null} when the projection cannot enumerate them.
     *
     * <p>The engine keys its per-field policy metadata on the Java field name, and a codec keys its
     * binding on the Java <em>property</em> name it derives from the members it finds. The two
     * agree for a field, for a record component, and for the accessor pair a field's name implies
     * ({@code streetName} with {@code setStreetName}); they diverge for an accessor or creator
     * parameter whose implicit name differs from the field it writes ({@code setStreet} writing
     * {@code streetName}), and neither side can derive that mapping — the codec does not know
     * which field a setter assigns. A policy declared on such a field would silently never run.
     * The engine therefore checks, at registration, that every field carrying a declared chain is
     * among the names returned here, and fails startup naming the field when it is not.
     *
     * <p>The default is {@code null}: a resolver that cannot enumerate what the codec binds — the
     * {@link #IDENTITY} projection, whose keys are already Java names — leaves the check to the
     * field names themselves. An implementation that can enumerate returns the complete set,
     * composed in {@link #precompute} and served from cache, so it is read at registration only.
     *
     * @param ownerType the type the intermediate is keyed against; must not be {@code null}
     * @return the Java property names the codec binds into on {@code ownerType}, or {@code null}
     *         when the projection cannot enumerate them
     */
    @Nullable
    default Set<String> boundJavaNames(Class<?> ownerType) {
        return null;
    }

    /**
     * Returns the wire keys of {@code ownerType} the codec binds into a Java property it cannot
     * name, or an empty set when every key is routable.
     *
     * <p>A creator parameter whose name is not a declared field's — {@code @JsonCreator} with
     * {@code @JsonProperty("street_name")} on a parameter writing {@code streetName} — is bound by
     * the codec, but the codec has no idea which field the constructor assigns it to. No projection
     * can route such a key, and {@link #boundJavaNames} cannot list the field it reaches. A
     * parameter named after the field it writes is routable by that name and is never here. The engine therefore refuses at registration an owner that has any such key
     * <em>and</em> declares a chain on any field: whether a policy governs the field the parameter
     * writes is undecidable, and a silent miss is the outcome this contract exists to prevent. An
     * owner with such keys and no declared chain is unaffected.
     *
     * <p>The default is the empty set, for a projection whose every key names its target. An
     * implementation whose {@link #boundJavaNames} returns {@code null} returns the empty set here
     * too — a projection that cannot enumerate is trusted as a whole.
     *
     * @param ownerType the type the intermediate is keyed against; must not be {@code null}
     * @return the wire keys of {@code ownerType} bound into a property the codec cannot name;
     *         never {@code null}
     */
    default Set<String> unroutableWireNames(Class<?> ownerType) {
        return Set.of();
    }

    /**
     * A key that arrives on one type but is bound into a field declared on another.
     *
     * @param declaringType the type declaring the field the key binds into; never {@code null}
     * @param fieldName     the Java property name the codec binds the key into on
     *                      {@code declaringType}. The engine keys its per-field metadata on the
     *                      field name, and registration verifies through
     *                      {@link #boundJavaNames} that the two agree for every field of
     *                      {@code declaringType} carrying a declared chain, so a governed promoted
     *                      field is always found under this name; never {@code null}
     * @param enclosingPath the owner-side field names traversed to reach the promoted field, outermost
     *                      first: the single unwrapped member for a one-level promotion, one entry per
     *                      level when promotion nests. The engine descends through each so the
     *                      enclosing members' own chains and skip flags still apply, and registration
     *                      verifies the path is one the engine can descend whenever routing the key
     *                      would apply any policy. Never {@code null}; never empty for a genuine
     *                      promotion, and immutable
     */
    record PromotedField(Class<?> declaringType, String fieldName, List<String> enclosingPath) {
        /** Copies {@code enclosingPath} so the record never publishes a live list. */
        public PromotedField {
            enclosingPath = List.copyOf(enclosingPath);
        }
    }
}
