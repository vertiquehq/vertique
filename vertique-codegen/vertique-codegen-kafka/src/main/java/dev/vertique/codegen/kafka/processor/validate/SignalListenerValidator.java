// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.validate;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.kafka.processor.KafkaCodegenAnnotations;
import dev.vertique.codegen.kafka.processor.scan.ListenerModel;
import dev.vertique.codegen.kafka.processor.scan.RouteModel;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Validates the Kafka-only rules of a signal listener: a {@link dev.vertique.kafka.KafkaListener @KafkaListener}
 * interface whose methods carry {@code @WorkflowSignalTarget}.
 *
 * <p>The signal source generated for such a listener commits manually and retries, and takes no dead-letter topic,
 * value type or JSON profile, so a listener that writes a setting the source would drop is an error instead of a
 * setting that is silently ignored. A {@code @KafkaHandler} method beside a target is an error too, because the
 * generated source consumes the topic and would leave the handler without records. The rules that every transport
 * shares, such as the shape of the target methods, belong to the shared signal-target scan and are not repeated here.
 *
 * <p>The settings are read from the explicit values of the annotation mirror, not from the defaults: the strategies
 * default to the forbidden {@code SKIP} and {@code AUTO}, which the generated source overrides, so only a value that
 * the listener writes can be an error.
 */
public final class SignalListenerValidator {

    private static final String COMMIT_STRATEGY = "commitStrategy";
    private static final String ERROR_STRATEGY = "errorStrategy";
    private static final String DEAD_LETTER_TOPIC = "deadLetterTopic";
    private static final String VALUE_TYPE = "valueType";
    private static final String JSON_PROFILE_FQN = "dev.vertique.core.json.JsonProfile";
    private static final String MANUAL = "MANUAL";
    private static final String RETRY = "RETRY";

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public SignalListenerValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns {@code true} when the signal listener sets nothing its generated source would drop and declares no
     * {@code @KafkaHandler} method; reports every violation and returns {@code false} otherwise.
     *
     * @param model the scanned model of a signal listener; must not be {@code null}
     * @return {@code true} when valid, {@code false} when a diagnostic was emitted
     */
    public boolean validate(ListenerModel model) {
        TypeElement type = model.originType();
        String name = type.getQualifiedName().toString();
        boolean ok = true;

        Map<? extends ExecutableElement, ? extends AnnotationValue> written =
                listenerMirror(type).getElementValues();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : written.entrySet()) {
            String attribute = entry.getKey().getSimpleName().toString();
            Object value = entry.getValue().getValue();
            switch (attribute) {
                case COMMIT_STRATEGY -> ok &= requireConstant(type, name, attribute, value, MANUAL);
                case ERROR_STRATEGY -> ok &= requireConstant(type, name, attribute, value, RETRY);
                case DEAD_LETTER_TOPIC, VALUE_TYPE -> {
                    ctx.diagnostics()
                            .error(
                                    type,
                                    "%s sets %s, but the signal source generated for a listener with signal targets takes"
                                            + " no %s; remove it",
                                    name,
                                    attribute,
                                    attribute);
                    ok = false;
                }
                default -> {
                    // name, topic and groupId carry into the generated source.
                }
            }
        }

        if (AnnotationMirrors.isPresent(type, JSON_PROFILE_FQN)) {
            ctx.diagnostics()
                    .error(
                            type,
                            "%s carries @JsonProfile, but the signal source generated for a listener with signal targets"
                                    + " takes no JsonProfile; remove it",
                            name);
            ok = false;
        }
        for (Element member : ctx.elements().getAllMembers(type)) {
            if (member.getKind() == ElementKind.METHOD && AnnotationMirrors.isPresent(member, JSON_PROFILE_FQN)) {
                ctx.diagnostics()
                        .error(
                                member,
                                "%s.%s carries @JsonProfile, but the signal source generated for a listener with signal"
                                        + " targets takes no JsonProfile; remove it",
                                name,
                                member.getSimpleName());
                ok = false;
            }
        }

        for (RouteModel route : model.routes()) {
            ctx.diagnostics()
                    .error(
                            route.method(),
                            "%s.%s is a @KafkaHandler method, but %s has signal targets: the signal source generated for"
                                    + " it consumes the topic, so a @KafkaHandler method beside a target would never"
                                    + " receive a record",
                            name,
                            route.method().getSimpleName(),
                            name);
            ok = false;
        }
        return ok;
    }

    private boolean requireConstant(TypeElement type, String name, String attribute, Object value, String allowed) {
        String written = value instanceof VariableElement constant
                ? constant.getSimpleName().toString()
                : String.valueOf(value);
        if (allowed.equals(written)) {
            return true;
        }
        ctx.diagnostics()
                .error(
                        type,
                        "%s sets %s = %s, but the signal source generated for a listener with signal targets forces %s;"
                                + " leave %s out or write %s",
                        name,
                        attribute,
                        written,
                        allowed,
                        attribute,
                        allowed);
        return false;
    }

    private static AnnotationMirror listenerMirror(TypeElement type) {
        return AnnotationMirrors.findByFqn(type, KafkaCodegenAnnotations.KAFKA_LISTENER)
                .orElseThrow(() -> new IllegalStateException(type.getQualifiedName() + " has no @KafkaListener"));
    }
}
