// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Task-domain SPI types for the human-task wait node (cycle 3).
 *
 * <p>Contains the {@link TaskStore} SPI, status enumerations, runtime-literal assignment
 * ({@link TaskAssignment}), query filter, result records, and the immutable {@link TaskRecord}
 * snapshot. Action APIs ({@code TaskService} / transactional variants) live in
 * {@code vertique-workflow-tasks} in this same package name — a classpath split package; only this
 * module ships {@code package-info.java} (JPMS redesign deferred).
 */
package dev.vertique.workflow.tasks;
