// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.job.JobExecutionStateTransitionListener;
import dev.vertique.job.JobRepository;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph smoke test for {@link JobPostgresqlModule}.
 *
 * <p>Composes a minimal test {@link dagger.Component} from {@link JobPostgresqlModule} and a
 * {@link TestStubsModule} that provides the infrastructure dependencies ({@link Pool} and
 * {@link PgDbExceptionMapper}) as Mockito mocks — no real database connection is made.
 *
 * <p>Two invariants are asserted:
 * <ol>
 *   <li>The unqualified {@link JobRepository} binding resolves to a {@link NotifyingJobRepository}
 *       — proving the decorator is wired correctly as the public binding.</li>
 *   <li>The {@code Set<JobExecutionStateTransitionListener>} resolves as an <em>empty</em> set
 *       when no listener has been contributed — proving that {@link JobPostgresqlModule}'s
 *       {@code includes = JobModule.class} satisfies the {@code @Multibinds} declaration without
 *       requiring external contributions.</li>
 * </ol>
 */
class JobPostgresqlGraphSmokeTest {

    /** Shared component; the graph shape is constant across tests, so it is built once. */
    private static TestComponent component;

    @BeforeAll
    static void buildGraph() {
        component = DaggerJobPostgresqlGraphSmokeTest_TestComponent.create();
    }

    @Test
    @DisplayName("JobRepository resolves to NotifyingJobRepository (decorator wired)")
    void jobRepositoryIsNotifyingDecorator() {
        JobRepository repo = component.jobRepository();
        assertInstanceOf(
                NotifyingJobRepository.class, repo, "unqualified JobRepository must resolve to NotifyingJobRepository");
    }

    @Test
    @DisplayName("Set<JobExecutionStateTransitionListener> resolves empty when no listener is contributed")
    void listenerSetResolvesEmpty() {
        Set<JobExecutionStateTransitionListener> listeners = component.listeners();
        assertTrue(listeners.isEmpty(), "listener set must be empty when no @IntoSet contribution is present");
    }

    // --- Test Dagger component ---

    /**
     * Minimal Dagger component that exercises the {@link JobPostgresqlModule} wiring.
     *
     * <p>Exposes both the unqualified {@link JobRepository} (expected to be
     * {@link NotifyingJobRepository}) and the listener set (expected to be empty).
     */
    @Singleton
    @Component(modules = {JobPostgresqlModule.class, TestStubsModule.class})
    interface TestComponent {

        /**
         * Returns the fully-wired {@link JobRepository}.
         *
         * @return the repository; non-null when the graph resolves
         */
        JobRepository jobRepository();

        /**
         * Returns the multibinding set of {@link JobExecutionStateTransitionListener}s.
         *
         * @return the listener set; empty when no listener is contributed
         */
        Set<JobExecutionStateTransitionListener> listeners();
    }

    // --- Minimal stubs for PgJobRepository's @Inject constructor ---

    /**
     * Provides mock infrastructure objects that satisfy {@link PgJobRepository}'s
     * {@code @Inject} constructor without requiring a real PostgreSQL connection.
     *
     * <p>The graph is resolved (all bindings satisfied) but no database method is ever called,
     * so Mockito mocks are sufficient.
     */
    @Module
    static final class TestStubsModule {

        /**
         * Provides a Mockito mock {@link Pool} to satisfy the {@link PgJobRepository} constructor.
         *
         * @return a mock pool; no real DB calls are made
         */
        @Provides
        @Singleton
        static Pool pool() {
            return mock(Pool.class);
        }

        /**
         * Provides a Mockito mock {@link PgDbExceptionMapper} to satisfy the
         * {@link PgJobRepository} constructor.
         *
         * @return a mock exception mapper
         */
        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return mock(PgDbExceptionMapper.class);
        }
    }
}
