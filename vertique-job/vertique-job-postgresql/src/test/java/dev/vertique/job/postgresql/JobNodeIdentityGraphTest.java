// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.job.JobCoordinator;
import dev.vertique.job.JobNodeId;
import dev.vertique.job.dagger.JobCoordinatorModule;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph test for the job coordinator wiring, built from the production modules with mock
 * infrastructure (no database call is made).
 *
 * <p>The coordinator heartbeat identity and the PostgreSQL repository's claim owner come from one
 * bound {@link JobNodeId} value: dead-node recovery looks up a dead server's executions by its
 * heartbeat {@code server_id}, so the two must be equal. The coordinator also contributes exactly one
 * verticle deployment, however many modules include {@link JobCoordinatorModule}.
 */
class JobNodeIdentityGraphTest {

    private static TestComponent newComponent() {
        return DaggerJobNodeIdentityGraphTest_TestComponent.factory().create(mock(Vertx.class), new JsonObject());
    }

    @Test
    @DisplayName("coordinator serverId equals the repository's claim owner and the bound node id")
    void coordinatorAndRepositoryShareOneNodeIdentity() {
        TestComponent component = newComponent();

        String nodeId = component.nodeId();
        assertEquals(nodeId, component.coordinator().serverId(), "coordinator must heartbeat under the node id");
        assertEquals(nodeId, component.pgJobRepository().nodeId(), "repository must claim under the node id");
        assertSame(nodeId, component.nodeId(), "the node id is scoped once per component");
    }

    @Test
    @DisplayName("the node id is the host name plus an 8-character suffix, distinct per component")
    void nodeIdHasHostnameAndRandomSuffix() {
        String first = newComponent().nodeId();
        String second = newComponent().nodeId();

        assertTrue(first.matches(".+-[0-9a-f]{8}"), "unexpected node id format: " + first);
        assertNotEquals(first, second, "each component (node) must get its own identity");
    }

    @Test
    @DisplayName("one SERVICES deployment drives the coordinator, though two modules include it")
    void coordinatorContributesOneDeployment() {
        Set<VerticleDeployment> deployments = newComponent().deployments();

        assertEquals(1, deployments.size(), "exactly one coordinator deployment");
        VerticleDeployment deployment = deployments.iterator().next();
        assertEquals("job-coordinator", deployment.name());
        assertEquals(LifecyclePhase.SERVICES, deployment.phase());
    }

    @Test
    @DisplayName("the repository rejects a null or blank node identity")
    void repositoryRejectsBlankNodeIdentity() {
        Pool pool = mock(Pool.class);
        PgDbExceptionMapper mapper = mock(PgDbExceptionMapper.class);

        assertThrows(IllegalArgumentException.class, () -> new PgJobRepository(pool, mapper, DbSchemas.empty(), " "));
        assertThrows(IllegalArgumentException.class, () -> new PgJobRepository(pool, mapper, DbSchemas.empty(), null));
    }

    @Singleton
    @Component(
            modules = {
                JobPostgresqlModule.class,
                JobCoordinatorModule.class,
                SchedulingModuleA.class,
                SchedulingModuleB.class,
                ConfigParsingModule.class,
                InfrastructureStubsModule.class
            })
    interface TestComponent {

        @JobNodeId
        String nodeId();

        JobCoordinator coordinator();

        PgJobRepository pgJobRepository();

        Set<VerticleDeployment> deployments();

        @Component.Factory
        interface Factory {
            TestComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** Stands in for a scheduling module (cron or delayed) that includes the coordinator module. */
    @Module(includes = JobCoordinatorModule.class)
    abstract static class SchedulingModuleA {}

    /** A second scheduling module including the coordinator module, as when cron and delayed are both installed. */
    @Module(includes = JobCoordinatorModule.class)
    abstract static class SchedulingModuleB {}

    @Module
    static final class InfrastructureStubsModule {

        @Provides
        @Singleton
        static Pool pool() {
            return mock(Pool.class);
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return mock(PgDbExceptionMapper.class);
        }
    }
}
