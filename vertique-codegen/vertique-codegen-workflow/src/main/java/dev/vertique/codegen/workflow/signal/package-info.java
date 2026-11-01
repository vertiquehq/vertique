// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processing for signal sources: the shared scan of {@code @WorkflowSignalTarget}, the processor of
 * {@code @KafkaSignalListener} that generates a {@code KafkaSignalSource}, and the processor that reports a target on a
 * type no processor turns into a source.
 *
 * <p>{@link dev.vertique.codegen.workflow.signal.SignalTargetScan} is an INTERNAL seam, outside the maturity promise of
 * this module, that the Camel processor of another repository also calls, so that every transport applies one rule set.
 */
package dev.vertique.codegen.workflow.signal;
