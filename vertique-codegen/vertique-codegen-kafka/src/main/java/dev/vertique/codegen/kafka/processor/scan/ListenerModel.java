// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable model of a scanned {@link dev.vertique.kafka.KafkaListener @KafkaListener} type.
 *
 * <p>Represents either a Model 3 router (a {@code @KafkaListener} interface with
 * {@link dev.vertique.kafka.KafkaHandler @KafkaHandler} methods) or a Model 4 direct handler
 * (a {@code @KafkaListener} class implementing {@code KafkaRecordHandler<V>}).
 *
 * <p>The {@link Kind} discriminator mirrors the {@code KafkaBindingMeta.Kind} constant names
 * ({@code ROUTER} / {@code HANDLER}) without introducing a compile-time dependency on the
 * runtime {@code vertique-kafka} module from the processor jar's main-scope classes. The emitter
 * references the runtime {@code KafkaBindingMeta.Kind} solely via a JavaPoet {@code ClassName}
 * string literal — no class-level import is required.
 *
 * @param originType       the {@code @KafkaListener}-annotated type element
 * @param name             binding name from {@code @KafkaListener.name()}
 * @param topic            Kafka topic from {@code @KafkaListener.topic()}
 * @param groupId          consumer group id from {@code @KafkaListener.groupId()}
 * @param errorStrategy    error strategy enum constant name (e.g. {@code "SKIP"})
 * @param commitStrategy   commit strategy enum constant name (e.g. {@code "AUTO"})
 * @param deadLetterTopic  dead-letter topic, or {@code ""} for the default {@code "{topic}.dlq"}
 * @param jsonProfile      JSON mapper profile id from {@code @JsonProfile}, or
 *                         {@code ""} for the framework default ({@code vertx})
 * @param kind             whether this listener is a {@link Kind#ROUTER} or {@link Kind#HANDLER}
 * @param handlerValueType for {@code HANDLER}: the resolved {@code V} from
 *                         {@code KafkaRecordHandler<V>}; {@code null} for {@code ROUTER}
 * @param routes           for {@code ROUTER}: ordered route models; empty for {@code HANDLER}
 */
public record ListenerModel(
        TypeElement originType,
        String name,
        String topic,
        String groupId,
        String errorStrategy,
        String commitStrategy,
        String deadLetterTopic,
        String jsonProfile,
        Kind kind,
        TypeMirror handlerValueType,
        List<RouteModel> routes) {

    // --- Discriminator enum ---

    /**
     * Discriminates between a Model 3 router and a Model 4 direct handler.
     *
     * <p>The constant names intentionally match those of {@code KafkaBindingMeta.Kind} in the
     * runtime module so the emitter can emit the correct constant name via a simple
     * {@link com.palantir.javapoet.ClassName} reference without importing the runtime class.
     */
    public enum Kind {
        /** Model 3 router — a {@code @KafkaListener} interface with {@code @KafkaHandler} methods. */
        ROUTER,

        /** Model 4 direct handler — a {@code @KafkaListener} class implementing {@code KafkaRecordHandler<V>}. */
        HANDLER
    }

    /**
     * Compact constructor that defensively copies the routes list.
     *
     * @param originType       the annotated type element
     * @param name             binding name
     * @param topic            Kafka topic
     * @param groupId          consumer group id
     * @param errorStrategy    error strategy constant name
     * @param commitStrategy   commit strategy constant name
     * @param deadLetterTopic  dead-letter topic
     * @param jsonProfile      JSON mapper profile id, or {@code ""} for the framework default
     * @param kind             binding kind discriminator
     * @param handlerValueType resolved value type for HANDLER kind
     * @param routes           route models for ROUTER kind
     */
    public ListenerModel {
        routes = List.copyOf(routes);
    }
}
