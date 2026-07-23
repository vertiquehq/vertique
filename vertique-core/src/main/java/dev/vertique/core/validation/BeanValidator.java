// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Programmatic validation API that validates objects against Jakarta Bean Validation
 * constraints and produces structured {@link ViolationDetail} results.
 *
 * <p>Use this for service-layer or event-bus-handler validation where HTTP context
 * is not available:
 * <pre>{@code
 * @Inject BeanValidator validator;
 *
 * public void processOrder(OrderRequest request) {
 *     validator.validate(request); // throws BeanValidationException if invalid
 *     // ... business logic
 * }
 * }</pre>
 *
 * <p>For non-throwing validation, use {@link #check(Object)} which returns the
 * violation details list.
 *
 * <p>For method-level parameter validation (used by the REST and services layers), use
 * {@link #checkParameters} or {@link #validateParameters}.
 *
 * <p>Implementations are provided by the {@code validation} module.
 */
public interface BeanValidator {

    /**
     * Validates the given object using the default validation group.
     * Throws {@link BeanValidationException} if any constraints are violated.
     *
     * @param object the object to validate; must not be {@code null}
     * @param <T>    the object type
     * @throws BeanValidationException if one or more constraints are violated
     * @throws NullPointerException    if {@code object} is {@code null}
     */
    <T> void validate(T object);

    /**
     * Validates the given object using the specified validation groups.
     * Throws {@link BeanValidationException} if any constraints are violated.
     *
     * @param object the object to validate; must not be {@code null}
     * @param groups the validation groups to apply
     * @param <T>    the object type
     * @throws BeanValidationException if one or more constraints are violated
     * @throws NullPointerException    if {@code object} is {@code null}
     */
    <T> void validate(T object, Class<?>... groups);

    /**
     * Validates the given object without throwing. Returns the list of violation details.
     *
     * @param object the object to validate; must not be {@code null}
     * @param <T>    the object type
     * @return the list of violation details; empty if valid
     * @throws NullPointerException if {@code object} is {@code null}
     */
    <T> List<ViolationDetail> check(T object);

    /**
     * Validates the given object with specific groups without throwing.
     *
     * @param object the object to validate; must not be {@code null}
     * @param groups the validation groups to apply
     * @param <T>    the object type
     * @return the list of violation details; empty if valid
     * @throws NullPointerException if {@code object} is {@code null}
     */
    <T> List<ViolationDetail> check(T object, Class<?>... groups);

    /**
     * Validates method parameters without throwing. Returns the list of parameter violations.
     *
     * <p>Each {@link ParameterViolation} carries the zero-based parameter index alongside
     * the violation detail, enabling the caller to map violations to HTTP locations.
     *
     * @param instance the object instance whose method is being validated; must not be {@code null}
     * @param method   the method whose parameters are being validated; must not be {@code null}
     * @param args     the argument values passed to the method; must not be {@code null}
     * @param groups   the validation groups to apply; empty means the default validation group
     * @return the list of parameter violations; empty if all parameters are valid
     * @throws NullPointerException if any of {@code instance}, {@code method}, or {@code args} is {@code null}
     */
    List<ParameterViolation> checkParameters(Object instance, Method method, Object[] args, Class<?>... groups);

    /**
     * Validates method parameters. Throws {@link BeanValidationException} if any constraints are violated.
     *
     * @param instance the object instance whose method is being validated; must not be {@code null}
     * @param method   the method whose parameters are being validated; must not be {@code null}
     * @param args     the argument values passed to the method; must not be {@code null}
     * @param groups   the validation groups to apply; empty means the default validation group
     * @throws BeanValidationException if one or more parameter constraints are violated
     * @throws NullPointerException    if any of {@code instance}, {@code method}, or {@code args} is {@code null}
     */
    void validateParameters(Object instance, Method method, Object[] args, Class<?>... groups);
}
