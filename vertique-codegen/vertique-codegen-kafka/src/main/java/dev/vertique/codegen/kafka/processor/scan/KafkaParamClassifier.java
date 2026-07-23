// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.kafka.processor.KafkaCodegenAnnotations;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * APT-side parameter classifier for {@link dev.vertique.kafka.KafkaHandler @KafkaHandler} methods.
 *
 * <p>Classifies each parameter as either {@link KafkaParamModel#SOURCE_PAYLOAD} or
 * {@link KafkaParamModel#SOURCE_DISPATCH_CONTEXT}, mirroring the runtime
 * {@code ParameterClassifier} rules extended with {@code KafkaRecordContext} in the allow-list:
 *
 * <ul>
 *   <li>{@code SecurityContext} subtypes → {@code DISPATCH_CONTEXT} with lookup key
 *       {@code "dev.vertique.security.SecurityContext"}</li>
 *   <li>Types assignable to {@code KafkaRecordContext} → {@code DISPATCH_CONTEXT} (explicit
 *       Kafka-domain extension of the dispatch-context allow-list)</li>
 *   <li>Types whose <em>type declaration</em> carries {@code @DispatchContextValue} →
 *       {@code DISPATCH_CONTEXT} with lookup key equal to the type FQN. Note:
 *       {@code @DispatchContextValue} is {@code @Target(TYPE)}, so it is checked on the resolved
 *       {@link javax.lang.model.element.TypeElement} of the parameter type, NOT on the parameter
 *       element itself.</li>
 *   <li>All other types → {@code PAYLOAD} (at most one per handler method)</li>
 * </ul>
 *
 * <p>Type elements are resolved lazily on first use and cached to avoid repeated
 * {@link javax.lang.model.util.Elements#getTypeElement} calls across many method classifications.
 * A {@code null} cached value means the type is not on the processor classpath; the corresponding
 * check safely returns {@code false}.
 */
public final class KafkaParamClassifier {

    /** Dispatch-context lookup key for {@code SecurityContext} parameters. */
    static final String SC_KEY = KafkaCodegenAnnotations.SECURITY_CONTEXT;

    private final CodegenContext ctx;

    // --- Cached type mirrors (resolved once; null when not on classpath) ---

    /** Erased {@code TypeMirror} for {@code SecurityContext}, or {@code null} if not on classpath. */
    private TypeMirror scErasure;

    /** {@code true} once {@link #scErasure} has been resolved. */
    private boolean scResolved;

    /** Erased {@code TypeMirror} for {@code KafkaRecordContext}, or {@code null} if not on classpath. */
    private TypeMirror kafkaCtxErasure;

    /** {@code true} once {@link #kafkaCtxErasure} has been resolved. */
    private boolean kafkaCtxResolved;

    /**
     * Constructs the classifier bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public KafkaParamClassifier(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Lazy-initialised cached type erasures ---

    /**
     * Returns the erased {@code TypeMirror} for {@code SecurityContext}, resolving on first call.
     *
     * @return erased type or {@code null} if not on the processor classpath
     */
    private TypeMirror scErasure() {
        if (!scResolved) {
            var el = ctx.elements().getTypeElement(KafkaCodegenAnnotations.SECURITY_CONTEXT);
            scErasure = (el != null) ? ctx.types().erasure(el.asType()) : null;
            scResolved = true;
        }
        return scErasure;
    }

    /**
     * Returns the erased {@code TypeMirror} for {@code KafkaRecordContext}, resolving on first call.
     *
     * @return erased type or {@code null} if not on the processor classpath
     */
    private TypeMirror kafkaCtxErasure() {
        if (!kafkaCtxResolved) {
            var el = ctx.elements().getTypeElement(KafkaCodegenAnnotations.KAFKA_RECORD_CONTEXT);
            kafkaCtxErasure = (el != null) ? ctx.types().erasure(el.asType()) : null;
            kafkaCtxResolved = true;
        }
        return kafkaCtxErasure;
    }

    // --- Classification API ---

    /**
     * Classifies the parameters of a {@code @KafkaHandler} method.
     *
     * <p>The single-payload rule is not enforced here; callers should verify at most one
     * {@code PAYLOAD} parameter exists in the returned list.
     *
     * @param method the handler method to classify; must not be {@code null}
     * @return ordered list of {@link KafkaParamModel} records; never {@code null}
     */
    public List<KafkaParamModel> classifyHandlerParams(ExecutableElement method) {
        List<KafkaParamModel> result = new ArrayList<>();

        for (VariableElement param : method.getParameters()) {
            TypeMirror paramType = param.asType();
            String name = param.getSimpleName().toString();

            if (isSecurityContextType(paramType)) {
                result.add(KafkaParamModel.dispatchContext(name, paramType, SC_KEY));
                continue;
            }

            if (isKafkaRecordContextType(paramType)) {
                result.add(
                        KafkaParamModel.dispatchContext(name, paramType, KafkaCodegenAnnotations.KAFKA_RECORD_CONTEXT));
                continue;
            }

            if (hasDispatchContextValueAnnotation(param)) {
                // lookup key = type FQN, mirrors ParameterClassifier behaviour
                result.add(KafkaParamModel.dispatchContext(name, paramType, paramType.toString()));
                continue;
            }

            result.add(KafkaParamModel.payload(name, paramType));
        }

        return result;
    }

    /**
     * Resolves the payload type for a {@code @KafkaHandler} method — the single
     * {@code PAYLOAD}-classified parameter, or {@code null} when no payload parameter is present.
     *
     * @param method the handler method to inspect; must not be {@code null}
     * @return the payload {@link TypeMirror}, or {@code null} when no payload param exists
     */
    public TypeMirror resolvePayloadType(ExecutableElement method) {
        return classifyHandlerParams(method).stream()
                .filter(KafkaParamModel::isPayload)
                .map(KafkaParamModel::type)
                .findFirst()
                .orElse(null);
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} if the type is assignable to {@code SecurityContext}.
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
     * Returns {@code true} if the type is assignable to {@code KafkaRecordContext}.
     *
     * @param type the type mirror to check
     * @return {@code true} if this is a {@code KafkaRecordContext} subtype
     */
    private boolean isKafkaRecordContextType(TypeMirror type) {
        TypeMirror cached = kafkaCtxErasure();
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
     * type to a {@link TypeElement} and inspects the annotation there.
     *
     * @param param the variable element whose parameter type to check; must not be {@code null}
     * @return {@code true} if the parameter's type carries {@code @DispatchContextValue}
     */
    private boolean hasDispatchContextValueAnnotation(VariableElement param) {
        return ctx.asTypeElement(param.asType())
                .map(te -> AnnotationMirrors.isPresent(te, KafkaCodegenAnnotations.DISPATCH_CONTEXT_VALUE))
                .orElse(false);
    }
}
