// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

/**
 * Test-only module supplying a real Hibernate Validator, bootstrapped the same way {@code
 * SchemaCorpusMetadataCrossCheckTest} and {@code vertique-json-schema}'s {@code
 * MetadataTestValidators} do (with {@link ParameterMessageInterpolator}, matching the design's no-EL
 * finding).
 *
 * <p>Included only by {@link ValidatorBackedValidationMountComponent} — never {@link
 * ValidationMountComponent}, the default graph every other integration test in this module builds
 * from, where {@link RestValidationModule}'s {@code @BindsOptionalOf Validator} stays absent and
 * {@link AnnotationSchemaSource} generates through the annotation walk alone. Two separate {@code
 * @Component} graphs, not a contributable seam on {@link dev.vertique.rest.test.RestTestContributions},
 * because whether the optional {@link Validator} binding resolves present is decided by which modules
 * a Dagger component includes, fixed at compile time — never per {@code Factory.create()} call.
 */
@Module
final class RestTestValidatorModule {

    private RestTestValidatorModule() {}

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
