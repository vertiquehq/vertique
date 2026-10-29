// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

/**
 * INTERNAL: raised when an executor bind finds a security context or a deferred-execution origin already bound on the
 * segment's context. An executor binds only durable context, so such a value means the executor's environment is
 * wrong, not the instance: the segment rolls back and is retried with a backoff, and the instance is never failed. The
 * message names the instance and the command and never a context value.
 */
public final class ExecutorContextNotCleanException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param instance the id of the instance the command belongs to
     * @param command  the id of the command whose operation was refused
     */
    public ExecutorContextNotCleanException(String instance, long command) {
        super("The executor context for workflow instance " + instance + ", command " + command
                + " already has a security context or a deferred-execution origin bound; the operation was not run");
    }
}
