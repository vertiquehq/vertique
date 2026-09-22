// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import jakarta.validation.Validator;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RestValidationModule} contributes the {@code web-validation}
 * {@link RequestValidationStrategy} into the strategy multibinding and binds an
 * {@link OperationSchemaSource}.
 *
 * <p>A full Dagger test component is heavy for this assertion, so the test instead (1) reflects on the
 * module's {@code @Binds}/{@code @Provides} methods to confirm an {@code @IntoSet} contribution returns
 * {@link RequestValidationStrategy} and a binding returns {@link OperationSchemaSource}, and (2)
 * constructs {@link WebValidationStrategy} directly to confirm its {@code id()} is {@code "web-validation"}.
 */
class RestValidationModuleBindingsTest {

    @Test
    @DisplayName("RestValidationModule declares an @IntoSet RequestValidationStrategy binding")
    void restValidationModuleBindsWebValidationStrategyIntoSet() {
        boolean hasStrategyIntoSet = Arrays.stream(RestValidationModule.class.getDeclaredMethods())
                .anyMatch(m -> isBindingMethod(m)
                        && m.isAnnotationPresent(IntoSet.class)
                        && m.getReturnType().equals(RequestValidationStrategy.class));

        assertTrue(
                hasStrategyIntoSet,
                "RestValidationModule must declare an @IntoSet binding returning RequestValidationStrategy");
    }

    @Test
    @DisplayName("RestValidationModule declares an OperationSchemaSource binding")
    void restValidationModuleBindsOperationSchemaSource() {
        boolean hasSchemaSource = Arrays.stream(RestValidationModule.class.getDeclaredMethods())
                .anyMatch(m -> isBindingMethod(m) && m.getReturnType().equals(OperationSchemaSource.class));

        assertTrue(
                hasSchemaSource,
                "RestValidationModule must bind OperationSchemaSource (the AnnotationSchemaSource producer)");
    }

    @Test
    @DisplayName("WebValidationStrategy id is \"web-validation\"")
    void webValidationStrategyHasExpectedId() {
        assertEquals(
                "web-validation",
                new WebValidationStrategy(JaxRsConfig.builder().build()).id(),
                "the strategy contributed by RestValidationModule must select id \"web-validation\"");
    }

    @Test
    @DisplayName("RestValidationModule declares @BindsOptionalOf Validator")
    void restValidationModuleDeclaresOptionalValidator() {
        boolean hasOptionalValidator = Arrays.stream(RestValidationModule.class.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(BindsOptionalOf.class)
                        && m.getReturnType().equals(Validator.class));

        assertTrue(
                hasOptionalValidator,
                "RestValidationModule must declare @BindsOptionalOf Validator, so an application without"
                        + " vertique-validation composes with Optional.empty() and one that binds a Validator"
                        + " (directly, or via vertique-validation's ValidationModule) is picked up automatically");
    }

    @Test
    @DisplayName(
            "AnnotationSchemaSource composes with an empty Optional<Validator> (no vertique-validation on the graph)")
    void annotationSchemaSourceComposesWithoutValidator() {
        AnnotationSchemaSource source = new AnnotationSchemaSource(Optional.empty());
        assertNotNull(source, "the source must construct with no validator present");
    }

    private static boolean isBindingMethod(Method method) {
        return method.isAnnotationPresent(Binds.class) || method.isAnnotationPresent(Provides.class);
    }
}
