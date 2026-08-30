// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers an injectable implementation as a Dagger binding for the specified type.
 *
 * <p>{@code AutoWireProcessor} consumes this source-retained annotation and emits an abstract
 * {@code @Binds} method in {@code GeneratedRegistrationsModule}. The annotated type must be a
 * concrete type with exactly one {@code jakarta.inject.Inject} or {@code javax.inject.Inject}
 * constructor and must be assignable to {@link #value()}.
 *
 * <p>Multiple declarations may be placed on one implementation. Each declaration produces one
 * direct binding, and {@link NoAutoWire} suppresses all declarations on the type.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(RegisterAsContainer.class)
@Documented
public @interface RegisterAs {

    /**
     * The type under which the annotated implementation is exposed to Dagger.
     *
     * @return the binding target type
     */
    Class<?> value();
}
