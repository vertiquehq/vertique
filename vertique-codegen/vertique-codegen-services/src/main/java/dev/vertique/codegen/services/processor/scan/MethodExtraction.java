// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.Diagnostics;
import dev.vertique.codegen.services.processor.ServiceAnnotations;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Package-private utility holding static helpers shared between {@link DirectImplExtractor} and
 * {@link HandlerImplExtractor}.
 *
 * <p>Centralising these methods eliminates the near-identical copies that previously existed in
 * both extractors.
 */
final class MethodExtraction {

    private MethodExtraction() {}

    // --- Shared helpers ---

    /**
     * Returns the non-{@code Object} public methods declared on or inherited by the given type.
     *
     * <p>Used by both extractors to enumerate contract interface methods as well as handler class
     * methods. {@code Object} methods are excluded because they are framework-irrelevant and would
     * produce bogus operation models.
     *
     * @param type the type whose public members are enumerated; must not be {@code null}
     * @param ctx  the codegen context; must not be {@code null}
     * @return list of public, non-{@code Object} method elements in declaration order
     */
    static List<ExecutableElement> publicNonObjectMethods(TypeElement type, CodegenContext ctx) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (ExecutableElement m : ElementFilter.methodsIn(ctx.elements().getAllMembers(type))) {
            if (m.getEnclosingElement() instanceof TypeElement owner) {
                if ("java.lang.Object".equals(owner.getQualifiedName().toString())) {
                    continue;
                }
            }
            if (!m.getModifiers().contains(Modifier.PUBLIC)) {
                continue;
            }
            methods.add(m);
        }
        return methods;
    }

    /**
     * Unwraps the {@code T} from {@code Future<T>} on the given method's return type.
     *
     * <p>Emits a compiler error and sets the error flag when:
     * <ul>
     *   <li>{@code Future} is not on the processor classpath.</li>
     *   <li>The return type is not a {@link TypeKind#DECLARED} type.</li>
     *   <li>The return type's erasure is not {@code Future}.</li>
     *   <li>The {@code Future} is raw (no type argument).</li>
     * </ul>
     *
     * @param method    the method whose return type is unwrapped; must not be {@code null}
     * @param ctx       the codegen context; must not be {@code null}
     * @param errorSink a mutable one-element {@code boolean[]} used as an out-parameter;
     *                  {@code errorSink[0]} is set to {@code true} when an error is emitted
     * @return the unwrapped type argument, or {@code null} when an error was emitted
     */
    static TypeMirror unwrapReturnType(ExecutableElement method, CodegenContext ctx, boolean[] errorSink) {
        return unwrapFutureType(method, method.getReturnType(), ctx, errorSink);
    }

    /**
     * Unwraps the {@code T} from {@code Future<T>} on the given method's <em>resolved</em> return
     * type.
     *
     * <p>Identical to {@link #unwrapReturnType(ExecutableElement, CodegenContext, boolean[])} except
     * that the return type is read from {@code resolved} — the {@link ExecutableType} produced by
     * {@link javax.lang.model.util.Types#asMemberOf(javax.lang.model.type.DeclaredType, javax.lang.model.element.Element)}
     * — so that type variables inherited from a generic super-interface are already substituted
     * (e.g. {@code Future<T>} declared on {@code Parent<T>} reads as {@code Future<String>} when
     * viewed as a member of {@code Child extends Parent<String>}).
     *
     * @param method    the method whose return type is unwrapped, used for diagnostics; must not be
     *                  {@code null}
     * @param resolved  the method's type as a member of the viewing type; must not be {@code null}
     * @param ctx       the codegen context; must not be {@code null}
     * @param errorSink a mutable one-element {@code boolean[]} used as an out-parameter;
     *                  {@code errorSink[0]} is set to {@code true} when an error is emitted
     * @return the unwrapped type argument, or {@code null} when an error was emitted
     */
    static TypeMirror unwrapReturnType(
            ExecutableElement method, ExecutableType resolved, CodegenContext ctx, boolean[] errorSink) {
        return unwrapFutureType(method, resolved.getReturnType(), ctx, errorSink);
    }

    /**
     * Shared implementation of the two {@code unwrapReturnType} overloads.
     *
     * @param method     the method the return type belongs to, used for diagnostics; must not be
     *                   {@code null}
     * @param returnType the return type to unwrap (element-declared or resolved); must not be
     *                   {@code null}
     * @param ctx        the codegen context; must not be {@code null}
     * @param errorSink  a mutable one-element {@code boolean[]} used as an out-parameter;
     *                   {@code errorSink[0]} is set to {@code true} when an error is emitted
     * @return the unwrapped type argument, or {@code null} when an error was emitted
     */
    private static TypeMirror unwrapFutureType(
            ExecutableElement method, TypeMirror returnType, CodegenContext ctx, boolean[] errorSink) {
        TypeElement futureElement = ctx.elements().getTypeElement(ServiceAnnotations.FUTURE);

        if (futureElement == null || returnType.getKind() != TypeKind.DECLARED) {
            ctx.diagnostics()
                    .error(
                            method,
                            "%s",
                            Diagnostics.mustReturnFuture(method.getEnclosingElement()
                                            .getSimpleName() + "." + method.getSimpleName() + "()"));
            errorSink[0] = true;
            return null;
        }

        DeclaredType declared = (DeclaredType) returnType;
        TypeMirror erasure = ctx.types().erasure(declared);
        if (!ctx.types().isSameType(erasure, ctx.types().erasure(futureElement.asType()))) {
            ctx.diagnostics()
                    .error(
                            method,
                            "%s",
                            Diagnostics.mustReturnFuture(method.getEnclosingElement()
                                            .getSimpleName() + "." + method.getSimpleName() + "()"));
            errorSink[0] = true;
            return null;
        }

        List<? extends TypeMirror> typeArgs = declared.getTypeArguments();
        if (typeArgs.isEmpty()) {
            ctx.diagnostics()
                    .error(
                            method,
                            "Return type Future on %s.%s() must be parameterized (e.g. Future<MyResponse>),"
                                    + " raw Future is not allowed",
                            method.getEnclosingElement().getSimpleName(),
                            method.getSimpleName());
            errorSink[0] = true;
            return null;
        }

        return typeArgs.get(0);
    }
}
