// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method in a {@link WorkflowContract} interface as the workflow start operation.
 *
 * <p>There must be exactly one {@code @WorkflowStart} method per contract. The method must return
 * {@code Future<WorkflowInstanceId>}. An idempotency key source is required — either a
 * {@code @IdempotencyKey String} parameter or a payload type that implements {@link IdempotencyKeyed}.
 *
 * <p>The method may optionally include parameters annotated with {@link BusinessKey} and
 * {@link SubjectRef}. When both annotation and payload-side interface are present for the same
 * field, the annotation wins.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowStart {}
