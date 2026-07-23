// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown by {@link dev.vertique.workflow.migration.DefaultWorkflowMigrationRegistry#find} when
 * a registered {@link dev.vertique.workflow.migration.WorkflowMigrationHandler} declares a source
 * or target state type that does not match the corresponding plan's runtime state type.
 *
 * <p>This is a configuration error — the handler was registered with an incorrect state-type
 * declaration. The mismatch is detected lazily at {@code find()} time because plan versions may
 * be dynamically activated after the Dagger graph is built, making it impossible to validate
 * state types at construction time.
 *
 * <p>Callers receiving this exception should surface it as an operation invariant violation and
 * fail the migration attempt. The handler must be corrected and redeployed.
 */
public class WorkflowMigrationStateTypeMismatchException extends WorkflowDefinitionException {

    /** The workflow definition id of the migration handler. */
    private final String definitionId;

    /** The source (from) version of the migration. */
    private final long fromVersion;

    /** The target (to) version of the migration. */
    private final long targetVersion;

    /** The state type that the handler declared ({@code sourceStateType} or {@code targetStateType}). */
    private final Class<?> expectedStateType;

    /** The actual state type exposed by the registered plan. */
    private final Class<?> actualStateType;

    /**
     * Creates a new {@code WorkflowMigrationStateTypeMismatchException}.
     *
     * @param definitionId the workflow definition id; must not be {@code null}
     * @param fromVersion the source definition version
     * @param targetVersion the target definition version
     * @param handlerClass the fully-qualified class name of the handler that declared the wrong type
     * @param role whether the mismatch is on the {@code "sourceStateType"} or
     *     {@code "targetStateType"} side
     * @param expectedStateType the state type that the handler declared; must not be {@code null}
     * @param actualStateType the actual state type of the registered plan; must not be {@code null}
     */
    public WorkflowMigrationStateTypeMismatchException(
            String definitionId,
            long fromVersion,
            long targetVersion,
            String handlerClass,
            String role,
            Class<?> expectedStateType,
            Class<?> actualStateType) {
        super("registered handler for ("
                + definitionId + ", v" + fromVersion + " -> v" + targetVersion + ") ["
                + handlerClass + "] declares " + role + "=" + expectedStateType.getName()
                + " but the registered plan stateType=" + actualStateType.getName());
        this.definitionId = definitionId;
        this.fromVersion = fromVersion;
        this.targetVersion = targetVersion;
        this.expectedStateType = expectedStateType;
        this.actualStateType = actualStateType;
    }

    /**
     * Returns the workflow definition id of the migration handler.
     *
     * @return the definition id; never {@code null}
     */
    public String definitionId() {
        return definitionId;
    }

    /**
     * Returns the source version of the migration.
     *
     * @return the from-version
     */
    public long fromVersion() {
        return fromVersion;
    }

    /**
     * Returns the target version of the migration.
     *
     * @return the target-version
     */
    public long targetVersion() {
        return targetVersion;
    }

    /**
     * Returns the state type that the handler declared (the wrong one).
     *
     * @return the declared state type; never {@code null}
     */
    public Class<?> expectedStateType() {
        return expectedStateType;
    }

    /**
     * Returns the actual state type exposed by the registered plan.
     *
     * @return the plan's runtime state type; never {@code null}
     */
    public Class<?> actualStateType() {
        return actualStateType;
    }
}
