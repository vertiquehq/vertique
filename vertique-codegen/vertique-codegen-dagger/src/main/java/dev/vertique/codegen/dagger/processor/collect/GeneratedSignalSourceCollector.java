// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.dagger.processor.collect;

import com.palantir.javapoet.ClassName;
import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.SignalSourceNaming;
import dev.vertique.codegen.dagger.processor.Registration;
import dev.vertique.codegen.dagger.processor.support.Filters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;

/**
 * Registers, in the round in which it sees an interface, the signal source class that another processor generates for
 * it, and reports the class that no processor generated.
 *
 * <p>The interfaces are those annotated with an annotation that carries {@code @SignalSourceForm}, such as
 * {@code @KafkaSignalListener} or {@code @CamelSignalListener}. The generated class is named by
 * {@link SignalSourceNaming#generatedName}, the one rule that the generating processor also calls, and is bound into
 * the {@code Set} of the form's {@code value()}, with this module naming no transport type.
 *
 * <p>The class does not exist yet when this processor emits, in the first round, so the registration refers to it by
 * name, which a {@code @Binds} method tolerates until the class appears in a later round. The final round looks each
 * predicted class up and reports the missing ones on their interfaces, naming the processor to add, because Dagger
 * would otherwise fail with a missing-type message that points away from the cause. The report is skipped when an
 * earlier round raised an error, which is the cause: a second error naming a processor to add would mislead.
 *
 * <p>Every annotation is identified by fully qualified name and read through annotation mirrors, because this module
 * takes no dependency on the Kafka or workflow modules.
 */
public final class GeneratedSignalSourceCollector {

    private static final String SIGNAL_SOURCE_TYPE_FQN = "dev.vertique.workflow.source.SignalSourceType";
    private static final String SIGNAL_SOURCE_FORM_FQN = "dev.vertique.workflow.source.SignalSourceForm";

    /** A class predicted in the first round: the interface it is generated for and what to add if it is missing. */
    private record Prediction(TypeElement origin, String className, String remedy) {}

    private final CodegenContext ctx;
    private final List<Prediction> predictions = new ArrayList<>();

    /**
     * Constructs a collector bound to the processing context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public GeneratedSignalSourceCollector(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Predicts and registers the generated signal sources of the interfaces visible in the round, and keeps the
     * predicted names for {@link #reportMissing}.
     *
     * @param annotations the annotation types present in the round; must not be {@code null}
     * @param roundEnv    the current processing round; must not be {@code null}
     * @param accumulator the list receiving the registrations; must not be {@code null}
     */
    public void collect(
            Set<? extends TypeElement> annotations, RoundEnvironment roundEnv, List<Registration> accumulator) {
        for (TypeElement annotation : annotations) {
            AnnotationMirrors.findByFqn(annotation, SIGNAL_SOURCE_FORM_FQN)
                    .ifPresent(form -> collectForm(annotation, form, roundEnv, accumulator));
        }
    }

    /**
     * Reports, on its interface, each predicted class that no processor generated.
     *
     * <p>Does nothing when an earlier round raised an error, because that error is the cause and the generating
     * processor may have generated nothing for it.
     *
     * @param roundEnv the final processing round; must not be {@code null}
     */
    public void reportMissing(RoundEnvironment roundEnv) {
        if (roundEnv.errorRaised()) {
            return;
        }
        for (Prediction prediction : predictions) {
            if (ctx.elements().getTypeElement(prediction.className()) == null) {
                ctx.diagnostics()
                        .error(
                                prediction.origin(),
                                "%s should have been generated for %s, but no processor generated it; %s",
                                prediction.className(),
                                prediction.origin().getQualifiedName(),
                                prediction.remedy());
            }
        }
    }

    private void collectForm(
            TypeElement annotation, AnnotationMirror form, RoundEnvironment roundEnv, List<Registration> accumulator) {
        TypeElement target = ctx.annotations()
                .attributeClass(form, "value")
                .filter(DeclaredType.class::isInstance)
                .map(type -> (TypeElement) ((DeclaredType) type).asElement())
                .orElse(null);
        for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
            if (!(element instanceof TypeElement type)
                    || type.getKind() != ElementKind.INTERFACE
                    || Filters.isOptedOut(type)) {
                continue;
            }
            if (target == null) {
                ctx.diagnostics()
                        .error(
                                annotation,
                                "@SignalSourceForm on %s must name a source interface",
                                annotation.getQualifiedName());
                return;
            }
            register(
                    type,
                    target,
                    "add the annotation processor that handles @" + annotation.getSimpleName()
                            + " to the annotation processor path",
                    accumulator);
        }
    }

    private void register(TypeElement origin, TypeElement target, String remedy, List<Registration> accumulator) {
        TypeElement module = AnnotationMirrors.findByFqn(target, SIGNAL_SOURCE_TYPE_FQN)
                .flatMap(mirror -> ctx.annotations().attributeClass(mirror, "module"))
                .filter(DeclaredType.class::isInstance)
                .map(type -> (TypeElement) ((DeclaredType) type).asElement())
                .orElse(null);
        if (module == null) {
            ctx.diagnostics()
                    .error(
                            origin,
                            "%s is not annotated @SignalSourceType with a module, so the source generated for %s cannot"
                                    + " be registered",
                            target.getQualifiedName(),
                            origin.getQualifiedName());
            return;
        }
        String className = SignalSourceNaming.generatedName(origin);
        int split = className.lastIndexOf('.');
        ClassName implementation =
                ClassName.get(split < 0 ? "" : className.substring(0, split), className.substring(split + 1));
        accumulator.add(Registration.generatedSource(origin, implementation, target, module));
        predictions.add(new Prediction(origin, className, remedy));
    }
}
