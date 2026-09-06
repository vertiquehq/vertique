// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dagger.Component;
import dagger.Module;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link CoreLifecycleStepsModule} is <em>self-contained</em> after the retirement of the
 * Jackson customizer mechanism (TP-003): a Dagger {@code @Component} that lists it as its only module
 * resolves the complete {@code Set<ApplicationStartupStep>} multibinding without the application
 * having to co-list any other module.
 *
 * <p>The proof is a real, compiled Dagger component ({@link CoreLifecycleGraphComponent}) rather than
 * a hand-built fixture: if the module still needed a foreign binding, annotation processing — not an
 * assertion — would be the red signal.
 *
 * <p>The reflection test pins the wiring contract itself: the module now includes <em>nothing</em>.
 * {@code JsonModule} existed only to supply {@code JacksonConfigureStep}'s
 * {@code Set<ObjectMapperCustomizer>} dependency; with the step and the customizer set deleted, the
 * process JSON mapper is installed by {@code vertique-json}'s own {@code CONFIGURE} step, so a
 * re-appearing {@code includes()} entry here is a regression.
 */
class CoreLifecycleStepsModuleGraphTest {

    @Test
    @DisplayName("A component whose only module is CoreLifecycleStepsModule resolves the single startup step")
    void componentWithOnlyCoreLifecycleStepsModuleResolvesStartupSteps() {
        CoreLifecycleGraphComponent component =
                DaggerCoreLifecycleStepsModuleGraphTest_CoreLifecycleGraphComponent.create();

        Set<ApplicationStartupStep> steps = component.startupSteps();

        assertEquals(1, steps.size(), "CoreLifecycleStepsModule must contribute exactly one startup step");

        ApplicationStartupStep composeStep = stepOfType(steps, ComposeValidationStep.class);

        assertEquals(LifecyclePhase.VALIDATE, composeStep.phase(), "ComposeValidationStep must run in VALIDATE");
    }

    @Test
    @DisplayName("CoreLifecycleStepsModule includes no other module")
    void moduleIncludesNothing() {
        Module annotation = CoreLifecycleStepsModule.class.getAnnotation(Module.class);

        assertNotNull(annotation, "CoreLifecycleStepsModule must be annotated @Module");
        assertArrayEquals(
                new Class<?>[] {},
                annotation.includes(),
                "CoreLifecycleStepsModule must include nothing: the retired JsonModule existed only for "
                        + "JacksonConfigureStep's JacksonConfigurer/Set<ObjectMapperCustomizer> dependencies");
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
     * every transitive dependency of the contributed step at annotation-processing time.
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
