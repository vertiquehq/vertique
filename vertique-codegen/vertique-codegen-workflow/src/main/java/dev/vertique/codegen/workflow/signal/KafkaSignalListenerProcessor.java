// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.signal;

import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.workflow.signal.SignalTargetScan.DedupRequirement;
import dev.vertique.codegen.workflow.signal.SignalTargetScan.Target;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Annotation processor that generates {@code <Interface>_SignalSource}, a {@code KafkaSignalSource}, for each
 * interface annotated {@code @KafkaSignalListener} whose methods carry {@code @WorkflowSignalTarget}.
 *
 * <p>{@link SignalTargetScan} reads and validates the targets, with {@code dedupBy} required on a business-key target
 * only, because a Kafka record has an offset to default to; the rules it applies are those of every transport, and this
 * processor holds no second copy of one. What is Kafka's own is checked by {@link SignalListenerValidator}. Every
 * violation of one interface is reported in one compilation, and an interface with a violation generates nothing.
 *
 * <p>An interface or class annotated {@code @KafkaListener} that carries a target is an error naming
 * {@code @KafkaSignalListener}: the Kafka listener processor routes records to {@code @KafkaHandler} methods and would
 * leave the target without a source, so the target would never run.
 *
 * <p>The generated class is registered by {@code AutoWireProcessor}, which binds it by name because the annotation
 * carries {@code @SignalSourceForm}. This processor names no Dagger type.
 *
 * <p>This class is registered via {@code META-INF/services/javax.annotation.processing.Processor}.
 */
@SupportedAnnotationTypes({
    KafkaSignalListenerProcessor.KAFKA_SIGNAL_LISTENER,
    KafkaSignalListenerProcessor.KAFKA_LISTENER
})
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedOptions(CodegenContext.OPTION_OUTPUT_PACKAGE)
public final class KafkaSignalListenerProcessor extends AbstractProcessor {

    static final String KAFKA_SIGNAL_LISTENER = "dev.vertique.workflow.kafka.KafkaSignalListener";
    static final String KAFKA_LISTENER = "dev.vertique.kafka.KafkaListener";

    private CodegenContext ctx;
    private SignalListenerValidator validator;
    private KafkaSignalSourceEmitter emitter;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        ctx = new CodegenContext(env);
        validator = new SignalListenerValidator(ctx);
        emitter = new KafkaSignalSourceEmitter(ctx);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        TypeElement listener = ctx.elements().getTypeElement(KAFKA_LISTENER);
        if (listener != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(listener)) {
                if (element instanceof TypeElement type && SignalTargetScan.declaresTarget(type)) {
                    ctx.diagnostics()
                            .error(
                                    type,
                                    "%s carries @WorkflowSignalTarget but is annotated @KafkaListener, which generates no"
                                            + " signal source, so its targets would never run; annotate the interface"
                                            + " @KafkaSignalListener instead",
                                    type.getQualifiedName());
                }
            }
        }
        TypeElement signalListener = ctx.elements().getTypeElement(KAFKA_SIGNAL_LISTENER);
        if (signalListener != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(signalListener)) {
                if (element instanceof TypeElement type) {
                    generate(type, signalListener);
                }
            }
        }
        return false;
    }

    private void generate(TypeElement type, TypeElement annotation) {
        String name = type.getQualifiedName().toString();
        if (type.getKind() != ElementKind.INTERFACE) {
            ctx.diagnostics()
                    .error(type, "%s: @KafkaSignalListener is allowed only on an interface; %s is not", name, name);
            return;
        }
        ListenerSettings settings = read(type, annotation);
        boolean settingsValid = settings != null && validator.validate(type, settings);
        List<Target> targets = SignalTargetScan.scan(ctx, type, DedupRequirement.BUSINESS_KEY_TARGETS);
        if (targets.isEmpty()) {
            if (!SignalTargetScan.declaresTarget(type)) {
                ctx.diagnostics()
                        .error(type, "%s: @KafkaSignalListener has no method annotated @WorkflowSignalTarget", name);
            }
            return;
        }
        if (settingsValid) {
            emitter.emit(type, settings, targets);
        }
    }

    /** Reads {@code name()}, {@code topic()} and {@code groupId()} of the annotation, or returns {@code null}. */
    private ListenerSettings read(TypeElement type, TypeElement annotation) {
        return type.getAnnotationMirrors().stream()
                .filter(mirror -> mirror.getAnnotationType().asElement().equals(annotation))
                .findFirst()
                .map(mirror ->
                        new ListenerSettings(text(mirror, "name"), text(mirror, "topic"), text(mirror, "groupId")))
                .orElse(null);
    }

    private String text(javax.lang.model.element.AnnotationMirror mirror, String attribute) {
        return ctx.annotations().attribute(mirror, attribute, String.class).orElse("");
    }
}
