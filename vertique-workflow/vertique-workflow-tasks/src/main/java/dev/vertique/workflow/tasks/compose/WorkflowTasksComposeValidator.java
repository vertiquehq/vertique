// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks.compose;

import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.workflow.tasks.TaskService;
import dev.vertique.workflow.tasks.TaskStore;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Startup validator that asserts the application's Dagger graph is correctly composed for
 * workflow task support.
 *
 * <h2>Validation contract</h2>
 *
 * <p>This validator uses the <em>constructible-as-validation</em> pattern: the act of successfully
 * constructing this object — with both {@link TaskService} and {@link TaskStore} injected — is
 * itself the validation. If the {@link TaskStore} binding is absent (i.e., the application forgot
 * to include {@code WorkflowPostgresqlModule}), Dagger reports a missing binding at compile time.
 * If the {@link TaskService} binding is absent, that is also a missing binding error.
 *
 * <p>Because Dagger performs all binding validation at code-generation time, no runtime
 * {@link IllegalStateException} is thrown from this constructor in normal cases. The injected
 * parameters are unused after construction; they exist purely to establish the required dependency
 * edges in the Dagger graph.
 *
 * <h2>How to use</h2>
 *
 * <p>Applications that include {@link dev.vertique.workflow.tasks.di.WorkflowTasksModule} should
 * expose a {@code WorkflowTasksComposeValidator} accessor on their {@code AppComponent} and call
 * it during {@code MainVerticle.start()} to fail fast if the SPI bindings are missing. This is
 * belt-and-suspenders on top of the Dagger compile-time check and catches runtime misconfiguration
 * where the component is constructed lazily.
 *
 * <p>This validator implements {@link ComposeValidator} so the framework can materialize it in the
 * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase (forcing its
 * construction, hence its validation) without any app code referencing it. The marker adds no
 * methods and does not change how validation runs.
 */
@Singleton
public final class WorkflowTasksComposeValidator implements ComposeValidator {

    /**
     * Validates the application's Dagger graph for workflow task support.
     *
     * <p>Both parameters are required by Dagger:
     * <ul>
     *   <li>{@code taskService} — proves the {@link TaskService} binding (and its transitive
     *       dependencies: {@link dev.vertique.workflow.tasks.TransactionalTaskService},
     *       {@link dev.vertique.workflow.ops.TransactionalTaskCallbacks}, and
     *       {@link io.vertx.sqlclient.Pool}) are present in the graph.</li>
     *   <li>{@code taskStore} — proves the {@link TaskStore}{@code <SqlClient>} SPI implementation
     *       is wired, which requires {@code WorkflowPostgresqlModule} (or equivalent) to be
     *       included in the component.</li>
     * </ul>
     *
     * @param taskService the public task service; injected for its binding side-effect only
     * @param taskStore the task storage SPI implementation; injected for its binding side-effect
     *     only
     */
    @Inject
    public WorkflowTasksComposeValidator(TaskService taskService, TaskStore<SqlClient> taskStore) {
        // Both parameters are intentionally unused after construction. Their presence in the
        // constructor forces Dagger to verify that both bindings exist in the component graph.
        // This is the constructible-as-validation pattern: if either binding is missing the
        // component fails to compile (Dagger) rather than failing at runtime.
    }
}
