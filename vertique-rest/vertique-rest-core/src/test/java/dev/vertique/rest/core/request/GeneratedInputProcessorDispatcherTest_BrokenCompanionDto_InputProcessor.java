// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import dev.vertique.core.sanitization.InputLocation;
import jakarta.annotation.Nullable;

/**
 * Test fixture: a broken {@link GeneratedInputProcessor} whose constructor throws. Used by
 * {@link GeneratedInputProcessorDispatcherTest} to assert that the dispatcher propagates
 * instantiation failures rather than silently masking them as reflective fallback.
 *
 * <p>The class FQN is the result of running the dispatcher's {@code generatedClassName(...)}
 * algorithm against {@code GeneratedInputProcessorDispatcherTest$BrokenCompanionDto}: the
 * package is preserved, the {@code '$'} between {@code Test} and {@code BrokenCompanionDto} is
 * flattened to {@code '_'}, and the {@code _InputProcessor} suffix is appended.
 */
public final class GeneratedInputProcessorDispatcherTest_BrokenCompanionDto_InputProcessor
        implements GeneratedInputProcessor<GeneratedInputProcessorDispatcherTest.BrokenCompanionDto> {

    /** Throws on construction so the dispatcher's instantiation-failure path is exercised. */
    public GeneratedInputProcessorDispatcherTest_BrokenCompanionDto_InputProcessor() {
        throw new IllegalStateException("intentional test failure during construction");
    }

    @Override
    public Class<GeneratedInputProcessorDispatcherTest.BrokenCompanionDto> targetType() {
        return GeneratedInputProcessorDispatcherTest.BrokenCompanionDto.class;
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
        throw new UnsupportedOperationException("unreachable — construction always fails");
    }
}
