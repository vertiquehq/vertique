// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor;

import com.palantir.javapoet.ClassName;
import javax.lang.model.element.TypeElement;

/**
 * Immutable description of one generic Dagger registration declaration.
 *
 * @param origin         the annotated implementation type
 * @param target         the type exposed by the generated binding
 * @param implementation the implementation type injected into the generated binding method
 * @param intoSet        whether the binding contributes to a set
 */
public record Registration(TypeElement origin, TypeElement target, ClassName implementation, boolean intoSet) {}
