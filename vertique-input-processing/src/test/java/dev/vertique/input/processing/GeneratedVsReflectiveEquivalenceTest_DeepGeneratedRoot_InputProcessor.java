// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.childPath;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.DeepGeneratedRoot;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.DeepLink2;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link DeepGeneratedRoot}, the root of
 * the twelve-level chain of distinct types used by
 * {@link GeneratedVsReflectiveEquivalenceTest}. Its single {@code child} arm mirrors the codegen
 * NESTED_DTO shape: descend the context, then dispatch the nested value at the declared field type.
 * The remaining eleven levels have no companion, so they resume on the reflective walker.
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(DeepGeneratedRoot.class, k)}) with arms keyed on Java property
 * names, while the emitted map keeps the wire key {@code k}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm.
 */
public final class GeneratedVsReflectiveEquivalenceTest_DeepGeneratedRoot_InputProcessor
        implements GeneratedInputProcessor<DeepGeneratedRoot> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_DeepGeneratedRoot_InputProcessor() {}

    @Override
    public Class<DeepGeneratedRoot> targetType() {
        return DeepGeneratedRoot.class;
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
        InputTraversalContext rootCtx =
                parent != null ? parent : InputTraversalContext.fromPolicies(policies, InputFieldNameResolver.IDENTITY);

        Map<String, Object> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v == null) {
                out.put(k, null);
                continue;
            }
            String childPath = childPath(parentPath, k);
            switch (rootCtx.logicalFieldName(DeepGeneratedRoot.class, k)) {
                case "child" -> {
                    InputTraversalContext nestedCtx = rootCtx.descend(
                            OBJ_CANON, OBJ_SANIT, OBJ_SKIP_CANON, OBJ_SKIP_SANIT, null, null, false, false);
                    out.put(
                            k,
                            dispatcher.dispatchNested(
                                    v,
                                    DeepLink2.class,
                                    policies,
                                    location,
                                    resolver,
                                    nestedCtx,
                                    childPath,
                                    DeepLink2.class));
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
                                    DeepGeneratedRoot.class,
                                    DeepGeneratedRoot.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
