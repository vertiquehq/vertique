// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.schema;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for all workflow step variants in a definition document.
 *
 * <p>Jackson uses the {@code type} JSON/YAML property to select the concrete record to
 * deserialize into. The {@code type} property is not visible on the deserialized instances
 * (it is consumed by the polymorphic dispatch and not mapped to a field).
 *
 * <p>The sealed hierarchy prevents unknown concrete types at the Java level; Jackson additionally
 * rejects unknown type names at parse time when the mapper is configured with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = true}.
 *
 * <p>No validation is performed here — step-level semantic checks are the responsibility of the
 * validator in {@code dev.vertique.workflow.definition.validator}.
 *
 * <p>Permitted subtypes:
 * <ul>
 *   <li>{@code "service"} → {@link ServiceStep}
 *   <li>{@code "wait-signal"} → {@link WaitSignalStep}
 *   <li>{@code "timer"} → {@link TimerStep}
 *   <li>{@code "human-task"} → {@link HumanTaskStep}
 *   <li>{@code "decision"} → {@link DecisionStep}
 *   <li>{@code "complete"} → {@link CompleteStep}
 *   <li>{@code "fail"} → {@link FailStep}
 *   <li>{@code "compensation"} → {@link CompensationStep}
 *   <li>{@code "fork"} → {@link ForkStep}
 *   <li>{@code "join"} → {@link JoinStep}
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = false)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ServiceStep.class, name = "service"),
    @JsonSubTypes.Type(value = WaitSignalStep.class, name = "wait-signal"),
    @JsonSubTypes.Type(value = TimerStep.class, name = "timer"),
    @JsonSubTypes.Type(value = HumanTaskStep.class, name = "human-task"),
    @JsonSubTypes.Type(value = DecisionStep.class, name = "decision"),
    @JsonSubTypes.Type(value = CompleteStep.class, name = "complete"),
    @JsonSubTypes.Type(value = FailStep.class, name = "fail"),
    @JsonSubTypes.Type(value = CompensationStep.class, name = "compensation"),
    @JsonSubTypes.Type(value = ForkStep.class, name = "fork"),
    @JsonSubTypes.Type(value = JoinStep.class, name = "join"),
})
public sealed interface StepNode
        permits ServiceStep,
                WaitSignalStep,
                TimerStep,
                HumanTaskStep,
                DecisionStep,
                CompleteStep,
                FailStep,
                CompensationStep,
                ForkStep,
                JoinStep {

    /**
     * Returns the unique step identifier within the workflow definition.
     *
     * @return the step id; never {@code null}
     */
    String id();
}
