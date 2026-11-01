// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.kafka.processor.KafkaCodegenAnnotations;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import java.util.Optional;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Validates that a {@link dev.vertique.kafka.KafkaListener @KafkaListener} type is either a
 * Model 3 router or a valid Model 4 direct handler (FR-CG006-005, Slice 6).
 *
 * <p>A {@code @KafkaListener} type that is <em>neither</em> is rejected at compile time:
 * <ul>
 *   <li>A {@code @KafkaListener} interface with no {@code @KafkaHandler} methods has no routes and
 *       cannot implement {@code KafkaRecordHandler<V>} — it is neither a router nor a direct
 *       handler.</li>
 *   <li>A {@code @KafkaListener} class that does not implement {@code KafkaRecordHandler} (not even
 *       as a raw type) has no handler contract — it is neither a router nor a direct handler.</li>
 *   <li>A {@code @KafkaListener} class that implements the raw {@code KafkaRecordHandler} (no type
 *       argument) is rejected: the value type {@code V} is unresolvable, so the emitter cannot
 *       produce a valid {@code .class} literal. The reflective path also errors loudly in this
 *       case.</li>
 *   <li>A {@code @KafkaListener} class whose {@code valueType()} is not {@code Void.class} and is
 *       not assignable from the resolved {@code V} is rejected: the reflective
 *       {@code KafkaConsumerScanner.processHandlerInstance} applies
 *       {@code annotatedValueType.isAssignableFrom(resolvedV)} and rejects the same mismatch.</li>
 * </ul>
 *
 * <p>This validator is intentionally a no-op for:
 * <ul>
 *   <li>Valid routers — {@link ListenerModel.Kind#ROUTER} with at least one route</li>
 *   <li>Valid direct handlers — {@link ListenerModel.Kind#HANDLER} whose class is assignable to
 *       {@code KafkaRecordHandler} with a resolvable type argument {@code V} and whose
 *       {@code valueType()} is either {@code Void.class} (no restriction) or is assignable from
 *       {@code V}</li>
 * </ul>
 */
public final class DirectHandlerValidator {

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public DirectHandlerValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns {@code true} when the listener model is a valid router or a valid direct handler;
     * emits a compiler error and returns {@code false} when it is not.
     *
     * <p>Validation rules:
     * <ul>
     *   <li>{@link ListenerModel.Kind#ROUTER} with at least one route → valid (no-op)</li>
     *   <li>{@link ListenerModel.Kind#ROUTER} with no routes → error</li>
     *   <li>{@link ListenerModel.Kind#HANDLER} whose class is not assignable to
     *       {@code KafkaRecordHandler} → error</li>
     *   <li>{@link ListenerModel.Kind#HANDLER} whose class implements raw {@code KafkaRecordHandler}
     *       (unresolvable {@code V}) → error: the emitter cannot produce a valid class literal</li>
     *   <li>{@link ListenerModel.Kind#HANDLER} with a non-{@code Void} {@code valueType()} that is
     *       not assignable from the resolved {@code V} → error: mirrors the reflective check in
     *       {@code KafkaConsumerScanner.processHandlerInstance}</li>
     *   <li>{@link ListenerModel.Kind#HANDLER} whose class implements {@code KafkaRecordHandler<V>}
     *       with a resolvable type argument and a compatible {@code valueType()} → valid</li>
     * </ul>
     *
     * @param model the scanned listener model to validate; must not be {@code null}
     * @return {@code true} when the model represents a valid consumer shape; {@code false} when a
     *         diagnostic was emitted
     */
    public boolean validate(ListenerModel model) {
        String className = model.originType().getSimpleName().toString();

        if (model.kind() == ListenerModel.Kind.ROUTER) {
            // A ROUTER model with at least one @KafkaHandler route is valid.
            if (!model.routes().isEmpty()) {
                return true;
            }
            // A ROUTER model with no routes is an interface that declares no @KafkaHandler methods.
            // Interfaces cannot implement KafkaRecordHandler, so this type is neither a router nor
            // a direct handler.
            ctx.diagnostics().error(model.originType(), Diagnostics.kafkaListenerNotRouterOrRecordHandler(className));
            return false;
        }

        // HANDLER kind — the type is a class. Check if it is assignable to KafkaRecordHandler
        // (raw type check via the APT type system, so generics are stripped).
        if (!isAssignableToRecordHandler(model.originType())) {
            // The class does not implement KafkaRecordHandler at all — it is neither a router nor a
            // direct handler.
            ctx.diagnostics().error(model.originType(), Diagnostics.kafkaListenerNotRouterOrRecordHandler(className));
            return false;
        }

        // The class implements KafkaRecordHandler. Verify that V is resolvable: a raw
        // KafkaRecordHandler (no type argument) would produce an uncompilable class literal.
        if (model.handlerValueType() == null) {
            ctx.diagnostics().error(model.originType(), Diagnostics.kafkaHandlerUnresolvedValueType(className));
            return false;
        }

        // Parity check: if @KafkaListener.valueType() is declared (not Void), verify that it is
        // assignable from the resolved V. Mirrors the reflective check:
        //   annotatedValueType.isAssignableFrom(resolvedV)
        // which in APT terms is: isAssignable(erasure(resolvedV), erasure(annotatedValueType)).
        Optional<TypeMirror> annotatedValueType = readAnnotatedValueType(model.originType());
        if (annotatedValueType.isPresent()) {
            TypeMirror annotated = annotatedValueType.get();
            TypeMirror resolved = model.handlerValueType();
            TypeMirror annotatedErasure = ctx.types().erasure(annotated);
            TypeMirror resolvedErasure = ctx.types().erasure(resolved);
            if (!isVoidType(annotatedErasure) && !ctx.types().isAssignable(resolvedErasure, annotatedErasure)) {
                String annotatedName = annotated.toString();
                String resolvedName = resolved.toString();
                ctx.diagnostics()
                        .error(
                                model.originType(),
                                Diagnostics.kafkaListenerValueTypeMismatch(className, annotatedName, resolvedName));
                return false;
            }
        }

        return true;
    }

    // --- Internal helpers ---

    /**
     * Returns {@code true} when the given type element is assignable to the erasure of
     * {@code KafkaRecordHandler}, performing a raw-type check using the APT type system.
     *
     * <p>If the {@code KafkaRecordHandler} type element is not on the processor classpath (e.g., in
     * an isolated test compilation), this method returns {@code false} conservatively.
     *
     * @param type the class element to check; must not be {@code null}
     * @return {@code true} if {@code type} implements {@code KafkaRecordHandler}
     */
    private boolean isAssignableToRecordHandler(TypeElement type) {
        TypeElement handlerElement = ctx.elements().getTypeElement(KafkaCodegenAnnotations.KAFKA_RECORD_HANDLER);
        if (handlerElement == null) {
            // KafkaRecordHandler not on the processor classpath — cannot determine assignability.
            return false;
        }
        TypeMirror subErasure = ctx.types().erasure(type.asType());
        TypeMirror superErasure = ctx.types().erasure(handlerElement.asType());
        return ctx.types().isAssignable(subErasure, superErasure);
    }

    /**
     * Reads the {@code valueType()} class attribute from the {@code @KafkaListener} annotation on
     * the given type element. Returns empty when the attribute is absent or when the annotation is
     * not present.
     *
     * @param type the annotated type element; must not be {@code null}
     * @return an {@link Optional} containing the {@link TypeMirror} for {@code valueType()}, or
     *         empty if not present
     */
    private Optional<TypeMirror> readAnnotatedValueType(TypeElement type) {
        return AnnotationMirrors.findByFqn(type, KafkaCodegenAnnotations.KAFKA_LISTENER)
                .flatMap(mirror -> ctx.annotations().attributeClass(mirror, "valueType"));
    }

    /**
     * Returns {@code true} when the given type mirror represents {@code Void} (either the boxed
     * {@code java.lang.Void} reference type or the primitive {@code void} kind).
     *
     * <p>The {@code @KafkaListener.valueType()} default is {@code Void.class}; when the attribute
     * resolves to {@code Void} the valueType restriction is absent and the check is skipped.
     *
     * @param type the type mirror to test; must not be {@code null}
     * @return {@code true} if {@code type} is {@code Void} or {@code void}
     */
    private boolean isVoidType(TypeMirror type) {
        if (type.getKind() == TypeKind.VOID) {
            return true;
        }
        // Boxed Void: the erasure of java.lang.Void
        TypeElement voidElement = ctx.elements().getTypeElement("java.lang.Void");
        if (voidElement == null) {
            return false;
        }
        return ctx.types().isSameType(type, ctx.types().erasure(voidElement.asType()));
    }
}
