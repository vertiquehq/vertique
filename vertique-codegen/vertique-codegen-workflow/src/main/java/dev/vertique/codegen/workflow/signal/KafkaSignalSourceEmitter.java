// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.signal;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.SignalSourceNaming;
import dev.vertique.codegen.workflow.signal.SignalTargetScan.Target;
import java.io.IOException;
import java.util.List;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Emits {@code <Interface>_SignalSource}, the {@code KafkaSignalSource} generated for an interface annotated
 * {@code @KafkaSignalListener} whose methods carry {@code @WorkflowSignalTarget}.
 *
 * <p>The generated class has an {@code @Inject} constructor and no other dependency, so the registration that the
 * dagger processor generates binds it into the {@code Set} of Kafka signal sources. Its {@code define} method calls the
 * same builder as a source written by hand: the listener's topic, group and name, the dedup accessor, and for each
 * target the signal, the key accessor and the no-match policy. It names no key value and builds no payload mapping.
 * Every accessor is a method reference, so the generated class compiles only when the message type has it.
 *
 * <p>The class is named by {@link SignalSourceNaming#generatedName}, the one rule that the registering processor also
 * calls, and lands in the interface's package. The workflow types are referenced by name, because this module takes no
 * dependency on the workflow modules.
 */
final class KafkaSignalSourceEmitter {

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.workflow.signal.KafkaSignalListenerProcessor";

    private static final ClassName KAFKA_SIGNAL_SOURCE =
            ClassName.get("dev.vertique.workflow.kafka", "KafkaSignalSource");
    private static final ClassName KAFKA_SIGNALS = ClassName.get("dev.vertique.workflow.kafka", "KafkaSignals");
    private static final ClassName SIGNAL_NO_MATCH = ClassName.get("dev.vertique.workflow.source", "SignalNoMatch");
    private static final ClassName INJECT = ClassName.get("jakarta.inject", "Inject");

    private final CodegenContext ctx;

    /**
     * Constructs an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    KafkaSignalSourceEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the signal source of a signal listener.
     *
     * @param origin the annotated interface; must not be {@code null}
     * @param settings the topic, group and name the annotation states; must not be {@code null}
     * @param targets the validated targets of the listener, which share one message type and one dedup accessor; must
     *     not be empty
     */
    void emit(TypeElement origin, ListenerSettings settings, List<Target> targets) {
        String generated = SignalSourceNaming.generatedName(origin);
        int split = generated.lastIndexOf('.');
        String packageName = split < 0 ? "" : generated.substring(0, split);
        String simpleName = generated.substring(split + 1);

        TypeMirror message = targets.get(0).messageType();
        TypeName messageName = TypeName.get(message);

        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(INJECT)
                .build();
        MethodSpec define = MethodSpec.methodBuilder("define")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterizedTypeName.get(KAFKA_SIGNALS, messageName), "source")
                .addCode(defineBody(settings, targets, messageName))
                .build();
        TypeSpec type = TypeSpec.classBuilder(simpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(KAFKA_SIGNAL_SOURCE, messageName))
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addOriginatingElement(origin)
                .addJavadoc(
                        "Generated Kafka signal source of {@link $T}.\n\n"
                                + "<p>Describes the listener's topic and signals on the builder, as a source written by hand does.\n",
                        ClassName.get(origin))
                .addMethod(constructor)
                .addMethod(define)
                .build();
        try {
            JavaFile.builder(packageName, type).build().writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(origin, "Failed to write generated source file '%s': %s", generated, e.getMessage());
        }
    }

    private CodeBlock defineBody(ListenerSettings settings, List<Target> targets, TypeName messageName) {
        CodeBlock.Builder chain = CodeBlock.builder()
                .add(
                        "$[source.topic($S)\n.groupId($S)\n.name($S)",
                        settings.topic(),
                        settings.groupId(),
                        settings.name());
        String dedupByMethod = targets.get(0).dedupByMethod();
        if (!dedupByMethod.isBlank()) {
            chain.add("\n.dedupBy($T::$L)", messageName, dedupByMethod);
        }
        for (Target target : targets) {
            chain.add("\n.signal($T.class, $S)", ClassName.get(target.contract()), target.signal());
            if (!target.key().isBlank()) {
                chain.add("\n.key($T::$L)", messageName, target.keyMethod());
            } else {
                chain.add("\n.businessKey($T::$L)", messageName, target.keyMethod());
            }
            chain.add("\n.onNoMatch($T.$L)", SIGNAL_NO_MATCH, target.onNoMatch());
        }
        chain.add(";\n$]");
        return chain.build();
    }
}
