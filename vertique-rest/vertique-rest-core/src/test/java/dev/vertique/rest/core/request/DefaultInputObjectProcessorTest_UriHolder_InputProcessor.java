// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.rest.core.request.DefaultInputObjectProcessorTest.TestPrefixSanitizer;
import dev.vertique.rest.core.request.DefaultInputObjectProcessorTest.UriHolder;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written companion {@link GeneratedInputProcessor} for {@link UriHolder}, mirroring the
 * {@code NESTED_DTO} switch arm that {@code vertique-codegen-sanitization} emits for a field
 * whose declared type is neither a string, a collection, nor a scalar — here {@link URI}.
 *
 * <p>{@link URI} has no generated processor, so the emitted
 * {@code dispatcher.dispatchNested(v, URI.class, …)} call reaches
 * {@code DefaultInputObjectProcessor.continueAt} with the raw wire string. This fixture exists so
 * {@link DefaultInputObjectProcessorTest} can prove that path applies the inherited chain instead
 * of returning the value untouched.
 *
 * <p>Naming follows the dispatcher's {@code generatedClassName(...)} algorithm: the binary name of
 * {@link UriHolder} is {@code ...DefaultInputObjectProcessorTest$UriHolder}; flattening
 * {@code '$' → '_'} and appending {@code _InputProcessor} yields this class's FQN.
 */
public final class DefaultInputObjectProcessorTest_UriHolder_InputProcessor
        implements GeneratedInputProcessor<UriHolder> {

    private static final List<Class<? extends Canonicalizer>> OBJ_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> OBJ_SANIT = List.of();
    private static final boolean OBJ_SKIP_CANON = false;
    private static final boolean OBJ_SKIP_SANIT = false;

    private static final List<Class<? extends Canonicalizer>> HOMEPAGE_CANON = List.of();
    private static final List<Class<? extends Sanitizer>> HOMEPAGE_SANIT = List.of(TestPrefixSanitizer.class);

    /** Public no-arg constructor for {@code Class.forName}-based instantiation by the dispatcher. */
    public DefaultInputObjectProcessorTest_UriHolder_InputProcessor() {}

    @Override
    public Class<UriHolder> targetType() {
        return UriHolder.class;
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
        InputTraversalContext rootCtx = parent != null ? parent : InputTraversalContext.fromRoute(policies);

        Map<String, Object> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (v == null) {
                out.put(k, null);
                continue;
            }
            String childPath = parentPath.isEmpty() ? k : parentPath + "." + k;
            switch (k) {
                case "homepage" -> {
                    InputTraversalContext nestedCtx = rootCtx.descend(
                            OBJ_CANON,
                            OBJ_SANIT,
                            OBJ_SKIP_CANON,
                            OBJ_SKIP_SANIT,
                            HOMEPAGE_CANON,
                            HOMEPAGE_SANIT,
                            false,
                            false);
                    out.put(
                            k,
                            dispatcher.dispatchNested(
                                    v, URI.class, policies, location, resolver, nestedCtx, childPath, URI.class));
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
                                    UriHolder.class,
                                    UriHolder.class,
                                    dispatcher));
            }
        }
        return out;
    }
}
