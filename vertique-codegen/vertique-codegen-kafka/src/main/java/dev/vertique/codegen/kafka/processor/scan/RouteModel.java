// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable model of a single {@code @KafkaHandler} route within a {@code @KafkaListener} router
 * interface.
 *
 * <p>Captures the match rule attributes from {@link dev.vertique.kafka.KafkaHandler @KafkaHandler},
 * the optional {@link dev.vertique.kafka.DispatchTo @DispatchTo} service and operation, and the
 * route-level payload type resolved from the handler method's single payload parameter.
 *
 * @param method           the {@code @KafkaHandler}-annotated method element
 * @param matchHeader      header name to match, or {@code ""} when not matching by header
 * @param matchProperty    JSON property name to match, or {@code ""} when not matching by property
 * @param matchValue       value to compare; only meaningful when {@code matchHeader} or
 *                         {@code matchProperty} is non-empty
 * @param defaultHandler   {@code true} for the catch-all route; mutually exclusive with header/property matching
 * @param routeValueType   per-route deserialization target resolved from the method's payload param;
 *                         {@code null} when the method has no payload parameter (treated as
 *                         {@code Void.class} in the emitter)
 * @param targetService    the {@code @DispatchTo} service {@link TypeMirror}, or {@code null} when absent
 * @param targetOperation  the {@code @DispatchTo} operation string, or {@code null} when absent
 */
public record RouteModel(
        ExecutableElement method,
        String matchHeader,
        String matchProperty,
        String matchValue,
        boolean defaultHandler,
        TypeMirror routeValueType,
        TypeMirror targetService,
        String targetOperation) {}
