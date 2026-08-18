// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes the set of classes {@link DefaultInputObjectProcessor} may pass to
 * {@link InputTraversalContext#logicalFieldName(Class, String)} while processing a declared type,
 * and hands each one to an {@link InputFieldNameResolver} so no projection is composed on the
 * request path.
 *
 * <p>The set is derived from the engine's <em>own</em> descent rules rather than re-derived by a
 * projection implementation. A projection that guesses the rules diverges in both directions: it
 * warms types the engine never reaches (turning a name collision in an unreachable type into a
 * startup failure) and misses types the engine does reach (leaving the projection to introspect on
 * an event loop, per request, forever).
 *
 * <p>A class is prepared here only when it is a <strong>field-name owner</strong> — a class whose
 * declared property schema an execution path may project a wire key against. A raw collection
 * reached only because no element schema is determinable, and a field's raw declared class reached
 * only because the wire shape disagrees with the declared shape (the {@code String} field mismatch
 * family), are <strong>schema-free dispatch targets</strong>: {@link DefaultInputObjectProcessor}
 * never projects a key against them ({@code metadata.fields()} is empty for both, so the projection
 * would be provably discarded), so this walk does not prepare them either. Everything else a field
 * unconditionally records — a nested-object field's declared type, an annotated schema-free field's
 * declared type ({@code Map}, {@code Object}, an enum), a collection-of-strings field's raw
 * container class, and a collection-of-objects field's raw container class alongside its element
 * type — stays prepared, because the engine genuinely may dispatch a Map-shaped fragment against it.
 *
 * <p>The rule, in full:
 *
 * <ul>
 *   <li><strong>Seed</strong> — {@link TypeClassifier#classify} of the declared type, unless it is
 *       itself a raw container (a {@link Collection} or an array) with no determinable
 *       {@link TypeClassifier#elementType} — that combination is the entry-point shape of a
 *       schema-free dispatch target and is not prepared. {@link TypeClassifier#elementType} of the
 *       declared type is always prepared when non-{@code null}.</li>
 *   <li><strong>Every frontier class is prepared, except a schema-free dispatch target.</strong> A
 *       field's raw declared type ({@link FieldPolicyMetadata#fieldType()}) is prepared unless the
 *       field is {@linkplain FieldPolicyMetadata#isStringType() string-typed} — a {@code String}
 *       field's raw class is dispatched against only on a wire/declared shape mismatch, never in the
 *       field's own normal path, so it is schema-free by the same rule as the seed. {@code Map}, an
 *       enum and {@code Object} remain legitimate owners: an annotated field of one of those types
 *       genuinely is dispatched against its raw declared class whenever the wire value is a Map.</li>
 *   <li><strong>Descent</strong> follows only a class that {@link InputPolicyMetadataResolver#isDescendableObject}
 *       accepts and that is not a platform type. Contributions are <em>both</em>
 *       {@link FieldPolicyMetadata#fieldType()} (subject to the string-type exclusion above) and
 *       {@link FieldPolicyMetadata#collectionElementType()} of every recorded field — both, never one
 *       or the other, because the engine can dispatch against either depending on the wire shape it
 *       meets.</li>
 *   <li><strong>A generated processor answers for its own type.</strong> When one exists for the
 *       frontier class and its {@link GeneratedInputProcessor#fieldNameOwnerTypes()} is non-empty,
 *       that set <em>replaces</em> the reflective contributions — the generated path dispatches
 *       against what it was emitted to dispatch against, not against what reflection infers. An
 *       empty set is the documented sentinel for "declares no owner set" and falls back to the
 *       reflective contributions, which is how a hand-written or previously-generated processor
 *       keeps working; that fallback is logged at debug, naming the processor class, so a stale
 *       generated class on the classpath is diagnosable rather than silent. The declared set is
 *       flat — the engine closes it transitively and bounds it with the same descent rule as any
 *       other contribution.</li>
 *   <li>A visited set terminates cycles, so a self-referential or mutually recursive graph closes.</li>
 * </ul>
 *
 * <p><strong>The platform bound is a cost bound, not a correctness bound.</strong> A JDK class is
 * prepared but never descended into, because its generic containers erase to {@code Object} or to
 * platform interfaces and so cannot yield an application class through a declared field. The stated
 * residual: an owner reachable <em>only</em> through a platform class's declared fields — say
 * {@code HashMap.Node} beneath a field declared {@code HashMap<K, V>} — is not prepared. Descending
 * would introspect JDK internals for no reachable application type.
 *
 * <p>Unlike {@link InputPolicyMetadataResolver#declaresPolicies}, which uses a throw-away resolver
 * because it answers a one-shot startup question, this walk deliberately populates the engine's own
 * long-lived metadata cache: every class it resolves is a class the engine will resolve again on the
 * request path, and the engine is component-scoped.
 */
final class OwnerTypeWalk {

    private static final Logger log = LoggerFactory.getLogger(OwnerTypeWalk.class);

    private OwnerTypeWalk() {}

    /**
     * Prepares {@code resolver} for every statically knowable owner reachable from
     * {@code declaredType}.
     *
     * @param declaredType     the body or message type; {@code null} prepares nothing
     * @param resolver         the resolver the same traversal will use at runtime; must not be
     *                         {@code null}
     * @param metadataResolver the engine's own metadata resolver, so the walk warms the cache the
     *                         request path reads; must not be {@code null}
     * @param dispatcher       the engine's own dispatcher, consulted so a generated processor can
     *                         answer for its own type instead of having reflection infer it; must
     *                         not be {@code null}
     * @throws IllegalStateException if a reachable type declares conflicting policy annotations
     * @throws RuntimeException      if a reachable type has a generated processor that exists but
     *                               cannot be instantiated — a build defect, and failing at
     *                               registration is the point
     */
    static void prepare(
            @Nullable Type declaredType,
            InputFieldNameResolver resolver,
            InputPolicyMetadataResolver metadataResolver,
            GeneratedInputProcessorDispatcher dispatcher) {
        if (declaredType == null) {
            return;
        }

        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        Class<?> classified = TypeClassifier.classify(declaredType);
        Class<?> elementClassified = TypeClassifier.elementType(declaredType);
        // A collection or array entry point is dispatched against its element class when one is
        // determinable. When it is not, the engine falls back to the raw container class classify()
        // yielded — but that fallback owner's own metadata carries no declared fields (Collection/
        // array types declare none the resolver records), so the fallback dispatch is provably a
        // schema-free no-op and the raw container class is not prepared. A raw container WITH a
        // determinable element schema is unaffected: outside this fallback, its raw class is still a
        // genuine mismatch-dispatch target (a top-level Map received where the collection was
        // declared), so it is prepared like any other frontier class below.
        if (classified != null && !(elementClassified == null && isCollectionOrArray(classified))) {
            enqueue(pending, classified);
        }
        enqueue(pending, elementClassified);

        while (!pending.isEmpty()) {
            Class<?> owner = pending.poll();
            if (!visited.add(owner)) {
                continue;
            }
            resolver.precompute(owner);
            if (!InputPolicyMetadataResolver.isDescendableObject(owner) || isPlatformType(owner)) {
                continue;
            }
            Set<Class<?>> declared = declaredOwnerTypes(owner, dispatcher);
            if (!declared.isEmpty()) {
                // The generated path answers for itself: it dispatches against what it was emitted to
                // dispatch against, so its set replaces — never supplements — the reflective one. Each
                // entry still goes through the frontier, so the descent rule bounds it like any other.
                for (Class<?> declaredOwner : declared) {
                    enqueue(pending, declaredOwner);
                }
                continue;
            }
            for (FieldPolicyMetadata field :
                    metadataResolver.resolve(owner).fields().values()) {
                // Both, never one or the other: the engine dispatches a nested fragment against the
                // field's raw declared type and each element of a collection fragment against the
                // element type, and the two are independently reachable from the same field. The raw
                // declared type is skipped for a string-typed field alone: DefaultInputObjectProcessor
                // dispatches String.class only on a wire/declared shape mismatch (never in the field's
                // own normal path, which handles the value directly), and String's own metadata
                // carries no declared fields, so that dispatch is provably a schema-free no-op — the
                // exact defect this walk exists to stop warming. A collection-of-strings or
                // collection-of-objects field's raw container class is NOT excluded here: the engine
                // reaches it the same way (a wire/declared Map mismatch) and its own metadata is
                // equally schema-free, but no fixture forces that narrower exclusion yet — see the
                // frozen conformance matrix in SanitizationProcessorRoundtripTest, which still pins
                // ConfPair/ConfFixed/ConfWeird as reflective-only surplus.
                if (!field.isStringType()) {
                    enqueue(pending, field.fieldType());
                }
                enqueue(pending, field.collectionElementType());
            }
        }
    }

    /**
     * Returns the owner set a generated processor declares for {@code owner}, or an empty set when
     * the reflective contributions apply — either because no generated processor exists, or because
     * the one that does declares no owner set.
     *
     * <p>The two empty outcomes are deliberately reported differently. No processor at all is the
     * ordinary reflective case and is silent; a processor that returns an empty set is a
     * hand-written or previously-generated class predating the owner-set contract, and is logged at
     * debug naming the class, so a stale generated processor on the classpath is diagnosable rather
     * than silent.
     *
     * @param owner      the frontier class
     * @param dispatcher the engine's own dispatcher
     * @return the declared owner types, or an empty set to fall back to the reflective walk
     */
    private static Set<Class<?>> declaredOwnerTypes(Class<?> owner, GeneratedInputProcessorDispatcher dispatcher) {
        // A Broken lookup — a generated class that exists but cannot be instantiated — propagates
        // deliberately: that is a build defect, and failing at registration is correct.
        Optional<? extends GeneratedInputProcessor<?>> generated = dispatcher.resolve(owner);
        if (generated.isEmpty()) {
            return Set.of();
        }
        Set<Class<?>> declared = generated.get().fieldNameOwnerTypes();
        if (declared.isEmpty()) {
            log.debug(
                    "Generated input processor {} declares no field-name owner types for {}; "
                            + "falling back to the reflective owner walk for this type",
                    generated.get().getClass().getName(),
                    owner.getName());
        }
        return declared;
    }

    /**
     * Adds {@code type} to the frontier when it is a class at all. The caller decides whether
     * {@code type} is a legitimate field-name owner before calling this — see the seed and per-field
     * exclusions in {@link #prepare}.
     *
     * @param pending the traversal queue
     * @param type    the candidate owner; may be {@code null}
     */
    private static void enqueue(Deque<Class<?>> pending, @Nullable Class<?> type) {
        if (type != null) {
            pending.add(type);
        }
    }

    /**
     * Returns whether {@code type} is the wire-array shape — a {@link Collection} or an array —
     * the same test {@code InputPolicyMetadataResolver.buildFieldMeta} applies before deciding
     * whether a field carries an element schema.
     *
     * @param type the candidate class
     * @return {@code true} for a {@link Collection} implementation or an array class
     */
    private static boolean isCollectionOrArray(Class<?> type) {
        return Collection.class.isAssignableFrom(type) || type.isArray();
    }

    /**
     * Returns whether {@code type} belongs to the Java platform, whose declared fields the walk does
     * not descend into.
     *
     * @param type the candidate owner
     * @return {@code true} for a {@code java.*} class
     */
    private static boolean isPlatformType(Class<?> type) {
        return isPlatformType(type.getName());
    }

    /**
     * Returns whether {@code binaryName} belongs to the Java platform (the JDK itself, not the wider
     * {@code javax.*}/{@code jakarta.*} namespaces, which carry ordinary third-party and
     * application-owned types).
     *
     * <p>The bound is {@code java.*} only, and only because a JDK class's generic containers erase to
     * {@code Object} or to platform interfaces, so a JDK class cannot yield an application type
     * through a declared field. That argument does not extend to {@code javax.*} or {@code
     * jakarta.*}: both namespaces hold ordinary classes with ordinary declared fields, including
     * application DTOs, so skipping their descent would leave a reachable owner unprepared.
     *
     * @param binaryName the candidate owner's binary name, as returned by {@link Class#getName()}
     * @return {@code true} for a {@code java.*} binary name
     */
    static boolean isPlatformType(String binaryName) {
        return binaryName.startsWith("java.");
    }
}
