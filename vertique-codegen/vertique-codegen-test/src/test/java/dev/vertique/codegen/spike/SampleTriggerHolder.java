// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.spike;

/**
 * Sample annotated element fixture for the CODEGEN-013 Phase 0 slice 0.3 annotation-literal
 * feasibility spike (OQ-2).
 *
 * <p>The class carries a single {@link SampleTrigger} with concrete non-default values for all three
 * member kinds. The spike test reflectively obtains this annotation instance ({@code
 * SampleTriggerHolder.class.getAnnotation(SampleTrigger.class)}) and compares it against a
 * hand-written {@code SampleTriggerLiteral} constructed with the identical values.
 */
@SampleTrigger(name = "x", order = 5, type = String.class)
final class SampleTriggerHolder {

    private SampleTriggerHolder() {}
}
