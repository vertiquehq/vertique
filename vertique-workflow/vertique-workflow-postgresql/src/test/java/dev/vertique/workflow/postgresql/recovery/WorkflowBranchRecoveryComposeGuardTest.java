// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.recovery;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compile-testing guard proving that the {@link WorkflowBranchRecoveryModule} {@code @Services}
 * factory's {@code CronPersistenceMarker} parameter genuinely fails Dagger code generation when
 * an AppComponent installs {@link WorkflowBranchRecoveryModule} without
 * {@code CronPersistenceModule} on the graph.
 *
 * <p>The runtime {@link WorkflowBranchRecoveryCronWiringTest} only exercises the positive path
 * (the impl is registered as a {@code @CronJob}). Without this test the architectural safety
 * check — preventing a deployment from accidentally wiring the cluster-singleton recovery cron
 * without the persistence-flavour cron module — could regress silently.
 *
 * <p>This test runs the Dagger annotation processor (
 * {@code dagger.internal.codegen.ComponentProcessor}) against a tiny test {@code @Component}
 * fixture and asserts:
 * <ul>
 *   <li>Compilation fails when {@code CronPersistenceModule} is absent.</li>
 *   <li>The failure diagnostic explicitly names {@code CronPersistenceMarker} — proving the
 *       missing binding is the marker, not some unrelated dependency.</li>
 * </ul>
 *
 * <p>The fixture deliberately installs only {@link WorkflowBranchRecoveryModule} (no
 * {@code CronModule} or {@code CronPersistenceModule}) to isolate the marker-missing signal
 * from {@code CronModule}'s many transitive dependencies; the assertion is on the diagnostic
 * mentioning {@code CronPersistenceMarker}, so any number of additional missing bindings is
 * irrelevant. It exposes a {@code Set<Object>} qualified with {@code @Services} as a provision
 * method so Dagger is forced to traverse the {@code @Services} multibinding (where the
 * {@code WorkflowBranchRecoveryModule} factory contributes the impl), which is what actually
 * triggers the marker dependency. Without that provision method Dagger may prune the unused
 * factory and never observe the missing marker.
 *
 * <p>The positive composition path (with {@code CronPersistenceModule}) is exercised end-to-end
 * by the example application's {@code AppComponent} on every CI build, so we do not duplicate it
 * here as a second compile-testing fixture.
 */
@DisplayName("WorkflowBranchRecoveryModule compose-guard (Dagger compile-testing)")
class WorkflowBranchRecoveryComposeGuardTest {

    @Test
    @DisplayName("@Component installing WorkflowBranchRecoveryModule without CronPersistenceModule fails to compile,"
            + " naming CronPersistenceMarker")
    void componentMissingCronPersistenceMarkerFailsCompile() {
        var result = ProcessorTestHarness.run(
                java.util.List.of(new dagger.internal.codegen.ComponentProcessor()),
                SourceFiles.inline("test.compose.guard.FailFixture", """
                                package test.compose.guard;

                                import dagger.Component;
                                import dev.vertique.services.Services;
                                import dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryModule;
                                import jakarta.inject.Singleton;
                                import java.util.Set;

                                /**
                                 * Test-only @Component that installs WorkflowBranchRecoveryModule WITHOUT
                                 * CronPersistenceModule (no CronPersistenceMarker binding available).
                                 *
                                 * <p>The @Services Set<Object> provision method forces Dagger to traverse
                                 * the @Services multibinding — which is where WorkflowBranchRecoveryModule
                                 * contributes its factory, and where the missing CronPersistenceMarker
                                 * dependency surfaces as a compile error.
                                 */
                                @Singleton
                                @Component(modules = WorkflowBranchRecoveryModule.class)
                                interface FailFixture {
                                    @Services
                                    Set<Object> services();
                                }
                                """));

        result.assertFailed().assertErrorMessage("CronPersistenceMarker");
    }
}
