// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyStringCollection;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.EnumElementGenerated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestUpper;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link EnumElementGenerated}, mirroring
 * the shape that {@code vertique-codegen-sanitization} emits for a DTO whose collection and array
 * fields all carry a <em>scalar</em> element type. Used by
 * {@link GeneratedVsReflectiveEquivalenceTest} to assert byte-equivalence with the reflective walker
 * on the structurally-identical
 * {@link GeneratedVsReflectiveEquivalenceTest.EnumElementReflective} fixture.
 *
 * <p>Arm selection follows {@code AnnotationCollector.buildFieldModel}: an enum, an enum array and
 * the primitive {@code Optional} specializations are all scalar leaves ({@code isScalarOrEnum}), so
 * {@code tags}, {@code statuses}, {@code counts}, {@code totals}, {@code ratios} and the
 * {@code List<UUID>} control produce no field model at all and fall through to the default
 * {@code applyDefault} arm. Only {@code chained} (an annotated {@code OTHER}-kind field, emitted by
 * {@code buildOtherFieldArm} with the erased declared type as the nested-map owner) and
 * {@code labels} (a collection of strings) get their own arm.
 *
 * <p>As the emitter does, the switch selects on the projected logical name
 * ({@code rootCtx.logicalFieldName(EnumElementGenerated.class, k)}) with arms keyed on Java property
 * names, while the emitted map keeps the wire key {@code k}.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm: binary name of
 * {@link EnumElementGenerated} is
 * {@code ...GeneratedVsReflectiveEquivalenceTest$EnumElementGenerated}; flattening {@code '$' → '_'}
 * and appending {@code _InputProcessor} yields this class's FQN.
 */
public final class GeneratedVsReflectiveEquivalenceTest_EnumElementGenerated_InputProcessor
        implements GeneratedInputProcessor<EnumElementGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> CHAINED_CANON = List.of(TestUpper.class);
    private static final List<Class<? extends Sanitizer>> CHAINED_SANIT = List.of();
    private static final List<Class<? extends Canonicalizer>> LABELS_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> LABELS_SANIT = List.of();

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_EnumElementGenerated_InputProcessor() {}

    @Override
    public Class<EnumElementGenerated> targetType() {
        return EnumElementGenerated.class;
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
            String childPath = GeneratedSupport.childPath(parentPath, k);
            switch (rootCtx.logicalFieldName(EnumElementGenerated.class, k)) {
                case "chained" ->
                    out.put(
                            k,
                            GeneratedSupport.applyDefault(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    CHAINED_CANON,
                                    CHAINED_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "chained",
                                    EnumElementGenerated.class,
                                    List.class,
                                    dispatcher));
                case "labels" ->
                    out.put(
                            k,
                            applyStringCollection(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    LABELS_CANON,
                                    LABELS_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    EnumElementGenerated.class));
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
                                    EnumElementGenerated.class,
                                    EnumElementGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
