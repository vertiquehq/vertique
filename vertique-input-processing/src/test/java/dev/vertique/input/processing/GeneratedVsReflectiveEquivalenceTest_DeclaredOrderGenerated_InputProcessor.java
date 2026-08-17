// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;
import static dev.vertique.input.processing.GeneratedSupport.childPath;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.DeclaredOrderGenerated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestDecodeEntities;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestStripHtml;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link DeclaredOrderGenerated},
 * mirroring the shape {@code vertique-codegen-sanitization} emits for a type whose object-level
 * chain repeats a class the field's own multi-element chain also declares: both constants are
 * emitted verbatim, in declaration order, and the composition is left to
 * {@link InputTraversalContext#compose}.
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(DeclaredOrderGenerated.class, k)}) with arms keyed on Java
 * property names, while the emitted map keeps the wire key {@code k}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm.
 */
public final class GeneratedVsReflectiveEquivalenceTest_DeclaredOrderGenerated_InputProcessor
        implements GeneratedInputProcessor<DeclaredOrderGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of(TestStripHtml.class);
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> VALUE_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> VALUE_SANIT =
            List.of(TestDecodeEntities.class, TestStripHtml.class);

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_DeclaredOrderGenerated_InputProcessor() {}

    @Override
    public Class<DeclaredOrderGenerated> targetType() {
        return DeclaredOrderGenerated.class;
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
            switch (rootCtx.logicalFieldName(DeclaredOrderGenerated.class, k)) {
                case "value" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    VALUE_CANON,
                                    VALUE_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "value",
                                    DeclaredOrderGenerated.class));
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
                                    DeclaredOrderGenerated.class,
                                    DeclaredOrderGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
