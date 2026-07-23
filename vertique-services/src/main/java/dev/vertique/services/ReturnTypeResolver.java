// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Resolves and validates the unwrapped return type {@code T} from {@code Future<T>}
 * on service contract methods.
 */
final class ReturnTypeResolver {

    private ReturnTypeResolver() {}

    /**
     * Resolves the unwrapped return type T from {@code Future<T>}.
     * Records a violation if the return type is not a parameterized {@code Future<T>}.
     *
     * @param contract the contract interface (for violation reporting)
     * @param method the method to inspect
     * @param violations mutable list to collect violations into
     * @return the unwrapped type T, or {@code null} if resolution failed
     */
    static Class<?> resolve(Class<?> contract, Method method, List<ServiceRegistrationViolation> violations) {
        Type genericReturn = method.getGenericReturnType();

        if (!(genericReturn instanceof ParameterizedType pt)) {
            violations.add(ServiceRegistrationViolation.ofMethod(
                    contract, method.getName(), "Return type must be Future<T>, found: " + genericReturn));
            return null;
        }

        Type rawType = pt.getRawType();
        if (!(rawType instanceof Class<?> rawClass) || !Future.class.isAssignableFrom(rawClass)) {
            violations.add(ServiceRegistrationViolation.ofMethod(
                    contract, method.getName(), "Return type must be Future<T>, found: " + genericReturn));
            return null;
        }

        Type[] typeArgs = pt.getActualTypeArguments();
        if (typeArgs.length != 1) {
            violations.add(ServiceRegistrationViolation.ofMethod(
                    contract,
                    method.getName(),
                    "Return type Future must have exactly one type argument, found: " + genericReturn));
            return null;
        }

        Type typeArg = typeArgs[0];
        if (typeArg instanceof Class<?> argClass) {
            return argClass;
        } else if (typeArg instanceof ParameterizedType paramArg && paramArg.getRawType() instanceof Class<?> rawArg) {
            return rawArg;
        } else {
            violations.add(ServiceRegistrationViolation.ofMethod(
                    contract,
                    method.getName(),
                    "Return type Future<T> type argument must be a concrete class, found: " + typeArg));
            return null;
        }
    }
}
