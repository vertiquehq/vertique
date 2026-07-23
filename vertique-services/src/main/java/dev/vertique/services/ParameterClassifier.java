// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.security.SecurityContext;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Classifies method parameters by their source for event bus dispatch.
 *
 * <p>Both contract method parameters and handler method parameters are classified as
 * {@link ParamSource#PAYLOAD} or {@link ParamSource#DISPATCH_CONTEXT}. Handler method parameters
 * additionally support {@link dev.vertique.core.eventbus.DispatchContextValue}-annotated types.
 *
 * <p>{@link SecurityContext} parameters on contract methods are classified as
 * {@link ParamSource#DISPATCH_CONTEXT} with {@link #SC_KEY} as the lookup key. A migration warning
 * is logged, since the preferred approach is the {@code ServiceHandler<C>} pattern.
 */
@Slf4j
final class ParameterClassifier {

    /**
     * The dispatch context map key under which the {@link SecurityContext} is stored.
     * Delegates to SecurityContext.class.getName() to ensure a single source of truth.
     */
    static final String SC_KEY = SecurityContext.class.getName();

    private ParameterClassifier() {}

    // --- Internal Classification ---

    /**
     * Internal enumeration of classification outcomes before converting to {@link ParamMeta}.
     */
    private enum ParamClassification {
        /** {@code DispatchEnvelope<?>} parameter — always a registration violation. */
        BODY_REJECTED,
        /** {@link SecurityContext} or subtype — maps to {@link ParamSource#DISPATCH_CONTEXT} with {@link #SC_KEY}. */
        SECURITY_CONTEXT,
        /** {@link dev.vertique.core.eventbus.DispatchContextValue}-annotated type — {@link ParamSource#DISPATCH_CONTEXT}. */
        DISPATCH_CONTEXT,
        /** All other types — {@link ParamSource#PAYLOAD}. */
        PAYLOAD
    }

    /**
     * Classifies a single parameter type into a {@link ParamClassification}.
     *
     * @param paramType            the parameter type to classify
     * @param allowDispatchContext {@code true} to check for
     *     {@link dev.vertique.core.eventbus.DispatchContextValue}; {@code false} for contract params
     * @return the classification result
     */
    private static ParamClassification classifyParam(Class<?> paramType, boolean allowDispatchContext) {
        if (DispatchEnvelope.class.isAssignableFrom(paramType)) {
            return ParamClassification.BODY_REJECTED;
        }
        if (SecurityContext.class.isAssignableFrom(paramType)) {
            return ParamClassification.SECURITY_CONTEXT;
        }
        if (allowDispatchContext
                && paramType.isAnnotationPresent(dev.vertique.core.eventbus.DispatchContextValue.class)) {
            return ParamClassification.DISPATCH_CONTEXT;
        }
        return ParamClassification.PAYLOAD;
    }

    // --- Public API ---

    /**
     * Classifies each method parameter as {@link ParamSource#PAYLOAD} or
     * {@link ParamSource#DISPATCH_CONTEXT}.
     *
     * <p>{@link SecurityContext} parameters are classified as {@link ParamSource#DISPATCH_CONTEXT}
     * with {@link #SC_KEY} as the lookup key, and a migration warning is logged recommending the
     * {@code ServiceHandler<C>} pattern.
     *
     * <p>Records a violation if more than one payload parameter is found, or if a {@link DispatchEnvelope}
     * parameter is declared (which is a transport wrapper reserved for framework use only).
     *
     * @param contract   the contract interface (for violation reporting)
     * @param method     the method to inspect
     * @param violations mutable list to collect violations into
     * @return list of parameter metadata in declaration order
     */
    static List<ParamMeta> classifyParams(
            Class<?> contract, Method method, List<ServiceRegistrationViolation> violations) {
        List<ParamMeta> params = new ArrayList<>();
        int payloadCount = 0;

        for (java.lang.reflect.Parameter param : method.getParameters()) {
            Class<?> paramType = param.getType();
            String paramName = param.getName();

            switch (classifyParam(paramType, false)) {
                case BODY_REJECTED ->
                    violations.add(ServiceRegistrationViolation.ofMethod(
                            contract,
                            method.getName(),
                            "DispatchEnvelope<?> parameters are not allowed on contract interfaces"
                                    + " (DispatchEnvelope is a transport wrapper created by the framework)"));
                case SECURITY_CONTEXT -> {
                    params.add(new ParamMeta(paramName, ParamSource.DISPATCH_CONTEXT, paramType, SC_KEY));
                    log.warn(
                            "Contract method {}.{}() declares SecurityContext as a parameter."
                                    + " Consider migrating to the ServiceHandler<{}> pattern"
                                    + " for cleaner contract interfaces.",
                            contract.getSimpleName(),
                            method.getName(),
                            contract.getSimpleName());
                }
                case DISPATCH_CONTEXT, PAYLOAD -> {
                    // DISPATCH_CONTEXT cannot occur when allowDispatchContext=false;
                    // both cases are treated as payload for contract methods.
                    payloadCount++;
                    if (payloadCount > 1) {
                        violations.add(ServiceRegistrationViolation.ofMethod(
                                contract,
                                method.getName(),
                                "At most one payload parameter is allowed per method, found multiple"));
                    }
                    params.add(new ParamMeta(paramName, ParamSource.PAYLOAD, paramType));
                }
            }
        }

        return params;
    }

    /**
     * Classifies parameters of a handler method.
     *
     * <p>{@link SecurityContext} or its subtypes are classified as
     * {@link ParamSource#DISPATCH_CONTEXT} with {@link #SC_KEY} as the lookup key — matching
     * the key used by {@link dev.vertique.core.eventbus.DispatchEnvelope} to store the SC.
     *
     * <p>{@link dev.vertique.core.eventbus.DispatchContextValue}-annotated types are classified as
     * {@link ParamSource#DISPATCH_CONTEXT} with {@code paramType.getName()} as the lookup key.
     *
     * <p>All other types are classified as {@link ParamSource#PAYLOAD}.
     *
     * @param contract      the contract interface (for violation reporting)
     * @param handlerMethod the handler method to classify
     * @param violations    mutable list to collect violations into
     * @return list of parameter metadata in declaration order
     */
    static List<ParamMeta> classifyHandlerParams(
            Class<?> contract, Method handlerMethod, List<ServiceRegistrationViolation> violations) {
        List<ParamMeta> params = new ArrayList<>();
        int payloadCount = 0;

        for (java.lang.reflect.Parameter param : handlerMethod.getParameters()) {
            Class<?> paramType = param.getType();
            String paramName = param.getName();

            switch (classifyParam(paramType, true)) {
                case BODY_REJECTED ->
                    violations.add(ServiceRegistrationViolation.ofMethod(
                            contract,
                            handlerMethod.getName(),
                            "DispatchEnvelope<?> parameters are not allowed on handler methods"
                                    + " (DispatchEnvelope is a transport wrapper created by the framework)"));
                case SECURITY_CONTEXT ->
                    params.add(new ParamMeta(paramName, ParamSource.DISPATCH_CONTEXT, paramType, SC_KEY));
                case DISPATCH_CONTEXT ->
                    params.add(new ParamMeta(paramName, ParamSource.DISPATCH_CONTEXT, paramType, paramType.getName()));
                case PAYLOAD -> {
                    payloadCount++;
                    if (payloadCount > 1) {
                        violations.add(ServiceRegistrationViolation.ofMethod(
                                contract,
                                handlerMethod.getName(),
                                "At most one payload parameter is allowed per handler method, found multiple"));
                    }
                    params.add(new ParamMeta(paramName, ParamSource.PAYLOAD, paramType));
                }
            }
        }

        return params;
    }
}
