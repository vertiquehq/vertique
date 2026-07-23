// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Immutable model of a scanned {@code @DelayedJobContract} interface.
 *
 * <p>Captures the contract's annotation attributes and the resolved payload type parameter
 * {@code P} from {@code DelayedJobClient<P>}. The payload type is {@code null} when it could not be
 * resolved (e.g. a raw {@code DelayedJobClient} or a forwarding type variable); validation reports
 * that as an error before any proxy is emitted.
 *
 * @param contractType the {@code @DelayedJobContract} interface element
 * @param name         the contract {@code name()} attribute
 * @param maxAttempts  the contract {@code maxAttempts()} attribute
 * @param queue        the contract {@code queue()} attribute
 * @param priority     the contract {@code priority()} attribute
 * @param payloadType  the resolved {@code DelayedJobClient<P>} payload type, or {@code null} when
 *                     unresolvable
 */
public record DelayedJobContractModel(
        TypeElement contractType, String name, int maxAttempts, String queue, int priority, TypeMirror payloadType) {}
