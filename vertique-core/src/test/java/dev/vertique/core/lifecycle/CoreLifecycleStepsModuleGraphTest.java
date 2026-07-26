// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dagger.Component;
import dagger.Module;
import dev.vertique.core.json.JsonModule;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link CoreLifecycleStepsModule} is <em>self-contained</em>: a Dagger {@code @Component}
 * that lists it as its only module resolves the complete {@code Set<ApplicationStartupStep>}
 * multibinding without the application having to co-list any other module.
 *
 * <p>The proof is a real, compiled Dagger component ({@link CoreLifecycleGraphComponent}) rather
 * than a hand-built fixture: {@link JacksonConfigureStep} depends on
 * {@link dev.vertique.core.json.JacksonConfigurer}, which in turn requires
 * {@code Set<dev.vertique.core.json.ObjectMapperCustomizer>}. That set's empty-by-default
 * {@code @Multibinds} declaration lives in {@link JsonModule}, so if the lifecycle module did not
 * include {@code JsonModule} this test class would fail to compile with a Dagger/MissingBinding
 * error — annotation processing, not an assertion, is the red signal.
 *
 * <p>The reflection test pins the wiring contract itself, so an accidental widening or removal of
 * the {@code includes()} list is caught even if the graph happens to resolve for another reason.
 */
class CoreLifecycleStepsModuleGraphTest {

    @Test
    @DisplayName("A component whose only module is CoreLifecycleStepsModule resolves both startup steps")
    void componentWithOnlyCoreLifecycleStepsModuleResolvesStartupSteps() {
        CoreLifecycleGraphComponent component =
                DaggerCoreLifecycleStepsModuleGraphTest_CoreLifecycleGraphComponent.create();

        Set<ApplicationStartupStep> steps = component.startupSteps();

        assertEquals(2, steps.size(), "CoreLifecycleStepsModule must contribute exactly two startup steps");

        ApplicationStartupStep jacksonStep = stepOfType(steps, JacksonConfigureStep.class);
        ApplicationStartupStep composeStep = stepOfType(steps, ComposeValidationStep.class);

        assertEquals(LifecyclePhase.CONFIGURE, jacksonStep.phase(), "JacksonConfigureStep must run in CONFIGURE");
        assertEquals(LifecyclePhase.VALIDATE, composeStep.phase(), "ComposeValidationStep must run in VALIDATE");
    }

    @Test
    @DisplayName("CoreLifecycleStepsModule includes exactly JsonModule")
    void moduleIncludesExactlyJsonModule() {
        Module annotation = CoreLifecycleStepsModule.class.getAnnotation(Module.class);

        assertNotNull(annotation, "CoreLifecycleStepsModule must be annotated @Module");
        assertArrayEquals(
                new Class<?>[] {JsonModule.class},
                annotation.includes(),
                "CoreLifecycleStepsModule must include exactly JsonModule so JacksonConfigureStep's "
                        + "JacksonConfigurer/Set<ObjectMapperCustomizer> dependencies are self-contained");
    }

    // --- Helpers ---

    /**
     * Returns the single step of the given concrete type from the resolved multibinding.
     *
     * @param steps the resolved startup-step set
     * @param type the expected concrete step type
     * @return the matching step
     */
    private static ApplicationStartupStep stepOfType(Set<ApplicationStartupStep> steps, Class<?> type) {
        return steps.stream()
                .filter(type::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in " + steps));
    }

    // --- Fixtures ---

    /**
     * Minimal component whose <em>only</em> module is {@link CoreLifecycleStepsModule} — the
     * self-containment proof. Exposing {@code Set<ApplicationStartupStep>} forces Dagger to resolve
     * every transitive dependency of both contributed steps at annotation-processing time.
     */
    @Singleton
    @Component(modules = CoreLifecycleStepsModule.class)
    interface CoreLifecycleGraphComponent {

        /**
         * Resolves the framework's built-in startup steps.
         *
         * @return the merged {@code Set<ApplicationStartupStep>} multibinding
         */
        Set<ApplicationStartupStep> startupSteps();
    }
}
