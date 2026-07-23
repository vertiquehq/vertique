// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor.scan;

import dev.vertique.codegen.CodegenContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Computes the transitive closure of DTO types that participate in structured-body sanitization,
 * starting from the direct {@code @BODY} discovery roots produced by {@link RestBodyDiscovery}.
 *
 * <p>Participation rules:
 * <ul>
 *   <li><strong>Direct roots</strong> (types discovered as {@code @BODY} parameters) are included
 *       unconditionally, so route- and parameter-level policies still flow through the generated
 *       path even when the DTO carries no local sanitization annotations.</li>
 *   <li><strong>Transitive nested types</strong> are included only when their subtree carries at
 *       least one {@code @Sanitize}, {@code @Canonicalize}, {@code @SkipCanonicalization}, or
 *       {@code @SkipSanitization} annotation (directly, on a field or component, on the type,
 *       or on a meta-annotation) or any reachable nested field's type participates.</li>
 *   <li>Types outside the current compilation unit are excluded — the runtime falls back to the
 *       reflective continuation for those.</li>
 *   <li>Cycle protection via max depth {@value #MAX_DEPTH}, mirroring
 *       {@code InputPolicyMetadataResolver.MAX_DEPTH}.</li>
 * </ul>
 */
public final class DtoScanner {

    /**
     * Maximum traversal depth before a depth-exceeded warning is emitted and recursion stops.
     * Mirrors {@code InputPolicyMetadataResolver.MAX_DEPTH}.
     */
    static final int MAX_DEPTH = 10;

    // --- Collection base FQN ---
    private static final String COLLECTION_FQN = "java.util.Collection";

    private final CodegenContext ctx;
    private final AnnotationCollector annotationCollector;

    /**
     * Constructs a {@code DtoScanner} bound to the given codegen context.
     *
     * @param ctx                 the shared codegen context; must not be {@code null}
     * @param annotationCollector the collector used to gather per-field metadata; must not be
     *                            {@code null}
     */
    public DtoScanner(CodegenContext ctx, AnnotationCollector annotationCollector) {
        this.ctx = ctx;
        this.annotationCollector = annotationCollector;
    }

    /**
     * Computes the transitive closure of emittable types from the given discovery roots and
     * returns their {@link DtoModel} representations.
     *
     * @param roots the direct {@code @BODY} discovery roots; must not be {@code null}
     * @return the set of models for types that should have a generated processor; never
     *         {@code null}, may be empty
     */
    public Set<DtoModel> scanTransitive(Set<TypeElement> roots) {
        Set<DtoModel> emittable = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();

        // Seed the BFS with direct roots (unconditionally emitted)
        Deque<PendingType> queue = new ArrayDeque<>();
        for (TypeElement root : roots) {
            String fqn = root.getQualifiedName().toString();
            if (visited.add(fqn)) {
                queue.add(new PendingType(root, true, 0));
            }
        }

        while (!queue.isEmpty()) {
            PendingType pending = queue.poll();
            TypeElement type = pending.type();
            boolean isRoot = pending.isRoot();
            int depth = pending.depth();

            if (!isInCurrentCompilationUnit(type)) {
                continue;
            }

            DtoModel model = annotationCollector.collect(type);

            boolean shouldEmit = isRoot || hasOwnAnnotations(model);
            if (shouldEmit) {
                emittable.add(model);
            }

            // Walk reachable nested types regardless of whether THIS type is emittable —
            // an un-annotated mid-tier DTO may still hold a path to an annotated leaf, and
            // skipping its fields would mask the leaf from the emit set.
            for (FieldModel field : model.fields().values()) {
                if (field.nestedTypeMirror() == null) continue;
                TypeMirror nestedMirror = field.nestedTypeMirror();
                if (!(nestedMirror instanceof DeclaredType dt)) continue;
                if (!(dt.asElement() instanceof TypeElement nestedEl)) continue;
                String nestedFqn = nestedEl.getQualifiedName().toString();
                if (!visited.add(nestedFqn)) continue;
                if (AnnotationCollector.isScalarOrEnum(nestedMirror)) continue;
                if (depth + 1 >= MAX_DEPTH) {
                    ctx.diagnostics()
                            .warning(
                                    type,
                                    "DtoScanner: max traversal depth (%d) reached while scanning nested type %s "
                                            + "from %s — subtree will fall back to reflective processing",
                                    MAX_DEPTH,
                                    nestedFqn,
                                    model.qualifiedName());
                    continue;
                }
                queue.add(new PendingType(nestedEl, false, depth + 1));
            }
        }

        return emittable;
    }

    // --- Participation check ---

    /**
     * Returns {@code true} if the given model carries at least one sanitization-relevant
     * annotation directly (on its type or one of its own fields/components). Nested-type
     * annotations are <em>not</em> consulted here — they are handled independently when each
     * nested type is visited by the BFS.
     *
     * <p>An un-annotated mid-tier DTO whose subtree leads to an annotated leaf does not become
     * emittable itself: at runtime, the dispatcher's reflective continuation will walk the
     * mid-tier reflectively and dispatch the annotated leaf to its own generated processor.
     *
     * @param model the DTO model to check
     * @return {@code true} if any annotation is declared directly on this type or its fields
     */
    private boolean hasOwnAnnotations(DtoModel model) {
        if (!model.objectCanonChain().isEmpty()) return true;
        if (!model.objectSanitChain().isEmpty()) return true;
        if (model.skipCanonicalization()) return true;
        if (model.skipSanitization()) return true;
        for (FieldModel field : model.fields().values()) {
            if (!field.canonChain().isEmpty()) return true;
            if (!field.sanitChain().isEmpty()) return true;
            if (field.skipCanon()) return true;
            if (field.skipSanit()) return true;
        }
        return false;
    }

    // --- Compilation-unit check ---

    /**
     * Returns {@code true} when the given type element's source is part of the current
     * compilation unit.
     *
     * <p>Uses {@link javax.lang.model.util.Elements#getOrigin(Element)} to distinguish
     * source-originated elements from class-file or synthesized elements.
     *
     * @param type the type element to check
     * @return {@code true} when the type is from source in the current round
     */
    private boolean isInCurrentCompilationUnit(TypeElement type) {
        // getOrigin throws UnsupportedOperationException on processing environments where the
        // implementation pre-dates JEP 286-style origin classification. Treat that one
        // documented case as "assume in CU"; let any other exception propagate so we don't
        // silently mis-classify external types as in-CU due to bugs elsewhere.
        try {
            return ctx.elements().getOrigin(type) == javax.lang.model.util.Elements.Origin.EXPLICIT;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    /**
     * BFS node carrying a type element with metadata about whether it is a direct discovery root
     * and its current traversal depth.
     *
     * @param type   the type element
     * @param isRoot whether this is a direct {@code @BODY} discovery root (unconditional emit)
     * @param depth  current BFS depth from the root
     */
    private record PendingType(TypeElement type, boolean isRoot, int depth) {}
}
