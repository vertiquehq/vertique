// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.collect;

import com.palantir.javapoet.ClassName;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.RegisterAs;
import dev.vertique.codegen.RegisterAsContainer;
import dev.vertique.codegen.RegisterIntoSet;
import dev.vertique.codegen.RegisterIntoSetContainer;
import dev.vertique.codegen.dagger.processor.Registration;
import dev.vertique.codegen.dagger.processor.support.Filters;
import dev.vertique.codegen.validate.InjectConstructorValidator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Collects generic {@link RegisterAs} and {@link RegisterIntoSet} declarations from a processing
 * round and validates their implementation and target types.
 *
 * <p>The collector reads annotation mirrors rather than runtime annotation instances so that
 * source retention and compiler-generated repeatable containers are handled consistently.
 */
public final class RegistrationCollector {

    private static final String REGISTER_AS_FQN = RegisterAs.class.getName();
    private static final String REGISTER_AS_CONTAINER_FQN = RegisterAsContainer.class.getName();
    private static final String REGISTER_INTO_SET_FQN = RegisterIntoSet.class.getName();
    private static final String REGISTER_INTO_SET_CONTAINER_FQN = RegisterIntoSetContainer.class.getName();

    private final CodegenContext ctx;
    private final InjectConstructorValidator constructorValidator;

    /**
     * Constructs a collector bound to the processing context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public RegistrationCollector(CodegenContext ctx) {
        this.ctx = ctx;
        this.constructorValidator = new InjectConstructorValidator(ctx);
    }

    /**
     * Collects all generic registration declarations visible in the current round.
     *
     * @param roundEnv    the current processing round; must not be {@code null}
     * @param accumulator the list receiving valid registrations; must not be {@code null}
     */
    public void collect(RoundEnvironment roundEnv, List<Registration> accumulator) {
        Set<TypeElement> candidates = new LinkedHashSet<>();
        addAnnotatedTypes(roundEnv, REGISTER_AS_FQN, candidates);
        addAnnotatedTypes(roundEnv, REGISTER_AS_CONTAINER_FQN, candidates);
        addAnnotatedTypes(roundEnv, REGISTER_INTO_SET_FQN, candidates);
        addAnnotatedTypes(roundEnv, REGISTER_INTO_SET_CONTAINER_FQN, candidates);

        for (TypeElement candidate : candidates) {
            if (Filters.isOptedOut(candidate)) {
                continue;
            }
            if (!isConcrete(candidate)) {
                ctx.diagnostics()
                        .error(
                                candidate,
                                "%s must be a concrete class to use @RegisterAs or @RegisterIntoSet",
                                candidate.getQualifiedName());
                continue;
            }
            if (!constructorValidator.validate(candidate)) {
                continue;
            }

            for (AnnotationMirror mirror : candidate.getAnnotationMirrors()) {
                String annotationFqn = annotationFqn(mirror);
                if (REGISTER_AS_FQN.equals(annotationFqn)) {
                    addRegistration(candidate, mirror, false, accumulator);
                } else if (REGISTER_AS_CONTAINER_FQN.equals(annotationFqn)) {
                    addContainerRegistrations(candidate, mirror, false, accumulator);
                } else if (REGISTER_INTO_SET_FQN.equals(annotationFqn)) {
                    addRegistration(candidate, mirror, true, accumulator);
                } else if (REGISTER_INTO_SET_CONTAINER_FQN.equals(annotationFqn)) {
                    addContainerRegistrations(candidate, mirror, true, accumulator);
                }
            }
        }
    }

    private void addAnnotatedTypes(RoundEnvironment roundEnv, String annotationFqn, Set<TypeElement> candidates) {
        TypeElement annotationType = ctx.elements().getTypeElement(annotationFqn);
        if (annotationType == null) {
            return;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(annotationType)) {
            if (element instanceof TypeElement type) {
                candidates.add(type);
            }
        }
    }

    private void addContainerRegistrations(
            TypeElement candidate, AnnotationMirror container, boolean intoSet, List<Registration> accumulator) {
        for (AnnotationValue value : ctx.annotations().attributeArray(container, "value")) {
            if (value.getValue() instanceof AnnotationMirror nested) {
                addRegistration(candidate, nested, intoSet, accumulator);
            }
        }
    }

    private void addRegistration(
            TypeElement candidate, AnnotationMirror annotation, boolean intoSet, List<Registration> accumulator) {
        TypeMirror targetMirror =
                ctx.annotations().attributeClass(annotation, "value").orElse(null);
        if (targetMirror == null
                || !(targetMirror instanceof DeclaredType declaredTarget)
                || !(declaredTarget.asElement() instanceof TypeElement target)) {
            ctx.diagnostics()
                    .error(
                            candidate,
                            "@%s on %s must name a declared target type",
                            intoSet ? "RegisterIntoSet" : "RegisterAs",
                            candidate.getQualifiedName());
            return;
        }
        if (!ctx.types().isAssignable(candidate.asType(), targetMirror)) {
            ctx.diagnostics()
                    .error(
                            candidate,
                            "%s is not assignable to registration target %s",
                            candidate.getQualifiedName(),
                            target.getQualifiedName());
            return;
        }
        accumulator.add(new Registration(candidate, target, ClassName.get(candidate), intoSet));
    }

    private boolean isConcrete(TypeElement type) {
        return (type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.RECORD)
                && !type.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT);
    }

    private static String annotationFqn(AnnotationMirror mirror) {
        Element annotationElement = mirror.getAnnotationType().asElement();
        if (annotationElement instanceof TypeElement type) {
            return type.getQualifiedName().toString();
        }
        return annotationElement.toString();
    }
}
