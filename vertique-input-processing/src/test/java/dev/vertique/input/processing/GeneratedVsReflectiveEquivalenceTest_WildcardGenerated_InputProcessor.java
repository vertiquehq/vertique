// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.dispatchObjectCollection;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.Inner;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.WildcardGenerated;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link WildcardGenerated}, mirroring
 * the shape that {@code vertique-codegen-sanitization} emits for element-bearing fields. Used by
 * {@link GeneratedVsReflectiveEquivalenceTest} to assert byte-equivalence with the reflective
 * walker on the structurally-identical
 * {@link GeneratedVsReflectiveEquivalenceTest.WildcardReflective} fixture.
 *
 * <p>All three arms dispatch at the element type {@link Inner}: the APT-time collector normalizes a
 * bounded type argument to its upper bound, and an array's component type is its element type, so
 * {@code List<Inner>}, {@code List<? extends Inner>} and {@code Inner[]} carry one and the same
 * element schema. The wire shape is a JSON array in all three cases, which the intermediate
 * represents as a {@link List}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm: binary name of
 * {@link WildcardGenerated} is {@code ...GeneratedVsReflectiveEquivalenceTest$WildcardGenerated};
 * flattening {@code '$' → '_'} and appending {@code _InputProcessor} yields this class's FQN.
 */
public final class GeneratedVsReflectiveEquivalenceTest_WildcardGenerated_InputProcessor
        implements GeneratedInputProcessor<WildcardGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_WildcardGenerated_InputProcessor() {}

    @Override
    public Class<WildcardGenerated> targetType() {
        return WildcardGenerated.class;
    }

    @Override
    public Object process(
            Object intermediate,
            EffectiveInputPolicies policies,
            InputLocation location,
            ChainResolver resolver,
            GeneratedInputProcessorDispatcher dispatcher,
            @Nullable InputTraversalContext parent,
            String parentPath) {

        if (!(intermediate instanceof Map<?, ?> raw)) {
            return intermediate;
        }
        InputTraversalContext rootCtx = parent != null ? parent : InputTraversalContext.fromPolicies(policies);

        Map<String, Object> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v == null) {
                out.put(k, null);
                continue;
            }
            String childPath = GeneratedSupport.childPath(parentPath, k);
            switch (k) {
                case "invariant", "bounded", "array" -> {
                    InputTraversalContext elementCtx = rootCtx.descend(
                            OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT, null, null, false, false);
                    out.put(
                            k,
                            dispatchObjectCollection(
                                    v,
                                    Inner.class,
                                    policies,
                                    location,
                                    resolver,
                                    dispatcher,
                                    elementCtx,
                                    childPath,
                                    Inner.class));
                }
                default ->
                    out.put(
                            k,
                            GeneratedSupport.applyDefault(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    List.of(),
                                    List.of(),
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    k,
                                    WildcardGenerated.class,
                                    WildcardGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
