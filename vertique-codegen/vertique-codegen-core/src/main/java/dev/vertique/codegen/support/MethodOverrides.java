// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Shared APT utility for deduplicating method lists by erased signature.
 *
 * <p>Both {@code KafkaListenerScanner} and {@code ContractScanner} use
 * {@link javax.lang.model.util.Elements#getAllMembers(TypeElement)} to collect inherited methods.
 * On certain APT implementations (or with certain inheritance shapes), the same logical method can
 * appear multiple times — once for each interface in the hierarchy that declares it. Without
 * deduplication the scanners would emit duplicate operations or routes and ultimately produce
 * generated code that fails to compile.
 *
 * <p>This class centralises the dedup algorithm so both scanners agree on the semantics.
 *
 * <h2>Algorithm</h2>
 * Methods are grouped by their <em>erased signature</em>: {@code simpleName(erasedParam1,…)}.
 * Within each group the retained set is reduced to mirror {@code Class#getMethods()} semantics:
 * <ul>
 *   <li><strong>Override chain</strong> (one enclosing type a proper sub-type of another, by
 *       {@link Types#isAssignable}) — the most-derived declaration wins; super-declarations are
 *       dropped.</li>
 *   <li><strong>Same-enclosing-type duplicate</strong> — the same method reported twice by
 *       {@code getAllMembers}, or reached via a diamond through a common ancestor — collapsed to one.</li>
 *   <li><strong>Genuine sibling</strong> — two methods with the same erased signature declared by
 *       <em>unrelated</em> super-interfaces, neither enclosing type a sub-type of the other —
 *       <em>both are retained, regardless of return type</em>.
 * </ul>
 *
 * <h2>Why siblings are preserved, not collapsed</h2>
 * Unlike a same-erasure pair with <em>different parameter types</em> (e.g.
 * {@code A.op(List<String>)} vs {@code B.op(List<Integer>)}), which <em>is</em> a JLS §8.4.8.3
 * "name clash" that {@code javac} rejects before any processor runs, a same-erased-signature pair
 * inherited from unrelated interfaces compiles: {@code interface C extends A, B} where both declare
 * {@code op(P)} is legal — whether their return types are covariant
 * ({@code A.op():CharSequence} / {@code B.op():String}) or identical — and the reflective runtime's
 * {@code Class#getMethods()} returns <em>both</em> declarations. The two declarations may even carry
 * different annotations (one {@code @WorkflowStart}, one bare). Collapsing the pair by encounter
 * order — including when their return types match — would let codegen validate against one
 * declaration while the runtime sees both, a silent codegen/runtime divergence. Retaining both lets
 * the caller's per-method validation reject (or accept) the contract identically to the runtime;
 * a consumer that emits a concrete class (which cannot override the same signature twice) rejects
 * the ambiguous shape explicitly before emission.
 *
 * <p>All methods are stateless and thread-safe. This class cannot be instantiated.
 */
public final class MethodOverrides {

    private MethodOverrides() {}

    /**
     * De-duplicates {@code methods} by erased signature (method name + erased parameter-type FQNs),
     * retaining the most-specific override when a sub-interface re-declares a super-interface method.
     *
     * <p>For each erased-signature group the retained set mirrors {@code Class#getMethods()}: an
     * override chain collapses to the most-derived declaration (the candidate whose enclosing type
     * is assignable, via {@link Types#isAssignable}, to another's), a same-enclosing-type duplicate
     * (the same method reported twice, or a diamond through a common ancestor) collapses to one, and
     * a genuine sibling (same erased signature declared by unrelated super-interfaces, neither
     * enclosing type a sub-type of the other) is retained alongside <em>regardless of return type</em>.
     * Retaining siblings keeps codegen scanners and the reflective runtime seeing the same set of
     * methods rather than silently collapsing a pair the runtime reports as two (see the class
     * javadoc for why such a pair compiles).
     *
     * @param methods all methods to deduplicate (typically from
     *                {@link javax.lang.model.util.Elements#getAllMembers(TypeElement)} after
     *                Object-method and static-method removal); may contain duplicates due to
     *                inheritance; must not be {@code null}
     * @param types   the {@link Types} utility from the processing environment; must not be
     *                {@code null}
     * @return a new list with one method per (erased signature, declaring interface) — genuine
     *         siblings retained — in encounter order; never {@code null}
     */
    public static List<ExecutableElement> deduplicateByErasedSignature(List<ExecutableElement> methods, Types types) {
        Map<String, List<ExecutableElement>> bySignature = new LinkedHashMap<>();
        for (ExecutableElement method : methods) {
            String sig = erasedSignature(method, types);
            mergeInto(bySignature.computeIfAbsent(sig, k -> new ArrayList<>()), method, types);
        }
        return bySignature.values().stream().flatMap(List::stream).collect(Collectors.toList());
    }

    /**
     * Merges {@code method} into the retained set for its erased-signature group, preserving
     * {@code Class#getMethods()} semantics: an override chain collapses to the most-derived
     * declaration, a duplicate declaration from the <em>same</em> enclosing type (e.g. the same
     * inherited method reported twice, or a diamond through a common ancestor) collapses to one, and
     * every genuinely distinct sibling — two methods with the same erased signature declared by
     * <em>unrelated</em> super-interfaces, neither enclosing type a sub-type of the other — is
     * retained alongside, <strong>regardless of return type</strong>.
     *
     * <p>Return type is deliberately <em>not</em> a merge criterion. {@code Class#getMethods()}
     * reports both declarations of a same-signature sibling pair even when their return types are
     * identical (verified), and the two declarations may carry different annotations — so collapsing
     * a same-return sibling by encounter order would let the caller's per-method validation see only
     * one (e.g. the {@code @WorkflowStart}-annotated one) while the runtime, iterating
     * {@code getMethods()}, sees both (including the un-annotated one). Retaining both keeps codegen
     * and runtime validating the identical method multiset.
     *
     * @param group  the already-retained methods for this erased signature; mutated in place
     * @param method the candidate to merge in
     * @param types  the {@link Types} utility from the processing environment
     */
    private static void mergeInto(List<ExecutableElement> group, ExecutableElement method, Types types) {
        // Drop any super-declaration that `method` strictly overrides (same erased signature, with
        // `method` declared on a sub-type) — the most-derived override wins, as getMethods() reports.
        group.removeIf(existing -> overrides(method, existing, types));
        // `method` is redundant only when an already-retained entry covers it: that entry strictly
        // overrides `method` (it is the more-derived declaration in an override chain), or it is
        // declared by the same enclosing type (the same method reported twice, or reached via a
        // diamond through a common ancestor). A declaration from an UNRELATED super-interface is a
        // genuine sibling and is retained — so the caller validates the same method multiset the
        // reflective runtime sees via Class#getMethods(), rather than collapsing one by encounter order.
        for (ExecutableElement existing : group) {
            if (overrides(existing, method, types) || sameEnclosingType(existing, method, types)) {
                return;
            }
        }
        group.add(method);
    }

    /**
     * Returns {@code true} when both methods are declared by the same enclosing type — the same
     * method reported more than once by {@code getAllMembers}, or a method reached through a diamond
     * via a common ancestor. Such duplicates collapse to one; declarations from distinct enclosing
     * types are genuine siblings and are kept.
     *
     * @param a     one method
     * @param b     the other method
     * @param types the {@link Types} utility from the processing environment
     * @return {@code true} if {@code a} and {@code b} have the same enclosing type
     */
    private static boolean sameEnclosingType(ExecutableElement a, ExecutableElement b, Types types) {
        TypeMirror aType = ((TypeElement) a.getEnclosingElement()).asType();
        TypeMirror bType = ((TypeElement) b.getEnclosingElement()).asType();
        return types.isSameType(aType, bType);
    }

    /**
     * Returns {@code true} when {@code sub} strictly overrides {@code sup}: they have the same erased
     * signature (assumed — both are in the same group) and {@code sub}'s enclosing type is a proper
     * sub-type of {@code sup}'s. {@code isAssignable(A, B)} means {@code A} is a sub-type of
     * {@code B}; the same-type guard excludes a method comparing against itself.
     *
     * @param sub   the candidate more-derived method
     * @param sup   the candidate super-declaration
     * @param types the {@link Types} utility from the processing environment
     * @return {@code true} if {@code sub} is declared on a proper sub-type of {@code sup}'s enclosing
     *         type
     */
    private static boolean overrides(ExecutableElement sub, ExecutableElement sup, Types types) {
        TypeMirror subType = ((TypeElement) sub.getEnclosingElement()).asType();
        TypeMirror supType = ((TypeElement) sup.getEnclosingElement()).asType();
        return !types.isSameType(subType, supType) && types.isAssignable(subType, supType);
    }

    /**
     * Returns the erased signature key for the given method: the method's simple name followed by
     * the erased FQNs of its parameter types, separated by commas and wrapped in parentheses.
     *
     * <p>For example: {@code onOrder(com.example.SomeEvent)} or {@code start()}.
     *
     * @param method the executable element whose signature to compute; must not be {@code null}
     * @param types  the {@link Types} utility from the processing environment; must not be
     *               {@code null}
     * @return the erased-signature key; never {@code null}
     */
    public static String erasedSignature(ExecutableElement method, Types types) {
        String paramTypes = method.getParameters().stream()
                .map(p -> types.erasure(p.asType()).toString())
                .collect(Collectors.joining(","));
        return method.getSimpleName() + "(" + paramTypes + ")";
    }
}
