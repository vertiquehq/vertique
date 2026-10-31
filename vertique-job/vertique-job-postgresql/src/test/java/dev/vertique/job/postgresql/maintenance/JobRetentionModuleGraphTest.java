// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.lifecycle.ComposeValidator;
import dev.vertique.job.cron.CronJob;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.OverlapPolicy;
import dev.vertique.job.cron.dagger.CronPersistenceMarker;
import dev.vertique.job.postgresql.PgJobRepository;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.Services;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dagger graph test for {@link JobRetentionModule}: the cron entry point reaches the {@code @Services}
 * set, the compose validator reaches the validator set, the configuration is parsed from
 * {@code job.retention}, and the cron job is the cluster-singleton, skip-on-overlap job the module
 * documents. The infrastructure bindings are mocks; no database is touched.
 */
@DisplayName("JobRetentionModule")
class JobRetentionModuleGraphTest {

    @Test
    @DisplayName("contributes the cron entry point to the @Services set and the validator to the validator set")
    void contributesServicesAndValidator() {
        TestComponent component = DaggerJobRetentionModuleGraphTest_TestComponent.builder()
                .stubs(new Stubs(new JsonObject()))
                .build();

        assertTrue(component.services().stream().anyMatch(JobMaintenanceCron.class::isInstance));
        assertTrue(component.validators().stream().anyMatch(JobRetentionComposeValidator.class::isInstance));
    }

    @Test
    @DisplayName("parses job.retention and falls back to the defaults when the section is absent")
    void parsesConfig() {
        JobRetentionConfig defaults = DaggerJobRetentionModuleGraphTest_TestComponent.builder()
                .stubs(new Stubs(new JsonObject()))
                .build()
                .config();
        assertEquals(604_800L, defaults.retentionSeconds());
        assertEquals(1_000, defaults.batchSize());

        JobRetentionConfig configured = DaggerJobRetentionModuleGraphTest_TestComponent.builder()
                .stubs(new Stubs(new JsonObject()
                        .put(
                                "job",
                                new JsonObject()
                                        .put(
                                                "retention",
                                                new JsonObject()
                                                        .put("retentionSeconds", 3600)
                                                        .put("batchSize", 50)))))
                .build()
                .config();
        assertEquals(3_600L, configured.retentionSeconds());
        assertEquals(50, configured.batchSize());
    }

    @Test
    @DisplayName("rejects a retention that is not positive when the configuration is built")
    void rejectsInvalidConfig() {
        TestComponent component = DaggerJobRetentionModuleGraphTest_TestComponent.builder()
                .stubs(new Stubs(new JsonObject()
                        .put("job", new JsonObject().put("retention", new JsonObject().put("retentionSeconds", 0)))))
                .build();

        assertThrows(ConfigurationException.class, component::config);
    }

    @Test
    @DisplayName("the cron job is a SINGLE_INSTANCE, SKIP job on a stable service operation")
    void cronJobShape() throws NoSuchMethodException {
        Method method = JobMaintenanceCron.class.getMethod("purgeFinishedExecutions");
        CronJob cron = method.getAnnotation(CronJob.class);

        assertEquals("job-execution-retention", cron.id());
        assertEquals("0 */15 * * * *", cron.cron());
        assertEquals(ExecutionMode.SINGLE_INSTANCE, cron.mode());
        assertEquals(OverlapPolicy.SKIP, cron.overlapPolicy());
        assertInstanceOf(ServiceContract.class, JobMaintenanceContract.class.getAnnotation(ServiceContract.class));
    }

    /** Test graph: the module under test plus mocked infrastructure. */
    @Singleton
    @Component(modules = {JobRetentionModule.class, Stubs.class})
    interface TestComponent {

        JobRetentionConfig config();

        @Services
        Set<Object> services();

        Set<ComposeValidator> validators();
    }

    /** The bindings the module expects from the rest of the application. */
    @Module
    static final class Stubs {

        private final JsonObject config;

        Stubs(JsonObject config) {
            this.config = config;
        }

        @Provides
        @VertxConfig
        JsonObject config() {
            return config;
        }

        @Provides
        static ConfigParser parser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }

        @Provides
        @Singleton
        static PgJobRepository repository() {
            return mock(PgJobRepository.class);
        }

        @Provides
        static CronJobRegistrar registrar() {
            return mock(CronJobRegistrar.class);
        }

        @Provides
        static CronScheduler scheduler() {
            return mock(CronScheduler.class);
        }

        @Provides
        static CronPersistenceMarker marker() {
            return mock(CronPersistenceMarker.class);
        }
    }
}
