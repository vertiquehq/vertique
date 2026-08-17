// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyStringCollection;
import static dev.vertique.input.processing.GeneratedSupport.childPath;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.CollectionChainRecursiveGenerated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestStripHtml;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for
 * {@link CollectionChainRecursiveGenerated}, mirroring the shape
 * {@code vertique-codegen-sanitization} emits for a self-referential DTO that carries a
 * <em>type-level</em> chain alongside a {@code List<String>} field with a chain of its own: the
 * {@code child} arm dispatches back at its own target type, so the object-level site is on the
 * descent path by the time any nested level's {@code tags} arm runs, and that arm routes through
 * {@link GeneratedSupport#applyStringCollection} — the helper whose frozen signature carries no
 * logical name for the field site it offers.
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(CollectionChainRecursiveGenerated.class, k)}) with arms keyed on
 * Java property names, while the emitted map keeps the wire key {@code k}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm.
 */
public final class GeneratedVsReflectiveEquivalenceTest_CollectionChainRecursiveGenerated_InputProcessor
        implements GeneratedInputProcessor<CollectionChainRecursiveGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> TAGS_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> TAGS_SANIT = List.of(TestStripHtml.class);

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_CollectionChainRecursiveGenerated_InputProcessor() {}

    @Override
    public Class<CollectionChainRecursiveGenerated> targetType() {
        return CollectionChainRecursiveGenerated.class;
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
            switch (rootCtx.logicalFieldName(CollectionChainRecursiveGenerated.class, k)) {
                case "tags" ->
                    out.put(
                            k,
                            applyStringCollection(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    TAGS_CANON,
                                    TAGS_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    CollectionChainRecursiveGenerated.class));
                case "child" -> {
                    InputTraversalContext nestedCtx = rootCtx.descend(
                            CollectionChainRecursiveGenerated.class,
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
                                    CollectionChainRecursiveGenerated.class,
                                    policies,
                                    location,
                                    resolver,
                                    nestedCtx,
                                    childPath,
                                    CollectionChainRecursiveGenerated.class));
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
                                    CollectionChainRecursiveGenerated.class,
                                    CollectionChainRecursiveGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
