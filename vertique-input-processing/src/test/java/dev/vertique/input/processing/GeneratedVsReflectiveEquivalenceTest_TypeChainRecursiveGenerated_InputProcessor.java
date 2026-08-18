// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;
import static dev.vertique.input.processing.GeneratedSupport.childPath;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TypeChainRecursiveGenerated;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link TypeChainRecursiveGenerated},
 * mirroring the shape {@code vertique-codegen-sanitization} emits for a directly self-referential
 * DTO that carries a <em>type-level</em> chain: the object-level constant is non-empty and the
 * {@code child} arm dispatches back at its own target type, so the object-level chain is offered
 * to {@code descend} once per level of the intermediate.
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(TypeChainRecursiveGenerated.class, k)}) with arms keyed on Java
 * property names, while the emitted map keeps the wire key {@code k}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm.
 */
public final class GeneratedVsReflectiveEquivalenceTest_TypeChainRecursiveGenerated_InputProcessor
        implements GeneratedInputProcessor<TypeChainRecursiveGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> NOTE_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> NOTE_SANIT = List.of();

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_TypeChainRecursiveGenerated_InputProcessor() {}

    @Override
    public Class<TypeChainRecursiveGenerated> targetType() {
        return TypeChainRecursiveGenerated.class;
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
            switch (rootCtx.logicalFieldName(TypeChainRecursiveGenerated.class, k)) {
                case "note" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    NOTE_CANON,
                                    NOTE_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "note",
                                    TypeChainRecursiveGenerated.class));
                case "child" -> {
                    InputTraversalContext nestedCtx = rootCtx.descend(
                            TypeChainRecursiveGenerated.class,
                            "child",
                            OBJ_CANON,
                            OBJ_SANIT,
                            OBJ_SKIP_CANON,
                            OBJ_SKIP_SANIT,
                            null,
                            null,
                            false,
                            false);
                    out.put(
                            k,
                            dispatcher.dispatchNested(
                                    v,
                                    TypeChainRecursiveGenerated.class,
                                    policies,
                                    location,
                                    resolver,
                                    nestedCtx,
                                    childPath,
                                    TypeChainRecursiveGenerated.class));
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
                                    TypeChainRecursiveGenerated.class,
                                    TypeChainRecursiveGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
