// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marker annotation for types carried in {@link DispatchMetadata#dispatchContext()} that can be
 * auto-injected into {@link dev.vertique.services.ServiceHandler} method parameters.
 *
 * <p>Types annotated with {@code @DispatchContextValue} are recognized as injectable by
 * the service dispatch framework. The dispatch context map uses the type's FQCN as key.
 *
 * <p>Example:
 * <pre>{@code
 * @DispatchContextValue
 * public record KafkaRecordContext(String topic, int partition, long offset) {}
 * }</pre>
 *
 * <p>Handler methods can then declare this type as a parameter:
 * <pre>{@code
 * public Future<Void> handle(MyEvent event, KafkaRecordContext kafka) { ... }
 * }</pre>
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface DispatchContextValue {}
