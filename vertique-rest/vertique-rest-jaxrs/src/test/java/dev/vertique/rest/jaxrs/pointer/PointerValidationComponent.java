// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.pointer;

import dagger.Component;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.validation.ValidationModule;
import jakarta.inject.Singleton;

/**
 * Test Dagger component installing {@link ValidationModule}, so the body pointer proofs run the real
 * Hibernate Validator-backed {@link BeanValidator} (with its structured path capture) instead of a
 * stub.
 */
@Singleton
@Component(modules = ValidationModule.class)
public interface PointerValidationComponent {

    /**
     * Returns the real bean validator.
     *
     * @return the bean validator
     */
    BeanValidator beanValidator();
}
