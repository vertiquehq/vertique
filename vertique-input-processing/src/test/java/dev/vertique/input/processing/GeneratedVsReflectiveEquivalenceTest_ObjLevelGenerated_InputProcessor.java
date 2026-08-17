// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.ObjLevelGenerated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion for {@link ObjLevelGenerated}, which carries class-level
 * {@code @Canonicalize(TestTrim.class)}. Mirrors the codegen output: object-level constants
 * carry the type chain; the {@code default} arm calls
 * {@link GeneratedSupport#applyDefault(Object, InputTraversalContext, java.util.List, java.util.List, boolean, boolean, java.util.List, java.util.List, boolean, boolean, ChainResolver, InputLocation, String, String, Class, GeneratedInputProcessorDispatcher) applyDefault}
 * with those object-level constants so unknown Jackson keys still receive the type chain —
 * matching the reflective walker.
 */
public final class GeneratedVsReflectiveEquivalenceTest_ObjLevelGenerated_InputProcessor
        implements GeneratedInputProcessor<ObjLevelGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> KNOWN_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> KNOWN_SANIT = List.of();

    /** Public no-arg constructor for {@code Class.forName}-based instantiation. */
    public GeneratedVsReflectiveEquivalenceTest_ObjLevelGenerated_InputProcessor() {}

    @Override
    public Class<ObjLevelGenerated> targetType() {
        return ObjLevelGenerated.class;
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
            String childPath = parentPath.isEmpty() ? k : parentPath + "." + k;
            switch (k) {
                case "known" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    KNOWN_CANON,
                                    KNOWN_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    k,
                                    ObjLevelGenerated.class));
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
                                    ObjLevelGenerated.class,
                                    ObjLevelGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
