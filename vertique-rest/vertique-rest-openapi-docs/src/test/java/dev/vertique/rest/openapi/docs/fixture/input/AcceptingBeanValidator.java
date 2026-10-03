// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import dev.vertique.core.validation.BeanValidator;
import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.ViolationDetail;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * A {@link BeanValidator} that accepts everything: it reports no violation and never throws. Binding
 * it makes the binding inventory treat Bean Validation as present, so a {@code @NotNull} method
 * parameter in the default group counts as required; no request in these tests depends on a real
 * check.
 */
public final class AcceptingBeanValidator implements BeanValidator {

    /** Creates the validator. */
    public AcceptingBeanValidator() {}

    @Override
    public <T> void validate(T object) {
        Objects.requireNonNull(object, "object");
    }

    @Override
    public <T> void validate(T object, Class<?>... groups) {
        Objects.requireNonNull(object, "object");
    }

    @Override
    public <T> List<ViolationDetail> check(T object) {
        Objects.requireNonNull(object, "object");
        return List.of();
    }

    @Override
    public <T> List<ViolationDetail> check(T object, Class<?>... groups) {
        Objects.requireNonNull(object, "object");
        return List.of();
    }

    @Override
    public List<ParameterViolation> checkParameters(Object instance, Method method, Object[] args, Class<?>... groups) {
        return List.of();
    }

    @Override
    public void validateParameters(Object instance, Method method, Object[] args, Class<?>... groups) {
        // Accepts every argument list.
    }
}
