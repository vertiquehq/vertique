// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.workflow.source.SignalSourceForm;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface whose methods carry {@code @WorkflowSignalTarget} as a Kafka signal source.
 *
 * <p>The annotation processor in {@code vertique-codegen-workflow} generates {@code <Interface>_SignalSource}, a
 * {@link KafkaSignalSource} with an {@code @Inject} constructor, and the generated Dagger registrations module binds
 * it into the application's {@code Set<KafkaSignalSource<?>>}. The interface carries no Dagger code and no
 * {@code @KafkaHandler} method. The generated {@code define} writes the topic, the group and the name this annotation
 * states, and then the shared signal stages the targets describe.
 *
 * <p>The generated source commits manually, retries and takes no dead-letter topic, value type or JSON profile of its
 * own, so the annotation has no attribute for them; the message type is the type of the target methods' only
 * parameter, which is also the signal's payload type. A target that addresses its instance by {@code businessKey}
 * names a {@code dedupBy} accessor of the message type.
 */
@SignalSourceForm(KafkaSignalSource.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface KafkaSignalListener {

    /**
     * The name of the consumer, which is also its configuration key under {@code kafka.consumers}.
     *
     * @return the name; must not be blank
     */
    String name();

    /**
     * The topic the source reads.
     *
     * @return the topic; must not be blank
     */
    String topic();

    /**
     * The consumer group of the source.
     *
     * @return the group id; must not be blank
     */
    String groupId();
}
