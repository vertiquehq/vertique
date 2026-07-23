// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies the validation groups to apply when validating method parameters.
 *
 * <p>Place on JAX-RS resource methods or event bus service methods to control which
 * validation groups are active for that operation. When absent, the default validation
 * group is used.
 *
 * <p>Example usage:
 * <pre>{@code
 * @POST @Path("/users")
 * @ValidateWith({Create.class})
 * public Future<User> createUser(@Valid CreateUserRequest request) { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidateWith {

    /**
     * The validation groups to apply. An empty array means the default validation group.
     *
     * @return the validation group classes
     */
    Class<?>[] value();
}
