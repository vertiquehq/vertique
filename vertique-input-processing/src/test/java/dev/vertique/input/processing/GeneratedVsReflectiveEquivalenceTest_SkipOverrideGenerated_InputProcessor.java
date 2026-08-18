// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;
import static dev.vertique.input.processing.GeneratedSupport.childPath;
import static dev.vertique.input.processing.GeneratedSupport.dispatchObjectCollection;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.PlainInner;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.SkipOverrideGenerated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link SkipOverrideGenerated},
 * mirroring the shape {@code vertique-codegen-sanitization} emits for an owner type carrying
 * {@code @SkipCanonicalization} whose fields each declare their own chain: the object-level skip
 * constant is {@code true} and every arm hands its own field chain to {@code applyString} or to
 * {@code descend}.
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(SkipOverrideGenerated.class, k)}) with arms keyed on Java
 * property names, while the emitted map keeps the wire key {@code k}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm.
 */
public final class GeneratedVsReflectiveEquivalenceTest_SkipOverrideGenerated_InputProcessor
        implements GeneratedInputProcessor<SkipOverrideGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = true; // type-level @SkipCanonicalization
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> DIRECT_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> DIRECT_SANIT = List.of();
    private static final List<Class<? extends Canonicalizer>> NESTED_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> NESTED_SANIT = List.of();
    private static final List<Class<? extends Canonicalizer>> MANY_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> MANY_SANIT = List.of();

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_SkipOverrideGenerated_InputProcessor() {}

    @Override
    public Class<SkipOverrideGenerated> targetType() {
        return SkipOverrideGenerated.class;
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
            switch (rootCtx.logicalFieldName(SkipOverrideGenerated.class, k)) {
                case "direct" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    DIRECT_CANON,
                                    DIRECT_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "direct",
                                    SkipOverrideGenerated.class));
                case "nested" -> {
                    InputTraversalContext nestedCtx = rootCtx.descend(
                            SkipOverrideGenerated.class,
                            "nested",
                            OBJ_CANON,
                            OBJ_SANIT,
                            OBJ_SKIP_CANON,
                            OBJ_SKIP_SANIT,
                            NESTED_CANON,
                            NESTED_SANIT,
                            false,
                            false);
                    out.put(
                            k,
                            dispatcher.dispatchNested(
                                    v,
                                    PlainInner.class,
                                    policies,
                                    location,
                                    resolver,
                                    nestedCtx,
                                    childPath,
                                    PlainInner.class));
                }
                case "many" -> {
                    InputTraversalContext innerCtx = rootCtx.descend(
                            SkipOverrideGenerated.class,
                            "many",
                            OBJ_CANON,
                            OBJ_SANIT,
                            OBJ_SKIP_CANON,
                            OBJ_SKIP_SANIT,
                            MANY_CANON,
                            MANY_SANIT,
                            false,
                            false);
                    out.put(
                            k,
                            dispatchObjectCollection(
                                    v,
                                    PlainInner.class,
                                    policies,
                                    location,
                                    resolver,
                                    dispatcher,
                                    innerCtx,
                                    childPath,
                                    PlainInner.class));
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
                                    SkipOverrideGenerated.class,
                                    SkipOverrideGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
