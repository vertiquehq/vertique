// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.inventory;

import dev.vertique.core.validation.BeanValidator;
import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.ViolationDetail;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Test {@link BeanValidator} that reports no violations for any object or method call.
 *
 * <p>The inventory proofs only need a validator to be <em>bound</em>: the operation inventory
 * classifies requiredness from whether a validator exists and from the declared constraints, and no
 * request is ever sent, so no method here is expected to run.
 */
public final class NoViolationsBeanValidator implements BeanValidator {

    @Override
    public <T> void validate(T object) {
        // no violations
    }

    @Override
    public <T> void validate(T object, Class<?>... groups) {
        // no violations
    }

    @Override
    public <T> List<ViolationDetail> check(T object) {
        return List.of();
    }

    @Override
    public <T> List<ViolationDetail> check(T object, Class<?>... groups) {
        return List.of();
    }

    @Override
    public List<ParameterViolation> checkParameters(Object instance, Method method, Object[] args, Class<?>... groups) {
        return List.of();
    }

    @Override
    public void validateParameters(Object instance, Method method, Object[] args, Class<?>... groups) {
        // no violations
    }
}
