// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.GeneratedSkip;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link GeneratedSkip}, which
 * carries {@code @SkipCanonicalization} at the type level. Mirrors the codegen output for the
 * sticky-skip equivalence scenario.
 */
public final class GeneratedVsReflectiveEquivalenceTest_GeneratedSkip_InputProcessor
        implements GeneratedInputProcessor<GeneratedSkip> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = true; // type-level @SkipCanonicalization
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> SKIPPED_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> SKIPPED_SANIT = List.of();

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_GeneratedSkip_InputProcessor() {}

    @Override
    public Class<GeneratedSkip> targetType() {
        return GeneratedSkip.class;
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
            if ("skipped".equals(k)) {
                String childPath = parentPath.isEmpty() ? k : parentPath + "." + k;
                out.put(
                        k,
                        applyString(
                                v,
                                rootCtx,
                                OBJ_CANON,
                                OBJ_SANIT,
                                OBJ_SKIP_CANON,
                                OBJ_SKIP_SANIT,
                                SKIPPED_CANON,
                                SKIPPED_SANIT,
                                false,
                                false,
                                resolver,
                                location,
                                childPath,
                                k,
                                GeneratedSkip.class));
            } else {
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
                                parentPath.isEmpty() ? k : parentPath + "." + k,
                                k,
                                GeneratedSkip.class,
                                GeneratedSkip.class,
                                dispatcher));
            }
        }
        return out;
    }
}
