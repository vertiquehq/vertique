// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} parameter in a {@link WorkflowSignal} method as the source of the
 * signal dedup key.
 *
 * <p>This annotation is one of two valid dedup-key sources on a {@code @WorkflowSignal} method.
 * The other is a payload type that implements {@link SignalDedupKeyed}. If both are present, the
 * annotated parameter takes precedence.
 *
 * <p>Proxy creation fails with {@code WorkflowProxyContractException} if neither source is
 * present on the {@code @WorkflowSignal} method.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SignalDedupKey {}
