// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.migration.WorkflowMigrationRegistry;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module for the portable workflow engine.
 *
 * <p>This module owns the engine-internal bindings that were previously declared by the dialect
 * module: it binds the four public ops/callback interfaces to the package-private
 * {@link WorkflowEngine}, binds the {@link WorkflowRecoveryBridge} SPI to its package-private
 * implementation, declares the {@code @WorkflowRecorders} multibinding, provides the engine-owned
 * default set of optional intent kinds, and declares the optional {@link WorkflowMigrationRegistry}
 * binding. Dialect modules {@code include} this module so they only contribute the
 * dialect-specific repository / store / runner bindings.
 *
 * <p>The module is {@code public} so a dialect module in another package can {@code include} it;
 * {@link WorkflowEngine} and the collaborators it binds stay package-private (Dagger generates the
 * binding code inside this package, where it can reach them).
 *
 * <p>It {@code include}s {@link WorkflowCoreModule} and {@link ContextRuntimeModule} so the engine's
 * core and durable-context collaborators are available; logging-context wiring is left to the
 * dialect module.
 */
@Module(includes = {WorkflowCoreModule.class, ContextRuntimeModule.class})
public abstract class WorkflowEngineModule {

    /**
     * Binds {@link WorkflowEngine} as the {@link WorkflowOperations} implementation.
     *
     * @param impl the engine singleton
     * @return the engine as the public operations interface
     */
    @Binds
    @Singleton
    abstract WorkflowOperations bindOps(WorkflowEngine impl);

    /**
     * Binds {@link WorkflowEngine} as the {@link TransactionalWorkflowOperations} implementation for
     * the {@link SqlClient}-typed stack.
     *
     * @param impl the engine singleton
     * @return the engine as the transactional operations interface
     */
    @Binds
    @Singleton
    abstract TransactionalWorkflowOperations<SqlClient> bindTxOps(WorkflowEngine impl);

    /**
     * Binds {@link WorkflowEngine} as the {@link TransactionalTimerCallbacks} implementation for the
     * {@link SqlClient}-typed stack.
     *
     * @param impl the engine singleton
     * @return the engine as the timer callbacks interface
     */
    @Binds
    @Singleton
    abstract TransactionalTimerCallbacks<SqlClient> bindTimerCallbacks(WorkflowEngine impl);

    /**
     * Binds {@link WorkflowEngine} as the {@link TransactionalTaskCallbacks} implementation for the
     * {@link SqlClient}-typed stack.
     *
     * @param impl the engine singleton
     * @return the engine as the task callbacks interface
     */
    @Binds
    @Singleton
    abstract TransactionalTaskCallbacks<SqlClient> bindTaskCallbacks(WorkflowEngine impl);

    /**
     * Binds {@link WorkflowRecoveryBridgeImpl} as the {@link WorkflowRecoveryBridge} SPI used by
     * dialect-side branch recovery sweeps.
     *
     * @param impl the recovery bridge singleton
     * @return the bridge implementation as the public SPI
     */
    @Binds
    @Singleton
    abstract WorkflowRecoveryBridge bindRecoveryBridge(WorkflowRecoveryBridgeImpl impl);

    /**
     * Declares the {@code @WorkflowRecorders}-qualified multibinding for side-effect recorders.
     *
     * <p>The {@link SqlClient}-typed multibinding lives here (not in {@code workflow-core}) so that
     * {@code workflow-core} remains SQL-free. Application modules contribute concrete recorders via
     * {@code @Provides @IntoSet @WorkflowRecorders WorkflowSideEffectRecorder<SqlClient>}.
     *
     * @return the (initially empty) set of recorders
     */
    @Multibinds
    @WorkflowRecorders
    abstract Set<WorkflowSideEffectRecorder<SqlClient>> recorders();

    /**
     * Declares {@link WorkflowMigrationRegistry} as an optional binding.
     *
     * <p>When the application installs {@code WorkflowMigrationModule}, Dagger satisfies the
     * {@code Optional<WorkflowMigrationRegistry>} injection point in {@link WorkflowEngine} with the
     * registered registry. Without that module the optional is empty and {@code migrate} fails with
     * {@link UnsupportedOperationException}, keeping migration opt-in.
     *
     * @return the optional migration registry binding declaration
     */
    @BindsOptionalOf
    abstract WorkflowMigrationRegistry optionalMigrationRegistry();

    /**
     * Provides the set of {@link IntentKind} values that {@link RecorderRouter} treats as optional:
     * if no recorder is registered for an optional kind, the router returns an empty result instead
     * of failing.
     *
     * <p>Delegates to {@link WorkflowEngineFactory#DEFAULT_OPTIONAL_INTENT_KINDS} — the single source
     * of truth shared with the non-Dagger {@link WorkflowEngineFactory#create} assembly path.
     *
     * @return the set of optional intent kinds
     */
    @Provides
    @Singleton
    @OptionalIntentKinds
    static Set<IntentKind> optionalKinds() {
        return WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS;
    }

    /**
     * Contributes the {@link WorkflowReminderComposeValidator} into the
     * {@code Set<ComposeValidator>} multibinding so the framework materializes it in the
     * {@link dev.vertique.core.lifecycle.LifecyclePhase#VALIDATE VALIDATE} lifecycle phase,
     * forcing its construction-time validation check with no app code referencing it.
     *
     * @param impl the compose validator, constructed via its {@code @Inject} constructor
     * @return the validator as a {@link ComposeValidator}
     */
    @Provides
    @Singleton
    @IntoSet
    static ComposeValidator reminderComposeValidator(WorkflowReminderComposeValidator impl) {
        return impl;
    }
}
