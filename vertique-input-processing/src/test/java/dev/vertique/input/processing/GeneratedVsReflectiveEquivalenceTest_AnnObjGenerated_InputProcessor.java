// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.AnnObjGenerated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion for {@link AnnObjGenerated}, which declares a single annotated
 * {@code @Canonicalize(TestTrim.class) Object misc} field. Mirrors the codegen output for an
 * {@code OTHER}-kind field with field-level annotations: an explicit switch arm calling
 * {@link GeneratedSupport#applyDefault(Object, InputTraversalContext, List, List, boolean, boolean, List, List, boolean, boolean, ChainResolver, InputLocation, String, String, Class, Class, GeneratedInputProcessorDispatcher) applyDefault}
 * with {@link AnnObjGenerated} as {@code parentOwnerType} (for string and list-element values)
 * and {@code Object.class} (the field's declared type) as {@code nestedMapOwnerType} (for
 * nested map values).
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(AnnObjGenerated.class, k)}) with arms keyed on Java property
 * names, while the emitted map keeps the wire key {@code k}.
 */
public final class GeneratedVsReflectiveEquivalenceTest_AnnObjGenerated_InputProcessor
        implements GeneratedInputProcessor<AnnObjGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> MISC_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> MISC_SANIT = List.of();

    /** Public no-arg constructor for {@code Class.forName}-based instantiation. */
    public GeneratedVsReflectiveEquivalenceTest_AnnObjGenerated_InputProcessor() {}

    @Override
    public Class<AnnObjGenerated> targetType() {
        return AnnObjGenerated.class;
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
            switch (rootCtx.logicalFieldName(AnnObjGenerated.class, k)) {
                case "misc" ->
                    out.put(
                            k,
                            GeneratedSupport.applyDefault(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    MISC_CANON,
                                    MISC_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "misc",
                                    AnnObjGenerated.class,
                                    Object.class,
                                    dispatcher));
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
                                    AnnObjGenerated.class,
                                    AnnObjGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
