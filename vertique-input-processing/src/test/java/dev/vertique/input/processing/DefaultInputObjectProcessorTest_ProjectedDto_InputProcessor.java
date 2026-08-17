// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static dev.vertique.input.processing.GeneratedSupport.applyString;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.DefaultInputObjectProcessorTest.ProjectedDto;
import dev.vertique.input.processing.DefaultInputObjectProcessorTest.TestPrefixSanitizer;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link ProjectedDto}, mirroring the
 * shape {@code vertique-codegen-sanitization} emits once the switch is keyed on the projected
 * logical name: {@code switch (ctx.logicalFieldName(Dto.class, wireKey))} while the emitted map
 * keeps the wire key.
 *
 * <p>The {@code parent == null} fallback deliberately re-seeds with
 * {@link InputFieldNameResolver#IDENTITY}, exactly as generated code must — a generated processor
 * has no other resolver to reach for. That makes this fixture sensitive to the caller: a top-level
 * dispatch site that passes {@code null} instead of the real traversal context reinstates identity
 * naming, the {@code "userName"} arm stops matching a {@code "user_name"} wire key, and the
 * declared {@code @Sanitize} silently does not run.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm: the binary name of
 * {@link ProjectedDto} is {@code ...DefaultInputObjectProcessorTest$ProjectedDto}; flattening
 * {@code '$' → '_'} and appending {@code _InputProcessor} yields this class's FQN.
 */
public final class DefaultInputObjectProcessorTest_ProjectedDto_InputProcessor
        implements GeneratedInputProcessor<ProjectedDto> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> USER_NAME_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> USER_NAME_SANIT = List.of(TestPrefixSanitizer.class);

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public DefaultInputObjectProcessorTest_ProjectedDto_InputProcessor() {}

    @Override
    public Class<ProjectedDto> targetType() {
        return ProjectedDto.class;
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
            switch (rootCtx.logicalFieldName(ProjectedDto.class, k)) {
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
                                    ProjectedDto.class));
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
                                    ProjectedDto.class,
                                    ProjectedDto.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
