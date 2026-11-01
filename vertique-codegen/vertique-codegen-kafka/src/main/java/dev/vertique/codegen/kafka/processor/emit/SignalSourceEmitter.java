// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.SignalListeners;
import dev.vertique.codegen.SignalTargetScan.Target;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import java.io.IOException;
import java.util.List;
import javax.annotation.processing.Generated;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;

/**
 * Emits {@code <Interface>_SignalSource}, the {@code KafkaSignalSource} generated for a signal listener: a
 * {@link dev.vertique.kafka.KafkaListener @KafkaListener} interface whose methods carry
 * {@code @WorkflowSignalTarget}.
 *
 * <p>The generated class has an {@code @Inject} constructor and no other dependency, so the registration that the
 * dagger processor generates binds it into the {@code Set} of Kafka signal sources. Its {@code define} method calls the
 * same builder as a source written by hand: the listener's topic, group and name, the dedup accessor, and for each
 * target the signal, the key accessor and the no-match policy. It names no key value and builds no payload mapping.
 * Every accessor is a method reference, so the generated class compiles only when the message type has it.
 *
 * <p>The class is named by {@link SignalListeners#generatedName}, the one rule that the registering processor also
 * calls, and lands in the interface's package. The workflow types are referenced by name, because this module takes no
 * dependency on the workflow modules.
 */
public final class SignalSourceEmitter {

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.kafka.processor.KafkaConsumerProcessor";

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
    public SignalSourceEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the signal source of a signal listener.
     *
     * @param model the validated model of the listener; must not be {@code null}
     * @param targets the validated targets of the listener, which share one message type and one dedup accessor; must
     *     not be empty
     */
    public void emit(ListenerModel model, List<Target> targets) {
        TypeElement origin = model.originType();
        String generated = SignalListeners.generatedName(origin);
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
                .addCode(defineBody(model, targets, messageName, message))
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

    private CodeBlock defineBody(ListenerModel model, List<Target> targets, TypeName messageName, TypeMirror message) {
        CodeBlock.Builder chain = CodeBlock.builder()
                .add("$[source.topic($S)\n.groupId($S)\n.name($S)", model.topic(), model.groupId(), model.name());
        String dedupBy = targets.get(0).dedupBy();
        if (!dedupBy.isBlank()) {
            chain.add("\n.dedupBy($T::$L)", messageName, accessor(message, dedupBy));
        }
        for (Target target : targets) {
            chain.add("\n.signal($T.class, $S)", ClassName.get(target.contract()), target.signal());
            if (!target.key().isBlank()) {
                chain.add("\n.key($T::$L)", messageName, accessor(message, target.key()));
            } else {
                chain.add("\n.businessKey($T::$L)", messageName, accessor(message, target.businessKey()));
            }
            chain.add("\n.onNoMatch($T.$L)", SIGNAL_NO_MATCH, target.onNoMatch());
        }
        chain.add(";\n$]");
        return chain.build();
    }

    /**
     * Returns the name of the method that reads the property: the record component or the {@code get} or {@code is}
     * method of the same name, which the shared scan has already found to exist.
     */
    private String accessor(TypeMirror message, String property) {
        TypeElement element = (TypeElement) ((DeclaredType) message).asElement();
        String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        List<String> candidates = element.getKind() == ElementKind.RECORD
                ? List.of(property, "get" + suffix, "is" + suffix)
                : List.of("get" + suffix, "is" + suffix);
        for (String candidate : candidates) {
            for (ExecutableElement method :
                    ElementFilter.methodsIn(ctx.elements().getAllMembers(element))) {
                if (method.getSimpleName().contentEquals(candidate)
                        && method.getParameters().isEmpty()
                        && method.getReturnType().getKind() != TypeKind.VOID
                        && method.getModifiers().contains(Modifier.PUBLIC)
                        && !method.getModifiers().contains(Modifier.STATIC)) {
                    return candidate;
                }
            }
        }
        return property;
    }
}
