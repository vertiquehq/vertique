// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.query;

import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import jakarta.annotation.Nullable;
import java.util.Objects;

/**
 * Public query filter for listing workflow instances via {@link WorkflowInstanceQueryService#list}.
 *
 * <p>This record supersedes the former postgresql-local {@code WorkflowFilter} type, which has
 * been removed. The name {@code WorkflowInstanceQuery} is chosen to avoid collision with the
 * {@link dev.vertique.workflow.contract.WorkflowQuery} annotation, which marks query-method
 * declarations on contract interfaces.
 *
 * <p>All nullable fields are optional. A {@code null} value means the corresponding filter is not
 * applied. Multiple non-null fields produce a conjunction (AND). The {@code includeArchived} flag
 * controls whether soft-deleted (archived) instances are included; defaults to {@code false} so
 * that routine queries only see live (non-archived) rows.
 *
 * @param status optional status to filter by; {@code null} means no filter
 * @param businessKey optional business key to filter by (exact match); {@code null} means no filter
 * @param subjectType optional subject type component of a subject-reference filter;
 *     {@code null} means no filter
 * @param subjectId optional subject id component of a subject-reference filter;
 *     {@code null} means no filter
 * @param subjectVersion optional subject version to filter by; {@code null} means no filter.
 *     Supports version-aware queries ({@code FR-WF-123}) — only instances whose
 *     {@code subject_version} column equals this value are returned.
 * @param definitionId optional definition id to filter by; {@code null} means no filter
 * @param includeArchived when {@code true}, archived instances (with a non-null {@code archived_at})
 *     are included in the result; defaults to {@code false}
 */
