// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.JaxRsAnnotations;
import dev.vertique.codegen.jaxrs.EffectiveMethodContract;
import dev.vertique.codegen.jaxrs.EffectiveParamContract;
import dev.vertique.codegen.jaxrs.JaxRsHierarchy;
import dev.vertique.codegen.jaxrs.JaxRsParamSource;
import dev.vertique.rest.core.context.RestContextMessages;
import dev.vertique.rest.core.context.RestContextTypes;
import java.util.List;
import java.util.function.Predicate;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Validates {@code @Context} parameter constraints on JAX-RS resource methods at compile time,
 * mirroring the runtime {@code RouteValidator.addContextParamViolations} rules
 * (FR-REST-187/188/189). This is the compile-time parity guardrail for split inherited
 * annotations, where {@code @Context} may appear on an interface method parameter while a
 * value-binding annotation ({@code @PathParam}, etc.) appears on the corresponding concrete
 * parameter.
 *
 * <p>For each parameter, at most one diagnostic is emitted in this priority order:
 * <ol>
 *   <li><strong>Conflict</strong> (FR-REST-187) — the parameter carries both {@code @Context} and a
 *       JAX-RS value-binding annotation ({@code @PathParam}, {@code @QueryParam},
 *       {@code @HeaderParam}, {@code @CookieParam}, {@code @FormParam}, {@code @BeanParam}), on
 *       either the concrete parameter or a corresponding interface parameter. This also catches the
 *       split-inherited case where {@code @Context} is on the interface and a binding annotation is
 *       on the concrete parameter, or vice versa.</li>
 *   <li><strong>Reserved unsupported type</strong> (FR-REST-188) — the parameter's erased type is
 *       in {@link RestContextTypes#RESERVED_UNSUPPORTED_JAXRS_FQNS}.</li>
 *   <li><strong>Non-injectable type</strong> (FR-REST-189) — the parameter's erased type is not
 *       {@code RoutingContext} (or a subtype), <em>exactly</em> JAX-RS {@code SecurityContext}, or a
 *       {@code ContextValue} subtype. The JAX-RS {@code SecurityContext} is matched exactly because
 *       the resolver only bridges that exact type.</li>
 * </ol>
 */
public final class ContextParamValidator {

    private final CodegenContext ctx;

    /**
     * Creates a new {@code ContextParamValidator} bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public ContextParamValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    // --- Contract-based API ---

    /**
     * Validates all parameters of the given method contract for {@code @Context} violations,
     * emitting an {@code ERROR} diagnostic for each offending parameter.
     *
     * <p>At most one diagnostic is emitted per parameter, in the priority order documented on this
     * class. Returns {@code false} if any violation was emitted so the pipeline caller can
     * short-circuit noisier downstream validators (e.g. path-alignment).
     *
     * @param methodContract the resolved method contract whose parameters to validate; must not be
     *                       {@code null}
     * @return {@code true} if no context-param violations were found; {@code false} if at least one
     *         diagnostic was emitted
     */
    public boolean validate(EffectiveMethodContract methodContract) {
        ExecutableElement concreteMethod = methodContract.concreteMethod();
        TypeElement resourceClass = (TypeElement) concreteMethod.getEnclosingElement();
        String resourceClassName = resourceClass.getSimpleName().toString();
        String methodName = concreteMethod.getSimpleName().toString();

        List<TypeElement> interfaces = JaxRsHierarchy.allInterfaces(ctx, resourceClass);

        boolean anyViolation = false;
        List<EffectiveParamContract> params = methodContract.params();

        for (int i = 0; i < params.size(); i++) {
            EffectiveParamContract pc = params.get(i);

            // --- Determine effective @Context presence ---
            boolean hasContextOnConcrete =
                    AnnotationMirrors.isPresent(pc.concreteParameter(), JaxRsAnnotations.CONTEXT);

            // Check if any overridden interface method's same-position parameter carries @Context
            boolean hasContextOnInterface = anyInterfaceParamMatches(
                    concreteMethod, i, interfaces, p -> AnnotationMirrors.isPresent(p, JaxRsAnnotations.CONTEXT));

            boolean effectiveContext = hasContextOnConcrete || hasContextOnInterface;

            // A parameter is subject to context validation when it is either classified as CONTEXT
            // by the param classifier (because it carries @Context OR its type is injectable — a
            // RoutingContext / JAX-RS SecurityContext / ContextValue subtype) OR it has an effective
            // @Context annotation (the latter catches the split case where the classifier labeled it
            // PATH/QUERY/etc. because the binding annotation is on the concrete param).
            boolean isContextParam = pc.source() == JaxRsParamSource.CONTEXT || effectiveContext;
            if (!isContextParam) {
                continue;
            }

            // Determine the param's simple type name for the diagnostic message
            String paramTypeSimpleName = simpleName(pc.concreteParameter());

            // --- Priority 1: conflict (context param + value-binding annotation) ---
            // A context parameter must not also carry a value-binding annotation. This mirrors the
            // runtime RouteValidator, which flags any CONTEXT-source param whose merged annotations
            // include a binding annotation — regardless of whether the param became CONTEXT via an
            // explicit @Context or via an injectable type (e.g. @PathParam on a ContextValue type).
            // The binding annotation may sit on the concrete param or on a matching interface param.
            boolean conflict = hasBindingAnnotation(pc.concreteParameter())
                    || anyInterfaceParamMatches(concreteMethod, i, interfaces, this::hasBindingAnnotation);
            if (conflict) {
                ctx.diagnostics()
                        .error(
                                pc.concreteParameter(),
                                RestContextMessages.contextParamConflict(
                                        resourceClassName, methodName, paramTypeSimpleName));
                anyViolation = true;
                continue;
            }

            // --- Priority 2: reserved JAX-RS type ---
            String erasedFqn = erasedFqn(pc.concreteParameter().asType());
            if (RestContextTypes.RESERVED_UNSUPPORTED_JAXRS_FQNS.contains(erasedFqn)) {
                ctx.diagnostics()
                        .error(
                                pc.concreteParameter(),
                                RestContextMessages.unsupportedJaxRsContextType(
                                        resourceClassName, methodName, paramTypeSimpleName));
                anyViolation = true;
                continue;
            }

            // --- Priority 3: non-injectable type ---
            if (!isInjectable(pc.concreteParameter().asType())) {
                ctx.diagnostics()
                        .error(
                                pc.concreteParameter(),
                                RestContextMessages.nonInjectableContextType(
                                        resourceClassName, methodName, paramTypeSimpleName));
                anyViolation = true;
            }
        }

        return !anyViolation;
    }

    // --- Private helpers ---

    /**
     * Returns {@code true} if the interface method parameter at position {@code paramIndex}
     * (in any transitively implemented interface) satisfies the given {@code test} predicate.
     *
     * <p>This is the single shared BFS walker used by both the {@code @Context} check and the
     * value-binding-annotation check; callers pass the appropriate predicate:
     * <ul>
     *   <li>{@code p -> AnnotationMirrors.isPresent(p, JaxRsAnnotations.CONTEXT)} — checks for
     *       {@code @Context} on any matching interface param.</li>
     *   <li>{@code this::hasBindingAnnotation} — checks for a JAX-RS binding annotation on any
     *       matching interface param.</li>
     * </ul>
     *
     * @param concreteMethod the concrete method whose matching interface method to inspect
     * @param paramIndex     the zero-based parameter index
     * @param interfaces     the BFS-ordered list of interfaces to walk
     * @param test           predicate applied to each candidate interface parameter element
     * @return {@code true} if any matching interface param satisfies {@code test}
     */
    private boolean anyInterfaceParamMatches(
            ExecutableElement concreteMethod,
            int paramIndex,
            List<TypeElement> interfaces,
            Predicate<VariableElement> test) {
        for (TypeElement iface : interfaces) {
            ExecutableElement ifaceMethod = JaxRsHierarchy.findMatchingMethod(ctx, concreteMethod, iface);
            if (ifaceMethod == null) {
                continue;
            }
            var ifaceParams = ifaceMethod.getParameters();
            if (paramIndex >= ifaceParams.size()) {
                continue;
            }
            if (test.test(ifaceParams.get(paramIndex))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the given parameter element carries any JAX-RS value-binding
     * annotation ({@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam},
     * {@code @CookieParam}, {@code @FormParam}, {@code @BeanParam}).
     *
     * @param param the parameter element to inspect; must not be {@code null}
     * @return {@code true} if a binding annotation is present
     */
    private boolean hasBindingAnnotation(VariableElement param) {
        return AnnotationMirrors.isPresent(param, JaxRsAnnotations.PATH_PARAM)
                || AnnotationMirrors.isPresent(param, JaxRsAnnotations.QUERY_PARAM)
                || AnnotationMirrors.isPresent(param, JaxRsAnnotations.HEADER_PARAM)
                || AnnotationMirrors.isPresent(param, JaxRsAnnotations.COOKIE_PARAM)
                || AnnotationMirrors.isPresent(param, JaxRsAnnotations.FORM_PARAM)
                || AnnotationMirrors.isPresent(param, JaxRsAnnotations.BEAN_PARAM);
    }

    /**
     * Returns the erased simple type name of the parameter — the same name the runtime
     * {@code RouteValidator} uses in its diagnostic messages.
     *
     * @param param the parameter
     * @return the simple type name; never {@code null}
     */
    private String simpleName(VariableElement param) {
        TypeMirror erased = ctx.types().erasure(param.asType());
        var element = ctx.types().asElement(erased);
        if (element instanceof TypeElement te) {
            return te.getSimpleName().toString();
        }
        return erased.toString();
    }

    /**
     * Returns the erased fully-qualified name of the given type mirror.
     *
     * @param type the type mirror; must not be {@code null}
     * @return the erased FQN string
     */
    private String erasedFqn(TypeMirror type) {
        TypeMirror erased = ctx.types().erasure(type);
        var element = ctx.types().asElement(erased);
        if (element instanceof TypeElement te) {
            return te.getQualifiedName().toString();
        }
        return erased.toString();
    }

    /**
     * Returns {@code true} if the given type is injectable as a {@code @Context} parameter.
     * Mirrors the APT-level check in {@link dev.vertique.codegen.jaxrs.JaxRsParamClassifier}:
     * <ul>
     *   <li>{@code RoutingContext} — assignability (subtypes OK; resolver uses {@code isInstance}).
     *   </li>
     *   <li>JAX-RS {@code SecurityContext} — <em>exact type only</em> ({@link javax.lang.model.util.Types#isSameType}).
     *       The runtime resolver only handles the exact JAX-RS interface; a subtype would pass
     *       this check but fail at request time, so subtypes are intentionally excluded.
     *   </li>
     *   <li>{@code ContextValue} — assignability (subtypes are the whole point of this arm).
     *   </li>
     * </ul>
     *
     * @param type the parameter type mirror; must not be {@code null}
     * @return {@code true} if the type is injectable
     */
    private boolean isInjectable(TypeMirror type) {
        TypeMirror erased = ctx.types().erasure(type);
        return isAssignableTo(erased, erasedMirrorOf(RestContextTypes.ROUTING_CONTEXT_FQN))
                // JAX-RS SecurityContext: exact type only — subtypes are not resolvable by the
                // framework resolver and must not be silently accepted as injectable.
                || isExactly(erased, erasedMirrorOf(RestContextTypes.JAXRS_SECURITY_CONTEXT_FQN))
                || isAssignableTo(erased, erasedMirrorOf(RestContextTypes.CONTEXT_VALUE_FQN));
    }

    /**
     * Returns {@code true} if {@code paramType} is the <em>exact same type</em> as
     * {@code targetMirror} (no subtype or supertype relationship — same erased type only).
     * Returns {@code false} when {@code targetMirror} is {@code null} (type not on classpath).
     *
     * <p>Used for the JAX-RS {@code SecurityContext} arm: the runtime resolver only handles the
     * exact interface, so subtypes must not be accepted here.
     *
     * @param paramType    the erased parameter type mirror
     * @param targetMirror the erased target type mirror, or {@code null}
     * @return {@code true} if {@code paramType} and {@code targetMirror} are the same type
     */
    private boolean isExactly(TypeMirror paramType, TypeMirror targetMirror) {
        if (targetMirror == null) {
            return false;
        }
        try {
            return ctx.types().isSameType(paramType, targetMirror);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns the erased {@link TypeMirror} for the given FQN, or {@code null} if the type is
     * not on the compilation classpath.
     *
     * @param fqn the fully-qualified class name
     * @return the erased type mirror, or {@code null}
     */
    private TypeMirror erasedMirrorOf(String fqn) {
        TypeElement element = ctx.elements().getTypeElement(fqn);
        if (element == null) {
            return null;
        }
        return ctx.types().erasure(element.asType());
    }

    /**
     * Returns {@code true} if {@code paramType} is assignable to {@code contextMirror}.
     * Returns {@code false} when {@code contextMirror} is {@code null}.
     *
     * @param paramType     the erased parameter type mirror
     * @param contextMirror the erased base type mirror, or {@code null}
     * @return {@code true} if assignable
     */
    private boolean isAssignableTo(TypeMirror paramType, TypeMirror contextMirror) {
        if (contextMirror == null) {
            return false;
        }
        try {
            return ctx.types().isAssignable(paramType, contextMirror);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
