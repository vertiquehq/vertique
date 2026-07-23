// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable model of a single parameter in a workflow contract operation method.
 *
 * <p>Captures the APT element, the simple parameter name, the declared type, and the
 * {@link ParamRole} assigned by {@link ContractScanner}.
 *
 * @param element the {@link VariableElement} representing the parameter in the APT model
 * @param name    the simple parameter name (from {@code VariableElement.getSimpleName()})
 * @param type    the declared {@link TypeMirror} of the parameter
 * @param role    the {@link ParamRole} assigned to this parameter by the scanner
 */
public record ParamRoleModel(VariableElement element, String name, TypeMirror type, ParamRole role) {}
