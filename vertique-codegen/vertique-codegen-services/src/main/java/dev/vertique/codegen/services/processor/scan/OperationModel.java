// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Represents a single validated operation derived from a service contract method.
 *
 * <p>For the direct-implementation pattern, {@link #handlerMethod()} equals
 * {@link #contractMethod()} and {@link #handlerParams()} equals {@link #params()}.
 *
 * <p>For the handler pattern, {@link #handlerMethod()} is the method on the handler class
 * (which may have additional {@code DISPATCH_CONTEXT} parameters) and {@link #handlerParams()}
 * contains all handler-side parameters including the extra context ones.
 *
 * @param contractMethod   the method on the {@code @ServiceContract} interface; never {@code null}
 * @param handlerMethod    the method to invoke on the handler/impl at dispatch time; equals
 *                         {@code contractMethod} for direct-impl; never {@code null}
 * @param operationName    the resolved operation name used in the event bus address; never blank
 * @param stableOperationId the stable operation id from {@code @ServiceOperation}, or {@code null}
 *                          if the annotation is absent (not stable-target-eligible)
 * @param returnType       the unwrapped {@code T} from {@code Future<T>}; never {@code null}
 * @param payloadType      the type of the single payload parameter, or {@code null} if no payload
 * @param params           classified contract-side parameters in declaration order; never {@code null}
 * @param handlerParams    classified handler-side parameters in declaration order; equals
 *                         {@code params} for direct-impl; never {@code null}
 * @param oneWay           {@code true} if the method is annotated with {@code @OneWay}
 */
public record OperationModel(
        ExecutableElement contractMethod,
        ExecutableElement handlerMethod,
        String operationName,
        String stableOperationId,
        TypeMirror returnType,
        TypeMirror payloadType,
        List<ParamModel> params,
        List<ParamModel> handlerParams,
        boolean oneWay) {}
