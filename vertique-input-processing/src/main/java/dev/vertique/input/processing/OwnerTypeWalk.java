// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

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
 * <p>The rule, in full:
 *
 * <ul>
 *   <li><strong>Seed</strong> — {@link TypeClassifier#classify} and {@link TypeClassifier#elementType}
 *       of the declared type. Together these reproduce {@link DefaultInputObjectProcessor}'s
 *       entry-point classification, <em>including</em> the raw container class it uses as owner when
 *       no element schema is determinable.</li>
 *   <li><strong>Every frontier class is prepared</strong>, with no filtering. {@code String},
 *       {@code List}, {@code Map}, an enum and {@code Object} are all legitimate owners: a field's
 *       raw declared type is recorded unconditionally, and a wire fragment whose shape disagrees
 *       with the declared shape is dispatched against that raw class.</li>
 *   <li><strong>Descent</strong> follows only a class that {@link InputPolicyMetadataResolver#isDescendableObject}
 *       accepts and that is not a platform type. Contributions are <em>both</em>
 *       {@link FieldPolicyMetadata#fieldType()} and {@link FieldPolicyMetadata#collectionElementType()}
 *       of every recorded field — both, never one or the other, because the engine can dispatch
 *       against either depending on the wire shape it meets.</li>
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
     * @throws IllegalStateException if a reachable type declares conflicting policy annotations
     */
    static void prepare(
            @Nullable Type declaredType,
            InputFieldNameResolver resolver,
            InputPolicyMetadataResolver metadataResolver) {
        if (declaredType == null) {
            return;
        }

        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        enqueue(pending, TypeClassifier.classify(declaredType));
        // A collection or array entry point is dispatched against its element class, or — when no
        // element schema is determinable — against the raw container class classify already yielded.
        enqueue(pending, TypeClassifier.elementType(declaredType));

        while (!pending.isEmpty()) {
            Class<?> owner = pending.poll();
            if (!visited.add(owner)) {
                continue;
            }
            resolver.precompute(owner);
            if (!InputPolicyMetadataResolver.isDescendableObject(owner) || isPlatformType(owner)) {
                continue;
            }
            for (FieldPolicyMetadata field :
                    metadataResolver.resolve(owner).fields().values()) {
                // Both, never one or the other: the engine dispatches a nested fragment against the
                // field's raw declared type and each element of a collection fragment against the
                // element type, and the two are independently reachable from the same field.
                enqueue(pending, field.fieldType());
                enqueue(pending, field.collectionElementType());
            }
        }
    }

    /**
     * Adds {@code type} to the frontier when it is a class at all. No further filtering: every
     * frontier class is a legitimate owner.
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
     * Returns whether {@code type} belongs to the Java platform, whose declared fields the walk does
     * not descend into.
     *
     * @param type the candidate owner
     * @return {@code true} for a {@code java.*}, {@code javax.*} or {@code jakarta.*} class
     */
    private static boolean isPlatformType(Class<?> type) {
        String binaryName = type.getName();
        return binaryName.startsWith("java.") || binaryName.startsWith("javax.") || binaryName.startsWith("jakarta.");
    }
}
