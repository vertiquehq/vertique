// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;
import static dev.vertique.input.processing.GeneratedSupport.childPath;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.FieldChainRecursiveGenerated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link FieldChainRecursiveGenerated},
 * mirroring the shape {@code vertique-codegen-sanitization} emits for a directly self-referential
 * DTO whose chain is declared on the <em>recursive link</em>: the object-level constant is empty,
 * the {@code child} arm's per-field constant is non-empty, and that arm dispatches back at its own
 * target type — so the field-level chain is offered to {@code descend} once per level of the
 * intermediate.
 *
 * <p>The {@code child} arm passes {@code "child"} as {@code descend}'s field-name key, exactly as the
 * emitter writes the arm's own {@code case} literal into that argument. That key is what bounds the
 * composed chain here: without it, {@code CHILD_CANON} would be appended afresh at every level and
 * the count of applications per string value would track the request's nesting depth.
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(FieldChainRecursiveGenerated.class, k)}) with arms keyed on Java
 * property names, while the emitted map keeps the wire key {@code k}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm.
 */
public final class GeneratedVsReflectiveEquivalenceTest_FieldChainRecursiveGenerated_InputProcessor
        implements GeneratedInputProcessor<FieldChainRecursiveGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> NOTE_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> NOTE_SANIT = List.of();

    private static final List<Class<? extends Canonicalizer>> CHILD_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> CHILD_SANIT = List.of();

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_FieldChainRecursiveGenerated_InputProcessor() {}

    @Override
    public Class<FieldChainRecursiveGenerated> targetType() {
        return FieldChainRecursiveGenerated.class;
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
            switch (rootCtx.logicalFieldName(FieldChainRecursiveGenerated.class, k)) {
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
                                    FieldChainRecursiveGenerated.class));
                case "child" -> {
                    InputTraversalContext nestedCtx = rootCtx.descend(
                            FieldChainRecursiveGenerated.class,
                            "child",
                            OBJ_CANON,
                            OBJ_SANIT,
                            OBJ_SKIP_CANON,
                            OBJ_SKIP_SANIT,
                            CHILD_CANON,
                            CHILD_SANIT,
                            false,
                            false);
                    out.put(
                            k,
                            dispatcher.dispatchNested(
                                    v,
                                    FieldChainRecursiveGenerated.class,
                                    policies,
                                    location,
                                    resolver,
                                    nestedCtx,
                                    childPath,
                                    FieldChainRecursiveGenerated.class));
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
                                    FieldChainRecursiveGenerated.class,
                                    FieldChainRecursiveGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
