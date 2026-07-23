// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ComposeValidationStep} — the framework's {@link LifecyclePhase#VALIDATE}
 * lifecycle step that forces construction of every {@link ComposeValidator} so the
 * constructible-as-validation checks run fail-fast.
 *
 * <p>The step injects a {@link Provider Provider&lt;Set&lt;ComposeValidator&gt;&gt;} so that
 * <em>constructing</em> the step does not materialize the validators — materialization (and thus the
 * fail-fast validation) is deferred to {@link ComposeValidationStep#start() start()}, which the
 * runner drives in the VALIDATE phase (after CONFIGURE). The AC-4 mechanism tests use a real Dagger
 * component so the proof exercises the same materialization path the framework relies on: resolving
 * {@code ComposeValidationStep} yields a step whose {@code start()} resolves the
 * {@code Set<ComposeValidator>} multibinding, constructing every contributed validator — and a
 * validator whose constructor throws surfaces that failure as a failed future from {@code start()},
 * not at construction.
 */
class ComposeValidationStepTest {

    @Test
    @DisplayName("phase() is VALIDATE")
    void phaseIsValidate() {
        ComposeValidationStep step = new ComposeValidationStep(Set::of);

        assertEquals(LifecyclePhase.VALIDATE, step.phase());
    }

    @Test
    @DisplayName("start() returns a succeeded future when all validators construct cleanly")
    void startSucceeds() {
        ComposeValidationStep step = new ComposeValidationStep(Set::of);

        Future<Void> result = step.start();

        assertTrue(result.succeeded(), "start() must return a succeeded future");
    }

    @Test
    @DisplayName("AC-4: constructing the step does NOT materialize the validators")
    void constructionDoesNotMaterializeValidators() {
        // A provider whose get() would throw if invoked. Constructing the step must not call it —
        // materialization is deferred to start(). If construction materialized the set, this would
        // throw here.
        Provider<Set<ComposeValidator>> throwingProvider = () -> {
            throw new IllegalStateException("provider.get() must not be called at construction");
        };

        ComposeValidationStep step = new ComposeValidationStep(throwingProvider);

        // The step is constructed with no exception; the throwing provider has not been invoked.
        assertSame(LifecyclePhase.VALIDATE, step.phase());
    }

    @Test
    @DisplayName("AC-4: start() materializes the set and a throwing validator yields a failed future")
    void startMaterializesAndFailingValidatorYieldsFailedFuture() {
        // Resolving the step from the real component does NOT throw (no validator is built yet) —
        // it is start() that materializes Set<ComposeValidator>, constructing ThrowingComposeValidator
        // whose @Inject constructor throws. The failure must surface as a failed future from start(),
        // exactly as it would abort the VALIDATE phase at startup, with no test code calling the
        // validator directly.
        FailingValidationComponent component = DaggerComposeValidationStepTest_FailingValidationComponent.create();

        ComposeValidationStep step = component.composeValidationStep();

        Future<Void> result = step.start();

        assertTrue(result.failed(), "start() must fail when a validator's construction throws");
        assertFalse(result.succeeded(), "start() must not succeed when a validator's construction throws");
        assertSame(IllegalStateException.class, result.cause().getClass());
        assertEquals(ThrowingComposeValidator.MESSAGE, result.cause().getMessage());
    }

    @Test
    @DisplayName("A passing ComposeValidator set materializes via start() and the step succeeds")
    void passingComposeValidatorMaterializes() {
        PassingValidationComponent component = DaggerComposeValidationStepTest_PassingValidationComponent.create();

        ComposeValidationStep step = component.composeValidationStep();

        assertSame(LifecyclePhase.VALIDATE, step.phase());
        assertTrue(step.start().succeeded());
    }

    // --- Fixtures ---

    /**
     * A {@link ComposeValidator} whose {@code @Inject} constructor throws — modelling a
     * constructible-as-validation check that fails (the runtime-invariant flavor that throws
     * {@link IllegalStateException}).
     */
    @Singleton
    static final class ThrowingComposeValidator implements ComposeValidator {

        static final String MESSAGE = "compose validation failed: required binding missing";

        @Inject
        ThrowingComposeValidator() {
            throw new IllegalStateException(MESSAGE);
        }
    }

    /**
     * A {@link ComposeValidator} whose construction succeeds — modelling a satisfied
     * constructible-as-validation check.
     */
    @Singleton
    static final class PassingComposeValidator implements ComposeValidator {

        @Inject
        PassingComposeValidator() {}
    }

    /** Contributes a throwing validator into {@code Set<ComposeValidator>}. */
    @Module(includes = CoreLifecycleStepsModule.class)
    abstract static class FailingModule {

        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator throwingValidator(ThrowingComposeValidator impl) {
            return impl;
        }
    }

    /** Contributes a passing validator into {@code Set<ComposeValidator>}. */
    @Module(includes = CoreLifecycleStepsModule.class)
    abstract static class PassingModule {

        @Provides
        @Singleton
        @IntoSet
        static ComposeValidator passingValidator(PassingComposeValidator impl) {
            return impl;
        }
    }

    /** Test component that resolves the step from a graph with a throwing validator. */
    @Singleton
    @Component(modules = FailingModule.class)
    interface FailingValidationComponent {
        ComposeValidationStep composeValidationStep();
    }

    /** Test component that resolves the step from a graph with a passing validator. */
    @Singleton
    @Component(modules = PassingModule.class)
    interface PassingValidationComponent {
        ComposeValidationStep composeValidationStep();
    }
}
