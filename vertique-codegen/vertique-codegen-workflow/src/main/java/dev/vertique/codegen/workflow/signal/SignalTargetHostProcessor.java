// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.signal;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that reports, on its type, each type that declares a {@code @WorkflowSignalTarget} and that no
 * processor turns into a signal source: a type that is neither annotated {@code @KafkaListener} nor annotated with an
 * annotation that carries {@code @SignalSourceForm}. Without this report the target compiles and does nothing.
 *
 * <p>A type annotated {@code @KafkaListener} is left to {@link KafkaSignalListenerProcessor}, which names
 * {@code @KafkaSignalListener}, so no type is reported twice. A type annotated with a form annotation is left to the
 * processor of that annotation, which validates its targets.
 *
 * <p>This class is registered via {@code META-INF/services/javax.annotation.processing.Processor}.
 */
@SupportedAnnotationTypes({SignalTargetHostProcessor.SIGNAL_TARGET, SignalTargetHostProcessor.SIGNAL_TARGETS})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions(CodegenContext.OPTION_OUTPUT_PACKAGE)
public final class SignalTargetHostProcessor extends AbstractProcessor {

    static final String SIGNAL_TARGET = SignalTargetScan.SIGNAL_TARGET_FQN;
    static final String SIGNAL_TARGETS = SignalTargetScan.SIGNAL_TARGETS_FQN;

    private static final String SIGNAL_SOURCE_FORM_FQN = "dev.vertique.workflow.source.SignalSourceForm";

    private CodegenContext ctx;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        Set<TypeElement> hosts = new LinkedHashSet<>();
        for (String annotationName : new String[] {SIGNAL_TARGET, SIGNAL_TARGETS}) {
            TypeElement annotation = ctx.elements().getTypeElement(annotationName);
            if (annotation == null) {
                continue;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element instanceof ExecutableElement && element.getEnclosingElement() instanceof TypeElement host) {
                    hosts.add(host);
                }
            }
        }
        for (TypeElement host : hosts) {
            if (!AnnotationMirrors.isPresent(host, KafkaSignalListenerProcessor.KAFKA_LISTENER)
                    && !carriesSignalSourceForm(host)) {
                ctx.diagnostics()
                        .error(
                                host,
                                "%s carries @WorkflowSignalTarget, but it is not annotated with an annotation that"
                                        + " carries @SignalSourceForm, such as @KafkaSignalListener, so no signal"
                                        + " source is generated for it and its targets would never run",
                                host.getQualifiedName());
            }
        }
        return false;
    }

    private static boolean carriesSignalSourceForm(TypeElement host) {
        return host.getAnnotationMirrors().stream()
                .anyMatch(mirror ->
                        AnnotationMirrors.isPresent(mirror.getAnnotationType().asElement(), SIGNAL_SOURCE_FORM_FQN));
    }
}
