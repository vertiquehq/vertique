// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.core.validation.CharacterPolicyBinding;
import dev.vertique.validation.constraints.AllowedCharacters;
import dev.vertique.validation.constraints.CharacterPolicyResolver;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.hibernate.validator.constraints.CodePointLength;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.Range;
import org.hibernate.validator.constraints.URL;
import org.hibernate.validator.constraints.UniqueElements;

/**
 * Dagger module that bootstraps Jakarta Bean Validation with Dagger DI support.
 *
 * <p>Include this module in your Dagger component to enable validation:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, RestModule.class, ValidationModule.class, ...})
 * public interface AppComponent { ... }
 * }</pre>
 *
 * <p>Extension points:
 * <ul>
 *   <li>{@code Set<ConstraintValidator<?,?>>} — individual validators with Dagger-injected dependencies</li>
 *   <li>{@code Set<ConstraintValidatorFactory>} — additional factories for library validators</li>
 *   <li>{@code Set<ViolationTypeMapping>} — simple annotation to type string mappings</li>
 *   <li>{@code Set<ViolationTypeMapper>} — programmatic type mapping for complex cases</li>
 *   <li>{@code Set<ViolationArgsInspector>} — custom constraint argument extraction</li>
 * </ul>
 */
@Module
public abstract class ValidationModule {

    // --- Multibinding declarations ---

    /**
     * Declares the multibinding set for individual Dagger-managed constraint validators.
     *
     * @return an empty set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<ConstraintValidator<?, ?>> constraintValidators();

    /**
     * Declares the multibinding set for contributed constraint validator factories.
     *
     * @return an empty set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<ConstraintValidatorFactory> constraintValidatorFactories();

    /**
     * Declares the multibinding set for simple annotation to type mappings.
     *
     * @return an empty set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<ViolationTypeMapping> violationTypeMappings();

    /**
     * Declares the multibinding set for programmatic type mappers.
     *
     * @return an empty set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<ViolationTypeMapper> violationTypeMappers();

    /**
     * Declares the multibinding set for custom args inspectors.
     *
     * @return an empty set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<ViolationArgsInspector> violationArgsInspectors();

    /**
     * Declares the multibinding set for Dagger-managed {@link CharacterPolicyBinding} instances.
     *
     * <p>Contribute a policy via:
     * <pre>{@code
     * @Provides @IntoSet
     * static CharacterPolicyBinding myPolicy(MyPolicy p) {
     *     return new CharacterPolicyBinding(MyPolicy.class, p);
     * }
     * }</pre>
     *
     * @return an empty set (populated by {@code @IntoSet} contributions)
     */
    @Multibinds
    abstract Set<CharacterPolicyBinding> characterPolicyBindings();

    // --- Built-in type mappings ---

    /**
     * Provides built-in violation type mappings for standard Jakarta and Hibernate Validator constraints.
     *
     * @return the set of built-in type mappings
     */
    @Provides
    @ElementsIntoSet
    static Set<ViolationTypeMapping> builtInTypeMappings() {
        return Set.of(
                // Jakarta constraints
                new ViolationTypeMapping(NotNull.class, "required"),
                new ViolationTypeMapping(NotBlank.class, "required"),
                new ViolationTypeMapping(NotEmpty.class, "required"),
                new ViolationTypeMapping(Null.class, "null"),
                new ViolationTypeMapping(Size.class, "size"),
                new ViolationTypeMapping(DecimalMin.class, "min"),
                new ViolationTypeMapping(DecimalMax.class, "max"),
                new ViolationTypeMapping(Min.class, "min"),
                new ViolationTypeMapping(Max.class, "max"),
                new ViolationTypeMapping(Positive.class, "min"),
                new ViolationTypeMapping(PositiveOrZero.class, "min"),
                new ViolationTypeMapping(Negative.class, "max"),
                new ViolationTypeMapping(NegativeOrZero.class, "max"),
                new ViolationTypeMapping(Future.class, "date"),
                new ViolationTypeMapping(FutureOrPresent.class, "date"),
                new ViolationTypeMapping(Past.class, "date"),
                new ViolationTypeMapping(PastOrPresent.class, "date"),
                new ViolationTypeMapping(Pattern.class, "pattern"),
                new ViolationTypeMapping(Email.class, "email"),
                new ViolationTypeMapping(Digits.class, "digits"),
                new ViolationTypeMapping(AssertTrue.class, "assert_true"),
                new ViolationTypeMapping(AssertFalse.class, "assert_false"),
                // Hibernate constraints
                new ViolationTypeMapping(Length.class, "length"),
                new ViolationTypeMapping(CodePointLength.class, "length"),
                new ViolationTypeMapping(Range.class, "range"),
                new ViolationTypeMapping(UniqueElements.class, "unique_elements"),
                new ViolationTypeMapping(URL.class, "url"),
                // Character policy constraints
                new ViolationTypeMapping(AllowedCharacters.class, "allowed_characters"));
    }

