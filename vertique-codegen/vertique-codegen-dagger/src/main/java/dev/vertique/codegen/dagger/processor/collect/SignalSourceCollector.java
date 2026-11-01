// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.collect;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.dagger.processor.Registration;
import dev.vertique.codegen.dagger.processor.support.Filters;
import dev.vertique.codegen.validate.InjectConstructorValidator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Collects classes annotated {@code @WorkflowSignalSource} and registers each into the {@code Set} of every interface it
 * implements that is marked {@code @SignalSourceType}.
 *
 * <p>Both annotations belong to the workflow modules, which this module takes no dependency on, so they are identified
 * by fully qualified name and read through annotation mirrors. The registration is required: a source that the
 * generated module cannot reference is an error, never a skipped warning. The module that the marker names is carried
 * on the registration, so the generated registrations module can include it.
 */
public final class SignalSourceCollector {

    private static final String SIGNAL_SOURCE_FQN = "dev.vertique.workflow.source.WorkflowSignalSource";
    private static final String SIGNAL_SOURCE_TYPE_FQN = "dev.vertique.workflow.source.SignalSourceType";

    private final CodegenContext ctx;
    private final InjectConstructorValidator constructorValidator;

    /**
     * Constructs a collector bound to the processing context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public SignalSourceCollector(CodegenContext ctx) {
        this.ctx = ctx;
        this.constructorValidator = new InjectConstructorValidator(ctx);
    }

    /**
     * Collects the signal source classes visible in the current round.
     *
     * @param roundEnv    the current processing round; must not be {@code null}
     * @param accumulator the list receiving valid registrations; must not be {@code null}
     */
    public void collect(RoundEnvironment roundEnv, List<Registration> accumulator) {
        TypeElement annotation = ctx.elements().getTypeElement(SIGNAL_SOURCE_FQN);
        if (annotation == null) {
            return;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
            if (element instanceof TypeElement candidate && !Filters.isOptedOut(candidate)) {
                collect(candidate, accumulator);
            }
        }
    }

    private void collect(TypeElement candidate, List<Registration> accumulator) {
        if (!isConcrete(candidate)) {
            ctx.diagnostics()
                    .error(
                            candidate,
                            "%s must be a concrete class to use @WorkflowSignalSource",
                            candidate.getQualifiedName());
            return;
        }
        if (!constructorValidator.validate(candidate)) {
            return;
        }
        Set<TypeElement> sourceTypes = new LinkedHashSet<>();
        collectMarkedInterfaces(candidate, sourceTypes);
        if (sourceTypes.isEmpty()) {
            ctx.diagnostics()
                    .error(
                            candidate,
                            "%s is annotated @WorkflowSignalSource but implements no interface annotated"
                                    + " @SignalSourceType",
                            candidate.getQualifiedName());
            return;
        }
        for (TypeElement sourceType : sourceTypes) {
            TypeElement module = transportModule(sourceType);
            if (module == null) {
                ctx.diagnostics()
                        .error(
                                candidate,
                                "@SignalSourceType on %s must name a declared module class",
                                sourceType.getQualifiedName());
                continue;
            }
            accumulator.add(Registration.sourceClass(candidate, sourceType, module));
        }
    }

    private void collectMarkedInterfaces(TypeElement type, Set<TypeElement> result) {
        for (TypeMirror supertype : ctx.types().directSupertypes(type.asType())) {
            if (supertype instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
                if (element.getKind() == ElementKind.INTERFACE
                        && AnnotationMirrors.isPresent(element, SIGNAL_SOURCE_TYPE_FQN)) {
                    result.add(element);
                }
                collectMarkedInterfaces(element, result);
            }
        }
    }

    private TypeElement transportModule(TypeElement sourceType) {
        return AnnotationMirrors.findByFqn(sourceType, SIGNAL_SOURCE_TYPE_FQN)
                .map(mirror -> moduleOf(mirror))
                .orElse(null);
    }

    private TypeElement moduleOf(AnnotationMirror mirror) {
        return ctx.annotations()
                .attributeClass(mirror, "module")
                .filter(DeclaredType.class::isInstance)
                .map(type -> ((DeclaredType) type).asElement())
                .filter(TypeElement.class::isInstance)
                .map(TypeElement.class::cast)
                .orElse(null);
    }

    private static boolean isConcrete(TypeElement type) {
        return (type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.RECORD)
                && !type.getModifiers().contains(Modifier.ABSTRACT);
    }
}
