// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;
import static dev.vertique.input.processing.GeneratedSupport.applyStringCollection;
import static dev.vertique.input.processing.GeneratedSupport.dispatchObjectCollection;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.Generated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.Inner;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestStripDots;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link Generated}, mirroring the
 * shape that {@code vertique-codegen-sanitization} will emit. Used by
 * {@link GeneratedVsReflectiveEquivalenceTest} to assert byte-equivalence with the reflective
 * walker on the structurally-identical {@link GeneratedVsReflectiveEquivalenceTest.Reflective}
 * fixture.
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(Generated.class, k)}) with arms keyed on Java property names,
 * while the emitted map keeps the wire key {@code k}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm: binary name of
 * {@link Generated} is {@code ...GeneratedVsReflectiveEquivalenceTest$Generated}; flattening
 * {@code '$' → '_'} and appending {@code _InputProcessor} yields this class's FQN.
 */
public final class GeneratedVsReflectiveEquivalenceTest_Generated_InputProcessor
        implements GeneratedInputProcessor<Generated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> TRIMMED_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> TRIMMED_SANIT = List.of();
    private static final List<Class<? extends Canonicalizer>> DOTTY_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> DOTTY_SANIT = List.of(TestStripDots.class);
    private static final List<Class<? extends Canonicalizer>> UNANN_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> UNANN_SANIT = List.of();
    private static final List<Class<? extends Canonicalizer>> TAGS_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> TAGS_SANIT = List.of();

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_Generated_InputProcessor() {}

    @Override
    public Class<Generated> targetType() {
        return Generated.class;
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
            switch (rootCtx.logicalFieldName(Generated.class, k)) {
                case "trimmed" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    TRIMMED_CANON,
                                    TRIMMED_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "trimmed",
                                    Generated.class));
                case "dotty" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    DOTTY_CANON,
                                    DOTTY_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "dotty",
                                    Generated.class));
                case "unannotated" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    UNANN_CANON,
                                    UNANN_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "unannotated",
                                    Generated.class));
                case "nested" -> {
                    InputTraversalContext nestedCtx = rootCtx.descend(
                            Generated.class,
                            "nested",
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
                                    v, Inner.class, policies, location, resolver, nestedCtx, childPath, Inner.class));
                }
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
                                    Generated.class));
                case "inners" -> {
                    InputTraversalContext innerCtx = rootCtx.descend(
                            Generated.class,
                            "inners",
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
                            dispatchObjectCollection(
                                    v,
                                    Inner.class,
                                    policies,
                                    location,
                                    resolver,
                                    dispatcher,
                                    innerCtx,
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
                                    Generated.class,
                                    Generated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
