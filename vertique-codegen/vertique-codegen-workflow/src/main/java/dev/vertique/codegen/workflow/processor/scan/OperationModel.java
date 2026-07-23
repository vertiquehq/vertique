// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.scan;

import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable model of a single method declared on a {@code @WorkflowContract} interface.
 *
 * <p>Produced by {@link ContractScanner} for every non-static, non-{@code Object} method on the
 * contract. The scanner is tolerant: it classifies even malformed methods (e.g. methods carrying
 * multiple operation-role annotations or missing a required payload). The validator (a later slice)
 * is responsible for inspecting this model and reporting compile-time errors.
 *
 * @param method                   the APT {@link ExecutableElement} for this method
 * @param methodName               the simple method name
 * @param declaredRoles            the operation roles whose annotation is present on this method,
 *                                 in fixed order {@code START, SIGNAL, QUERY}; size 0, 1, or more
 * @param signalName               the {@code @WorkflowSignal.value()} attribute if the signal
 *                                 annotation is present, {@code null} otherwise
 * @param isDefault                {@code true} when the method carries the {@code default} modifier
 * @param returnType               the declared return {@link TypeMirror}
 * @param params                   the ordered list of parameter models for this method
 * @param payloadIsIdempotencyKeyed {@code true} when the single PAYLOAD parameter's type is
 *                                 assignable to {@code IdempotencyKeyed} (the required start key source)
 * @param payloadIsSignalDedupKeyed {@code true} when the single PAYLOAD parameter's type is
 *                                 assignable to {@code SignalDedupKeyed} (the required signal key source)
 */
public record OperationModel(
        ExecutableElement method,
        String methodName,
        List<OperationRole> declaredRoles,
        String signalName,
        boolean isDefault,
        TypeMirror returnType,
        List<ParamRoleModel> params,
        boolean payloadIsIdempotencyKeyed,
        boolean payloadIsSignalDedupKeyed) {

    /**
     * Compact constructor that copies {@code declaredRoles} and {@code params} defensively so the
     * record is fully immutable regardless of the mutability of the supplied lists.
     *
     * @param method                    the APT {@link ExecutableElement}; must not be {@code null}
     * @param methodName                the simple method name; must not be {@code null}
     * @param declaredRoles             the mutable or immutable roles list to copy
     * @param signalName                the signal name, or {@code null}
     * @param isDefault                 whether the method has the {@code default} modifier
     * @param returnType                the declared return type; must not be {@code null}
     * @param params                    the mutable or immutable params list to copy
     * @param payloadIsIdempotencyKeyed payload assignability flag
     * @param payloadIsSignalDedupKeyed payload assignability flag
     */
    public OperationModel {
        declaredRoles = List.copyOf(declaredRoles);
        params = List.copyOf(params);
    }

    // --- Convenience accessors ---

    /**
     * Returns the single {@link OperationRole} when exactly one role annotation is present on this
     * method; returns {@link Optional#empty()} when zero or more than one role is declared.
     *
     * @return the unambiguous role, or empty when the count is not exactly one
     */
    public Optional<OperationRole> role() {
        return declaredRoles.size() == 1 ? Optional.of(declaredRoles.get(0)) : Optional.empty();
    }

    /**
     * Returns {@code true} when this method is a clean single-role operation — exactly one
     * operation-role annotation and not a {@code default} method. Role-specific validators and the
     * emitter only act on clean single-role operations; {@link ContractShapeValidator} separately
     * reports the default / conflicting-role / no-role cases.
     *
     * @return {@code true} when the method has exactly one role and is not {@code default}
     */
    public boolean isCleanSingleRoleOp() {
        return !isDefault && role().isPresent();
    }

    /**
     * Returns the first parameter with the given {@link ParamRole}, or {@link Optional#empty()} if
     * no such parameter exists.
     *
     * @param r the role to search for; must not be {@code null}
     * @return the first matching {@link ParamRoleModel}, or empty
     */
    public Optional<ParamRoleModel> firstParamWithRole(ParamRole r) {
        return params.stream().filter(p -> p.role() == r).findFirst();
    }

    /**
     * Returns the count of parameters classified as {@link ParamRole#PAYLOAD}.
     *
     * @return the number of PAYLOAD parameters; zero or more
     */
    public long payloadParamCount() {
        return params.stream().filter(p -> p.role() == ParamRole.PAYLOAD).count();
    }
}
