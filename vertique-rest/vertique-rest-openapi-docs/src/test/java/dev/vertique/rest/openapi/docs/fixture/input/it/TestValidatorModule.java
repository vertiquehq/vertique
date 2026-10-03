// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

/**
 * Binds a real Hibernate Validator as the Bean Validation {@link Validator} the canonical schema
 * source optionally consults, bootstrapped the way {@code vertique-rest-validation}'s own tests do
 * (with {@link ParameterMessageInterpolator}, so no expression language is needed).
 *
 * <p>With a {@code Validator} bound, the canonical source renders an authored {@code @Pattern}'s
 * flags into the captured pattern as an inline modifier group, for example {@code (?sx:…)} for
 * {@code DOTALL} and {@code COMMENTS}; without one, the flags are not rendered. A component lists
 * this module beside {@code InputValidationModule}, whose {@code @BindsOptionalOf Validator} it
 * satisfies.
 */
@Module
public final class TestValidatorModule {

    private TestValidatorModule() {}

    /**
     * Provides the real validator.
     *
     * @return a Hibernate Validator with no expression-language message interpolation
     */
    @Provides
    @Singleton
    static Validator validator() {
        return Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()
                .getValidator();
    }
}