    // --- Providers ---

    /**
     * Provides the Jakarta {@link ValidatorFactory} configured with the Dagger-aware
     * {@link DaggerConstraintValidatorFactory}.
     *
     * @param cvFactory the Dagger-aware constraint validator factory
     * @return a fully configured validator factory
     */
    @Provides
    @Singleton
    static ValidatorFactory validatorFactory(DaggerConstraintValidatorFactory cvFactory) {
        return Validation.byDefaultProvider()
                .configure()
                .constraintValidatorFactory(cvFactory)
                .buildValidatorFactory();
    }

    /**
     * Provides the Jakarta {@link Validator} from the factory.
     *
     * @param factory the validator factory
     * @return a thread-safe validator instance
     */
    @Provides
    @Singleton
    static Validator validator(ValidatorFactory factory) {
        return factory.getValidator();
    }

    /**
     * Provides the default {@link BeanValidator} implementation.
     *
     * @param defaultValidator the default bean validator
     * @return the bean validator
     */
    @Provides
    @Singleton
    static BeanValidator beanValidator(DefaultBeanValidator defaultValidator) {
        return defaultValidator;
    }

    /**
     * Provides the {@link CharacterPolicyResolver} backed by the Dagger-managed
     * {@link CharacterPolicyBinding} multibinding set.
     *
     * @param bindings the set of Dagger-managed character policy bindings
     * @return a singleton {@link CharacterPolicyResolver}
     */
    @Provides
    @Singleton
    static CharacterPolicyResolver characterPolicyResolver(Set<CharacterPolicyBinding> bindings) {
        return new CharacterPolicyResolver(bindings);
    }

    /**
     * Contributes the built-in {@link AllowedCharactersArgsInspector} to the args inspector set.
     *
     * <p>This inspector extracts the policy class name from {@link AllowedCharacters} violations,
     * making the policy name available in structured validation error responses.
     *
     * @return the args inspector for {@link AllowedCharacters} violations
     */
    @Provides
    @IntoSet
    static ViolationArgsInspector allowedCharactersArgsInspector() {
        return new AllowedCharactersArgsInspector();
    }

    /**
     * Contributes the {@link AllowedCharactersValidatorFactory} to the
     * {@code Set<ConstraintValidatorFactory>} multibinding.
     *
     * <p>This factory ensures that {@link dev.vertique.validation.constraints.AllowedCharactersValidator}
     * and {@link dev.vertique.validation.constraints.AllowedCharactersObjectValidator} instances
     * receive a {@link dev.vertique.validation.constraints.CharacterPolicyResolver}, enabling
     * validation of {@link dev.vertique.core.validation.CharacterPolicy} implementations that
     * require Dagger-injected dependencies (i.e., have no public no-arg constructor).
     *
     * @param resolver the character policy resolver backed by the Dagger multibinding
     * @return the factory contributed to the tier-2 resolution chain
     */
    @Provides
    @IntoSet
    static ConstraintValidatorFactory allowedCharactersValidatorFactory(CharacterPolicyResolver resolver) {
        return new AllowedCharactersValidatorFactory(resolver);
    }
}
