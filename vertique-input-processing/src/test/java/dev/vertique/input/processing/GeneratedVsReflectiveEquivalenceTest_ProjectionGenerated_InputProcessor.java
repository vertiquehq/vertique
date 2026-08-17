// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.ProjectionGenerated;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestStripDots;
import dev.vertique.input.processing.GeneratedVsReflectiveEquivalenceTest.TestTrim;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link ProjectionGenerated}, mirroring
 * the shape {@code vertique-codegen-sanitization} emits once the switch is keyed on the projected
 * logical name: {@code switch (ctx.logicalFieldName(Dto.class, wireKey))}, with the output map
 * keeping the wire key so the codec still binds what the caller sent.
 *
 * <p>Used by {@link GeneratedVsReflectiveEquivalenceTest} to assert byte-equivalence with the
 * reflective walker under an identity projection, a {@code @JsonProperty}-style rename, and a
 * {@code SNAKE_CASE} naming strategy.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm: the binary name of
 * {@link ProjectionGenerated} is
 * {@code ...GeneratedVsReflectiveEquivalenceTest$ProjectionGenerated}; flattening {@code '$' → '_'}
 * and appending {@code _InputProcessor} yields this class's FQN.
 */
public final class GeneratedVsReflectiveEquivalenceTest_ProjectionGenerated_InputProcessor
        implements GeneratedInputProcessor<ProjectionGenerated> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> USER_NAME_CANON = List.of(TestTrim.class);
    private static final List<Class<? extends Sanitizer>> USER_NAME_SANIT = List.of();
    private static final List<Class<? extends Canonicalizer>> HOME_PAGE_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> HOME_PAGE_SANIT = List.of(TestStripDots.class);

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public GeneratedVsReflectiveEquivalenceTest_ProjectionGenerated_InputProcessor() {}

    @Override
    public Class<ProjectionGenerated> targetType() {
        return ProjectionGenerated.class;
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
            switch (rootCtx.logicalFieldName(ProjectionGenerated.class, k)) {
                case "userName" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    USER_NAME_CANON,
                                    USER_NAME_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "userName",
                                    ProjectionGenerated.class));
                case "homePage" ->
                    out.put(
                            k,
                            applyString(
                                    v,
                                    rootCtx,
                                    OBJ_CANON,
                                    OBJ_SANIT,
                                    OBJ_SKIP_CANON,
                                    OBJ_SKIP_SANIT,
                                    HOME_PAGE_CANON,
                                    HOME_PAGE_SANIT,
                                    false,
                                    false,
                                    resolver,
                                    location,
                                    childPath,
                                    "homePage",
                                    ProjectionGenerated.class));
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
                                    ProjectionGenerated.class,
                                    ProjectionGenerated.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
