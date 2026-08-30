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
 * Contributes an injectable implementation to a Dagger set for the specified element type.
 *
 * <p>{@code AutoWireProcessor} consumes this source-retained annotation and emits an abstract
 * {@code @Binds @IntoSet} method in {@code GeneratedRegistrationsModule}. The annotated type must
 * be a concrete type with exactly one {@code jakarta.inject.Inject} or {@code javax.inject.Inject}
 * constructor and must be assignable to {@link #value()}.
 *
 * <p>Multiple declarations may be placed on one implementation. Each declaration produces one
 * set contribution, and {@link NoAutoWire} suppresses all declarations on the type.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(RegisterIntoSetContainer.class)
@Documented
public @interface RegisterIntoSet {

    /**
     * The set element type to which the annotated implementation contributes.
     *
     * @return the set element target type
     */
    Class<?> value();
}
