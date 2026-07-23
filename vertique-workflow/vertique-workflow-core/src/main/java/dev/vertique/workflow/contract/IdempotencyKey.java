// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} parameter in a {@link WorkflowStart} method as the source of the
 * idempotency key for the start command.
 *
 * <p>This annotation is one of two valid idempotency-key sources on a {@code @WorkflowStart}
 * method. The other is a payload type that implements {@link IdempotencyKeyed}. If both are
 * present, the annotated parameter takes precedence.
 *
 * <p>Proxy creation fails with {@code WorkflowProxyContractException} if neither source is
 * present on the {@code @WorkflowStart} method.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IdempotencyKey {}
