// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * APT-layer replica of the runtime {@code dev.vertique.services.ParameterClassifier}.
 *
 * <p>Classifies each parameter of a service contract or handler method as either
 * {@link ParamModel#SOURCE_PAYLOAD} or {@link ParamModel#SOURCE_DISPATCH_CONTEXT}.
 *
 * <p>Classification rules (mirror {@code ParameterClassifier.java:58-70,90-132,151-185}):
 * <ul>
 *   <li>{@code DispatchEnvelope<?>} parameters → error; always rejected.</li>
 *   <li>{@code SecurityContext} subtypes → {@code DISPATCH_CONTEXT} with lookup key
 *       {@code SecurityContext.class.getName()} ({@code "sc"}).</li>
 *   <li>{@code @DispatchContextValue}-annotated types (handler params only) →
 *       {@code DISPATCH_CONTEXT} with lookup key {@code paramType.getName()}.</li>
 *   <li>All other types → {@code PAYLOAD}.</li>
 * </ul>
 *
 * <p>Contract method classification does not allow {@code @DispatchContextValue} params (those
 * are handler-only). Handler method classification allows all three kinds.
 *
 * <p>The {@code DispatchEnvelope}, {@code SecurityContext}, and {@code @DispatchContextValue}
 * type elements are resolved once and cached as instance fields to avoid repeated
 * {@link javax.lang.model.util.Elements#getTypeElement} lookups per parameter across potentially
 * many contract methods. If a type is not on the processor classpath the cached reference is
 * {@code null} and the corresponding check safely returns {@code false}.
 */
final class AptParamClassifier {

    /**
     * The dispatch-context map key used to look up {@link SecurityContext}. Mirrors the runtime
     * convention {@code SecurityContext.class.getName()}, kept as a string constant here so the
     * annotation processor does not need to depend on the runtime {@code vertique-core}
     * classpath to resolve the FQCN.
     */
    static final String SC_KEY = ServiceAnnotations.SECURITY_CONTEXT;

    private final CodegenContext ctx;

    // --- Cached type mirrors (resolved once; null when not on classpath) ---

    /** Erased {@code TypeMirror} for {@code DispatchEnvelope<?>}, or {@code null} if not on classpath. */
    private TypeMirror envelopeErasure;

    /** {@code true} once {@link #envelopeErasure} has been resolved (even if it resolved to null). */
    private boolean envelopeResolved;

    /** Erased {@code TypeMirror} for {@code SecurityContext}, or {@code null} if not on classpath. */
    private TypeMirror scErasure;

    /** {@code true} once {@link #scErasure} has been resolved. */
    private boolean scResolved;

    /**
     * Constructs the classifier bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    AptParamClassifier(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Lazy-initialised cached type erasures ---

    /**
     * Returns the erased {@code TypeMirror} for {@code DispatchEnvelope<?>}, resolving it on first call.
     *
     * @return erased type or {@code null} if {@code DispatchEnvelope} is not on the processor classpath
     */
    private TypeMirror envelopeErasure() {
        if (!envelopeResolved) {
            var el = ctx.elements().getTypeElement(ServiceAnnotations.DISPATCH_ENVELOPE);
            envelopeErasure = (el != null) ? ctx.types().erasure(el.asType()) : null;
            envelopeResolved = true;
        }
        return envelopeErasure;
    }

    /**
     * Returns the erased {@code TypeMirror} for {@code SecurityContext}, resolving it on first call.
     *
     * @return erased type or {@code null} if {@code SecurityContext} is not on the processor classpath
     */
    private TypeMirror scErasure() {
        if (!scResolved) {
            var el = ctx.elements().getTypeElement(ServiceAnnotations.SECURITY_CONTEXT);
            scErasure = (el != null) ? ctx.types().erasure(el.asType()) : null;
            scResolved = true;
        }
        return scErasure;
    }

    /**
     * Classifies parameters of a contract interface method.
     *
     * <p>{@code DispatchEnvelope<?>} params produce an error diagnostic. {@code SecurityContext} subtypes
     * are classified as {@code DISPATCH_CONTEXT}. All others are {@code PAYLOAD}.
     * {@code @DispatchContextValue}-annotated params are treated as {@code PAYLOAD} on contract
     * methods (mirrors runtime behaviour: {@code classifyParam(paramType, false)}).
     *
     * @param method    the contract method to classify; must not be {@code null}
     * @param errorSink a mutable one-element {@code boolean[]} used as an out-parameter;
     *                  {@code errorSink[0]} is set to {@code true} when an error is emitted
     * @return ordered list of {@link ParamModel} records; never {@code null}
     */
    List<ParamModel> classifyContractParams(ExecutableElement method, boolean[] errorSink) {
        List<ParamModel> result = new ArrayList<>();
        int payloadCount = 0;

        for (VariableElement param : method.getParameters()) {
            TypeMirror paramType = param.asType();
            String name = param.getSimpleName().toString();

            if (isDispatchEnvelopeType(paramType)) {
                ctx.diagnostics()
                        .error(
                                method,
                                "DispatchEnvelope<?> parameters are not allowed on contract interface methods"
                                        + " (DispatchEnvelope is a transport wrapper created by the framework) on %s.%s()",
                                method.getEnclosingElement().getSimpleName(),
                                method.getSimpleName());
                errorSink[0] = true;
                continue;
            }

            if (isSecurityContextType(paramType)) {
                result.add(ParamModel.dispatchContext(name, paramType, SC_KEY));
                continue;
            }

            // Everything else is PAYLOAD on contract methods
            payloadCount++;
            if (payloadCount > 1) {
                ctx.diagnostics()
                        .error(
                                method,
                                "At most one payload parameter is allowed per method on %s.%s(), found multiple",
                                method.getEnclosingElement().getSimpleName(),
                                method.getSimpleName());
                errorSink[0] = true;
            }
            result.add(ParamModel.payload(name, paramType));
        }

        return result;
    }

    /**
     * Classifies parameters of a handler class method.
     *
     * <p>Supports all three classifications: {@code DispatchEnvelope<?>} is an error; {@code SecurityContext}
     * subtypes → {@code DISPATCH_CONTEXT} with key {@code SC_KEY}; {@code @DispatchContextValue}
     * types → {@code DISPATCH_CONTEXT} with key {@code paramType.toString()}; all others →
     * {@code PAYLOAD}.
     *
     * <p>Unlike {@link #classifyContractParams}, this method does <em>not</em> enforce the
     * "at most one payload" rule. Extra {@code PAYLOAD} params beyond the contract's payload count
     * are classified without error; {@link dev.vertique.codegen.services.processor.validate.HandlerMatchValidator}
     * is responsible for detecting and reporting them with a targeted diagnostic that names the
     * offending param and suggests the two valid dispatch-context kinds.
     *
     * @param method    the handler method to classify; must not be {@code null}
     * @param errorSink a mutable one-element {@code boolean[]} used as an out-parameter;
     *                  {@code errorSink[0]} is set to {@code true} when an error is emitted
     * @return ordered list of {@link ParamModel} records; never {@code null}
     */
    List<ParamModel> classifyHandlerParams(ExecutableElement method, boolean[] errorSink) {
        List<ParamModel> result = new ArrayList<>();

        for (VariableElement param : method.getParameters()) {
            TypeMirror paramType = param.asType();
            String name = param.getSimpleName().toString();

            if (isDispatchEnvelopeType(paramType)) {
                ctx.diagnostics()
                        .error(
                                method,
                                "DispatchEnvelope<?> parameters are not allowed on handler methods"
                                        + " (DispatchEnvelope is a transport wrapper created by the framework) on %s.%s()",
                                method.getEnclosingElement().getSimpleName(),
                                method.getSimpleName());
                errorSink[0] = true;
                continue;
            }

            if (isSecurityContextType(paramType)) {
                result.add(ParamModel.dispatchContext(name, paramType, SC_KEY));
                continue;
            }

            if (hasDispatchContextValueAnnotation(param)) {
                // lookup key = type FQN, mirrors ParameterClassifier.java:170
                result.add(ParamModel.dispatchContext(name, paramType, paramType.toString()));
                continue;
            }

            // Everything else is PAYLOAD; the max-one-payload rule is enforced by
            // HandlerMatchValidator (not here) so that it can emit a targeted diagnostic
            // naming the offending param and suggesting @DispatchContextValue or SecurityContext.
            result.add(ParamModel.payload(name, paramType));
        }

        return result;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} if the type is assignable to {@code DispatchEnvelope<?>}.
     *
     * <p>Uses only the APT type universe (via {@link javax.lang.model.util.Elements#getTypeElement})
     * to avoid the {@code loadClass} fallback to {@code Object.class} which would make every type
     * appear to be a {@code DispatchEnvelope} subtype when the class is not on the processor
     * classpath. The {@code DispatchEnvelope} type element is resolved and cached on first call.
     *
     * @param type the type mirror to check
     * @return {@code true} if this is a {@code DispatchEnvelope} type
     */
    private boolean isDispatchEnvelopeType(TypeMirror type) {
        TypeMirror cached = envelopeErasure();
        if (cached == null) {
            return false;
        }
        var typeErasure = ctx.types().erasure(type);
        return ctx.types().isAssignable(typeErasure, cached);
    }

    /**
     * Returns {@code true} if the type is assignable to {@code SecurityContext}.
     *
     * <p>The {@code SecurityContext} type element is resolved and cached on first call.
     *
     * @param type the type mirror to check
     * @return {@code true} if this is a {@code SecurityContext} subtype
     */
    private boolean isSecurityContextType(TypeMirror type) {
        TypeMirror cached = scErasure();
        if (cached == null) {
            return false;
        }
        var typeErasure = ctx.types().erasure(type);
        return ctx.types().isAssignable(typeErasure, cached);
    }

    /**
     * Returns {@code true} if the parameter's <em>type</em> carries {@code @DispatchContextValue}.
     *
     * <p>{@code @DispatchContextValue} is a {@code @Target(TYPE)} annotation — it annotates the
     * parameter type declaration, not the parameter element itself. Checking
     * {@code AnnotationMirrors.isPresent(param, ...)} would always return {@code false} because the
     * annotation lives on the type, not on the usage site. The correct check resolves the parameter's
     * type to a {@link javax.lang.model.element.TypeElement} and inspects the annotation there.
     *
     * @param param the variable element whose parameter type to check; must not be {@code null}
     * @return {@code true} if the parameter's type carries {@code @DispatchContextValue}
     */
    private boolean hasDispatchContextValueAnnotation(VariableElement param) {
        return ctx.asTypeElement(param.asType())
                .map(te -> AnnotationMirrors.isPresent(te, ServiceAnnotations.DISPATCH_CONTEXT_VALUE))
                .orElse(false);
    }
}