public record WorkflowInstanceQuery(
        @Nullable WorkflowStatus status,
        @Nullable String businessKey,
        @Nullable String subjectType,
        @Nullable String subjectId,
        @Nullable String subjectVersion,
        @Nullable String definitionId,
        boolean includeArchived) {

    // --- Factory methods ---

    /**
     * Returns a query that matches all workflow instances (no criteria applied, excludes archived).
     *
     * @return an empty query with {@code includeArchived = false}
     */
    public static WorkflowInstanceQuery none() {
        return new WorkflowInstanceQuery(null, null, null, null, null, null, false);
    }

    /**
     * Returns a query that matches instances with the given status (excludes archived).
     *
     * @param status the status to filter by
     * @return a query for the given status
     */
    public static WorkflowInstanceQuery byStatus(WorkflowStatus status) {
        return new WorkflowInstanceQuery(status, null, null, null, null, null, false);
    }

    /**
     * Returns a query that matches instances with the given definition id (excludes archived).
     *
     * @param definitionId the definition id to filter by
     * @return a query for the given definition id
     */
    public static WorkflowInstanceQuery byDefinitionId(String definitionId) {
        return new WorkflowInstanceQuery(null, null, null, null, null, definitionId, false);
    }

    /**
     * Returns a query that matches instances associated with the given subject reference.
     *
     * <p>Populates {@code subjectType}, {@code subjectId}, and {@code subjectVersion} from the
     * supplied {@link WorkflowSubjectRef}. If the ref carries a {@code null} version, the
     * {@code subjectVersion} field is left {@code null} (no version filter applied).
     *
     * @param ref the subject reference to filter by; must not be {@code null}
     * @return a query for instances matching the given subject reference
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    public static WorkflowInstanceQuery bySubject(WorkflowSubjectRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        return new WorkflowInstanceQuery(null, null, ref.type(), ref.id(), ref.version(), null, false);
    }

    /**
     * Returns a query that matches instances with the given subject type (excludes archived).
     *
     * <p>Only {@code subjectType} is set; {@code subjectId} and {@code subjectVersion} remain
     * {@code null}. Use {@link #bySubject(WorkflowSubjectRef)} when all three subject components
     * are known.
     *
     * @param subjectType the subject type string to filter by
     * @return a query for the given subject type
     */
    public static WorkflowInstanceQuery bySubjectType(String subjectType) {
        return new WorkflowInstanceQuery(null, null, subjectType, null, null, null, false);
    }

    // --- Fluent with* setters ---

    /**
     * Returns a copy of this query with the {@code status} field replaced.
     *
     * @param status the status to filter by; {@code null} removes the filter
     * @return a new {@code WorkflowInstanceQuery} with the updated field and all others preserved
     */
    public WorkflowInstanceQuery withStatus(@Nullable WorkflowStatus status) {
        return new WorkflowInstanceQuery(
                status, businessKey, subjectType, subjectId, subjectVersion, definitionId, includeArchived);
    }

    /**
     * Returns a copy of this query with the {@code businessKey} field replaced.
     *
     * @param businessKey the business key to filter by; {@code null} removes the filter
     * @return a new {@code WorkflowInstanceQuery} with the updated field and all others preserved
     */
    public WorkflowInstanceQuery withBusinessKey(@Nullable String businessKey) {
        return new WorkflowInstanceQuery(
                status, businessKey, subjectType, subjectId, subjectVersion, definitionId, includeArchived);
    }

    /**
     * Returns a copy of this query with the {@code subjectType} field replaced.
     *
     * @param subjectType the subject type to filter by; {@code null} removes the filter
     * @return a new {@code WorkflowInstanceQuery} with the updated field and all others preserved
     */
    public WorkflowInstanceQuery withSubjectType(@Nullable String subjectType) {
        return new WorkflowInstanceQuery(
                status, businessKey, subjectType, subjectId, subjectVersion, definitionId, includeArchived);
    }

    /**
     * Returns a copy of this query with the {@code subjectId} field replaced.
     *
     * @param subjectId the subject id to filter by; {@code null} removes the filter
     * @return a new {@code WorkflowInstanceQuery} with the updated field and all others preserved
     */
    public WorkflowInstanceQuery withSubjectId(@Nullable String subjectId) {
        return new WorkflowInstanceQuery(
                status, businessKey, subjectType, subjectId, subjectVersion, definitionId, includeArchived);
    }

    /**
     * Returns a copy of this query with the {@code subjectVersion} field replaced.
     *
     * <p>Setting a non-null version restricts results to instances whose {@code subject_version}
     * column equals this value (version-aware query, {@code FR-WF-123}). Pass {@code null} to
     * remove the version filter.
     *
     * <p><b>Performance note:</b> a {@code subjectVersion} predicate is only efficient when
     * combined with {@code subjectType} (and ideally {@code subjectId}). The partial composite
     * index {@code idx_workflow_instances_subject_versioned} is keyed on {@code (subject_type,
     * subject_id, subject_version)} — a query that filters by {@code subjectVersion} alone
     * cannot use the leftmost index columns and degrades to a sequential scan over versioned
     * rows. Prefer {@link #bySubject(WorkflowSubjectRef)} when possible.
     *
     * @param subjectVersion the subject version to filter by; {@code null} removes the filter
     * @return a new {@code WorkflowInstanceQuery} with the updated field and all others preserved
     */
    public WorkflowInstanceQuery withSubjectVersion(@Nullable String subjectVersion) {
        return new WorkflowInstanceQuery(
                status, businessKey, subjectType, subjectId, subjectVersion, definitionId, includeArchived);
    }

    /**
     * Returns a copy of this query with the {@code definitionId} field replaced.
     *
     * @param definitionId the definition id to filter by; {@code null} removes the filter
     * @return a new {@code WorkflowInstanceQuery} with the updated field and all others preserved
     */
    public WorkflowInstanceQuery withDefinitionId(@Nullable String definitionId) {
        return new WorkflowInstanceQuery(
                status, businessKey, subjectType, subjectId, subjectVersion, definitionId, includeArchived);
    }

    /**
     * Returns a copy of this query with the {@code includeArchived} flag replaced.
     *
     * @param includeArchived {@code true} to include archived instances; {@code false} to exclude
     * @return a new {@code WorkflowInstanceQuery} with the updated flag and all other fields preserved
     */
    public WorkflowInstanceQuery withIncludeArchived(boolean includeArchived) {
        return new WorkflowInstanceQuery(
                status, businessKey, subjectType, subjectId, subjectVersion, definitionId, includeArchived);
    }
}
