// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.signal;

import dev.vertique.codegen.AnnotationMirrors;
import dev.vertique.codegen.CodegenContext;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Validates the Kafka-only rules of an interface annotated {@code @KafkaSignalListener}.
 *
 * <p>The signal source generated for the interface commits manually, retries, and takes no JSON profile, so an
 * interface that writes a setting the source would drop is an error instead of a setting that is silently ignored. A
 * {@code @KafkaHandler} method beside a target is an error too, because the generated source consumes the topic and
 * would leave the handler without records. A blank {@code name}, {@code topic} or {@code groupId} is an error, because
 * the source names its consumer, its topic and its consumer group by them. The rules that every transport shares,
 * such as the shape of the target methods, belong to {@link SignalTargetScan} and are not repeated here.
 */
final class SignalListenerValidator {

    private static final String JSON_PROFILE_FQN = "dev.vertique.core.json.JsonProfile";
    private static final String KAFKA_HANDLER_FQN = "dev.vertique.kafka.KafkaHandler";

    private final CodegenContext ctx;

    /**
     * Constructs the validator bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    SignalListenerValidator(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns {@code true} when the interface sets nothing its generated source would drop, declares no
     * {@code @KafkaHandler} method and names its consumer; reports every violation and returns {@code false}
     * otherwise.
     *
     * @param type the annotated interface; must not be {@code null}
     * @param settings what the annotation states; must not be {@code null}
     * @return {@code true} when valid, {@code false} when a diagnostic was emitted
     */
    boolean validate(TypeElement type, ListenerSettings settings) {
        String name = type.getQualifiedName().toString();
        boolean ok = requireText(type, name, "name", settings.name());
        ok &= requireText(type, name, "topic", settings.topic());
        ok &= requireText(type, name, "groupId", settings.groupId());

        if (AnnotationMirrors.isPresent(type, JSON_PROFILE_FQN)) {
            ctx.diagnostics()
                    .error(
                            type,
                            "%s carries @JsonProfile, but the signal source generated for @KafkaSignalListener takes no"
                                    + " JsonProfile; remove it",
                            name);
            ok = false;
        }
        for (Element member : ctx.elements().getAllMembers(type)) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            if (AnnotationMirrors.isPresent(member, JSON_PROFILE_FQN)) {
                ctx.diagnostics()
                        .error(
                                member,
                                "%s.%s carries @JsonProfile, but the signal source generated for @KafkaSignalListener"
                                        + " takes no JsonProfile; remove it",
                                name,
                                member.getSimpleName());
                ok = false;
            }
            if (AnnotationMirrors.isPresent(member, KAFKA_HANDLER_FQN)) {
                ctx.diagnostics()
                        .error(
                                member,
                                "%s.%s is a @KafkaHandler method, but %s is a @KafkaSignalListener: the signal source"
                                        + " generated for it consumes the topic, so a @KafkaHandler method beside a"
                                        + " target would never receive a record",
                                name,
                                member.getSimpleName(),
                                name);
                ok = false;
            }
        }
        return ok;
    }

    private boolean requireText(TypeElement type, String name, String attribute, String value) {
        if (!value.isBlank()) {
            return true;
        }
        ctx.diagnostics()
                .error(
                        type,
                        "%s sets a blank %s on @KafkaSignalListener, but the signal source generated for it names its"
                                + " consumer by it; write a non-blank %s",
                        name,
                        attribute,
                        attribute);
        return false;
    }
}
