// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dagger.BindsInstance;
import dagger.BindsOptionalOf;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.db.DbModule;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.flyway.CredentialSource;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.flyway.FlywayMigrationRunner;
import dev.vertique.db.flyway.FlywayMode;
import dev.vertique.db.flyway.MigrationInstanceResult;
import dev.vertique.db.flyway.MigrationInstanceView;
import dev.vertique.db.flyway.MigrationReport;
import dev.vertique.db.flyway.MigrationRunException;
import dev.vertique.db.flyway.MigrationStatus;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.postgresql.InboxOutboxMigrationsModule;
import dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule;
import dev.vertique.job.postgresql.JobMigrationsModule;
import dev.vertique.job.postgresql.JobPostgresqlModule;
import dev.vertique.workflow.postgresql.engine.WorkflowMigrationsModule;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proof that the packaged job, inbox/outbox and workflow migrations, plus application migrations, run
 * through a generated migration-only Dagger component and the public named runner with no application
 * bootstrap: no connection pool, no PostgreSQL runtime or adapter module, no worker or HTTP deployment.
 *
 * <p>The caller owns Vert.x. The test creates it with context class loader propagation disabled and a
 * single worker thread whose ambient context class loader can see no migration resource at all, so the
 * run only succeeds when the runner uses the class loader of the thread that called it.
 *
 * <p>The caller's class loader serves the job scripts and a sibling instance's scripts from one test
 * archive: the job prefix {@code db/migration/job} and the sibling prefix {@code db/migration/job-v2}
 * share their leading characters. The archive's parent hides the job prefix of the real job artifact,
 * and the runner's own class loader cannot see the sibling prefix, so the captured caller loader is
 * the only loader that resolves every instance. Each history must then hold only its own scripts, and
 * every object must live only in the schema mapped to its instance.
 */
@ExtendWith(DatabaseExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class StandaloneNamedMigrationsIT {

    // --- Instances, schemas and resources ---

    private static final String APP = "app";
    private static final String INBOX_OUTBOX = "inbox-outbox";
    private static final String JOB = "job";
    private static final String JOB_SIBLING = "job-v2";
    private static final String WORKFLOW = "workflow";

    private static final String APP_SCHEMA = "app";
    private static final String APP_EXTRA_SCHEMA = "app_extra";
    private static final String MESSAGING_SCHEMA = "messaging";
    private static final String JOB_SCHEMA = "jobs";
    private static final String SIBLING_SCHEMA = "job_v2";
    private static final String WORKFLOW_SCHEMA = "workflows";
    private static final String DEFAULT_SCHEMA = "public";

    private static final String APP_PREFIX = "db/it-named-app";
    private static final String INBOX_OUTBOX_PREFIX = "db/migration/inbox-outbox";
    private static final String JOB_PREFIX = "db/migration/job";
    private static final String SIBLING_PREFIX = "db/migration/job-v2";
    private static final String WORKFLOW_PREFIX = "db/migration/workflow";

    private static final String APP_SCRIPT = "V1__create_app_tables.sql";
    private static final String JOB_SCRIPT = "V1__create_job_tables.sql";
    private static final String SIBLING_SCRIPT = "V100__create_job_v2_tables.sql";
    private static final String SIBLING_SCRIPT_SOURCE = "it-standalone-jar/job-v2/" + SIBLING_SCRIPT;

    /** Every migration prefix of the run; a loader that can see none of them cannot run anything. */
    private static final List<String> MIGRATION_PREFIXES =
            List.of(APP_PREFIX, INBOX_OUTBOX_PREFIX, JOB_PREFIX, SIBLING_PREFIX, WORKFLOW_PREFIX);

    /** Directory entries the archive must carry; without them a class loader lists no prefix at all. */
    private static final Set<String> ARCHIVE_DIRECTORIES =
            Set.of("db/", "db/migration/", "db/migration/job/", "db/migration/job-v2/");

    /** The instances in execution order: no dependencies, so ordered by name. */
    private static final List<ExpectedInstance> INSTANCES = List.of(
            new ExpectedInstance(APP, APP_PREFIX, List.of(APP_SCHEMA, APP_EXTRA_SCHEMA), "flyway_schema_history_app"),
            new ExpectedInstance(
                    INBOX_OUTBOX, INBOX_OUTBOX_PREFIX, List.of(MESSAGING_SCHEMA), "flyway_schema_history_inbox_outbox"),
            new ExpectedInstance(JOB, JOB_PREFIX, List.of(JOB_SCHEMA), "flyway_schema_history_job"),
            new ExpectedInstance(JOB_SIBLING, SIBLING_PREFIX, List.of(SIBLING_SCHEMA), "flyway_schema_history_job_v2"),
            new ExpectedInstance(
                    WORKFLOW, WORKFLOW_PREFIX, List.of(WORKFLOW_SCHEMA), "flyway_schema_history_workflow"));

    /** Every non-system schema the database may hold after the run. */
    private static final Set<String> EXPECTED_SCHEMAS = Set.of(
            DEFAULT_SCHEMA,
            APP_SCHEMA,
            APP_EXTRA_SCHEMA,
            MESSAGING_SCHEMA,
            JOB_SCHEMA,
            SIBLING_SCHEMA,
            WORKFLOW_SCHEMA);

    /** Each migrated table name and the one schema allowed to hold it. */
    private static final Map<String, String> TABLE_PLACEMENT = Map.ofEntries(
            Map.entry("app_items", APP_SCHEMA),
            Map.entry("app_audit", APP_EXTRA_SCHEMA),
            Map.entry("inbox", MESSAGING_SCHEMA),
            Map.entry("outbox", MESSAGING_SCHEMA),
            Map.entry("job_executions", JOB_SCHEMA),
            Map.entry("job_logs", JOB_SCHEMA),
            Map.entry("job_schedules", JOB_SCHEMA),
            Map.entry("job_server_heartbeats", JOB_SCHEMA),
            Map.entry("job_v2_markers", SIBLING_SCHEMA),
            Map.entry("workflow_instances", WORKFLOW_SCHEMA),
            Map.entry("workflow_history", WORKFLOW_SCHEMA),
            Map.entry("workflow_dedup", WORKFLOW_SCHEMA),
            Map.entry("workflow_timers", WORKFLOW_SCHEMA),
            Map.entry("workflow_tasks", WORKFLOW_SCHEMA),
            Map.entry("workflow_branch_tokens", WORKFLOW_SCHEMA),
            Map.entry("workflow_join_states", WORKFLOW_SCHEMA));

    /** One migrated index per instance that creates indexes, and its one allowed schema. */
    private static final Map<String, String> INDEX_PLACEMENT = Map.of(
            "idx_app_items_label", APP_SCHEMA,
            "idx_outbox_pending", MESSAGING_SCHEMA,
            "idx_job_executions_claim", JOB_SCHEMA,
            "idx_workflow_instances_business_key", WORKFLOW_SCHEMA);

    private static final String NOTIFY_FUNCTION = "notify_transactional_outbox";
    private static final String NOTIFY_TRIGGER = "transactional_outbox_notify on " + MESSAGING_SCHEMA + ".outbox calls "
            + MESSAGING_SCHEMA + "." + NOTIFY_FUNCTION;

    /** Runtime modules a migration-only component must not contain, directly or through includes. */
    private static final Set<Class<?>> RUNTIME_MODULES = Set.of(
            DbPostgresqlModule.class,
            JobPostgresqlModule.class,
            TransactionalMessagingPostgresqlModule.class,
            WorkflowPostgresqlModule.class);

    private static final Pattern VERSIONED_SCRIPT = Pattern.compile("V(\\d+(?:[._]\\d+)*)__.+\\.sql");

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("standalone_named_migrations");

    // --- Caller-owned resources, closed in teardown ---

    private Vertx vertx;
    private URLClassLoader callerLoader;
    private URLClassLoader unrelatedWorkerLoader;

    @BeforeEach
    void createCallerOwnedVertx() {
        // The caller's Vert.x does not copy a context class loader onto its threads, and its single worker
        // thread keeps whatever loader it is given, so the run cannot borrow the caller's loader by accident.
        vertx = Vertx.vertx(new VertxOptions().setDisableTCCL(true).setWorkerPoolSize(1));
    }

    @AfterEach
    void closeCallerOwnedResources() throws Exception {
        try {
            if (vertx != null) {
                await(vertx.close());
            }
        } finally {
            try {
                if (callerLoader != null) {
                    callerLoader.close();
                }
            } finally {
                if (unrelatedWorkerLoader != null) {
                    unrelatedWorkerLoader.close();
                }
            }
        }
    }

    @Test
    // One method migrates five instances twice against a fresh database and builds archive and loader
    // fixtures, which does not fit the class default; the override is bounded to this method.
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("packaged module migrations run once each into their mapped schemas without application bootstrap")
    void migratesPackagedModulesWithoutApplicationBootstrap(@TempDir Path workDir) throws Exception {
        // Given: a fresh database and a migration-only component with the packaged declarations
        assertDatabaseHoldsNoMigratedObjects();
        MigrationComponent migration =
                DaggerStandaloneNamedMigrationsIT_MigrationComponent.factory().create(vertx, migrationConfig());
        assertComponentIsMigrationOnly(migration);
        assertPackagedAndApplicationDeclarations(migration);
        assertPlacementHasExactlyFourEntries(migration.dbSchemas());

        // Given: the caller's loader serves the job and sibling prefixes from one packaged test archive
        SortedMap<String, byte[]> jobFiles = packagedFiles(JobMigrationsModule.class, JOB_PREFIX);
        Path archive = buildSiblingPrefixArchive(workDir, jobFiles);
        assertArchiveListsEveryDirectory(archive);
        callerLoader = new URLClassLoader(
                "standalone-migration-caller",
                new URL[] {archive.toUri().toURL()},
                new PackagedJobScriptsHidingLoader(applicationLoader()));
        assertCallerLoaderResolvesEveryPrefixFromItsIntendedSource(archive);
        Map<String, List<String>> scripts = expectedScripts(jobFiles);

        // Given: the only worker thread carries an unrelated ambient loader that sees no migration resource
        loadJdbcDriversOnTestThread();
        Thread worker = pinUnrelatedLoaderOnWorkerThread();
        assertWorkerStillCarriesUnrelatedLoader(worker);
        assertEquals(Set.of(), vertx.deploymentIDs(), "nothing is deployed before the run");

        // Then: without the caller's loader the instances cannot be resolved, so the loader decides the outcome
        assertLoaderCannotResolveInstance(migration.runner(), applicationLoader(), JOB_SIBLING);
        assertLoaderCannotResolveInstance(migration.runner(), callerLoader.getParent(), JOB);

        // When: the caller resolves the connection-free views and runs the public runner on its own loader
        List<MigrationInstanceView> views = withCallerLoader(migration.runner()::instances);
        MigrationReport first = runWithCallerLoader(migration.runner());

        // Then: every instance migrated exactly its own scripts into its own schema, on the unrelated worker
        assertConnectionFreeViews(views);
        assertEveryInstanceMigrated(first, scripts, true);
        assertHistoriesHoldOnlyTheirOwnScripts(scripts);
        assertObjectsLiveOnlyInTheirMappedSchemas();
        assertSeededRows();
        assertWorkerStillCarriesUnrelatedLoader(worker);

        // When: the same runner runs again
        MigrationReport second = runWithCallerLoader(migration.runner());

        // Then: nothing is applied twice and nothing moved
        assertEveryInstanceMigrated(second, scripts, false);
        assertHistoriesHoldOnlyTheirOwnScripts(scripts);
        assertObjectsLiveOnlyInTheirMappedSchemas();
        assertSeededRows();

        // Then: no application runtime was created and the caller's Vert.x is still usable
        assertNoApplicationRuntimeStarted(migration);
    }

    // --- Component ---

    /** Migration-only graph: shipped declarations and the named runner; no Pool and no runtime module. */
    @Singleton
    @Component(
            modules = {
                DbModule.class,
                DbFlywayModule.class,
                ConfigParsingModule.class,
                JobMigrationsModule.class,
                InboxOutboxMigrationsModule.class,
                WorkflowMigrationsModule.class,
                ApplicationMigrationsModule.class,
                AbsentPoolProbeModule.class
            })
    interface MigrationComponent {

        FlywayMigrationRunner runner();

        Set<MigrationDefinition> migrationDefinitions();

        DbSchemas dbSchemas();

        Optional<Pool> optionalPool();

        @Component.Factory
        interface Factory {
            MigrationComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** The application's own declarations: its tables and a sibling of the job prefix. */
    @Module
    abstract static class ApplicationMigrationsModule {

        @Provides
        @IntoSet
        static MigrationDefinition appMigration() {
            return new MigrationDefinition(APP, List.of("classpath:" + APP_PREFIX), List.of());
        }

        @Provides
        @IntoSet
        static MigrationDefinition jobSiblingMigration() {
            return new MigrationDefinition(JOB_SIBLING, List.of("classpath:" + SIBLING_PREFIX), List.of());
        }
    }

    /** Lets the component report that it has no Pool binding. */
    @Module
    abstract static class AbsentPoolProbeModule {

        @BindsOptionalOf
        abstract Pool pool();
    }

    /** What one named instance is expected to resolve to and own. */
    private record ExpectedInstance(String name, String prefix, List<String> managedSchemas, String history) {

        String schema() {
            return managedSchemas.get(0);
        }

        String location() {
            return "classpath:" + prefix;
        }
    }

    // --- Graph assertions ---

    private static void assertComponentIsMigrationOnly(MigrationComponent migration) {
        assertEquals(
                List.of(
                        DbModule.class,
                        DbFlywayModule.class,
                        ConfigParsingModule.class,
                        JobMigrationsModule.class,
                        InboxOutboxMigrationsModule.class,
                        WorkflowMigrationsModule.class,
                        ApplicationMigrationsModule.class,
                        AbsentPoolProbeModule.class),
                List.of(MigrationComponent.class.getAnnotation(Component.class).modules()),
                "the component declares exactly the migration-only modules");
        Set<Class<?>> modules = declaredModules(MigrationComponent.class);
        assertTrue(
                modules.stream().noneMatch(RUNTIME_MODULES::contains),
                "no runtime module may be reachable through includes: " + modules);
        for (Class<?> metadata :
                List.of(JobMigrationsModule.class, InboxOutboxMigrationsModule.class, WorkflowMigrationsModule.class)) {
            assertEquals(
                    0,
                    metadata.getAnnotation(Module.class).includes().length,
                    metadata.getSimpleName() + " is a metadata-only declaration module");
        }
        assertTrue(migration.optionalPool().isEmpty(), "the migration-only graph must not bind a Pool");
    }

    private static void assertPackagedAndApplicationDeclarations(MigrationComponent migration) {
        assertEquals(
                Set.of(
                        new MigrationDefinition(JOB, List.of("classpath:" + JOB_PREFIX), List.of()),
                        new MigrationDefinition(INBOX_OUTBOX, List.of("classpath:" + INBOX_OUTBOX_PREFIX), List.of()),
                        new MigrationDefinition(WORKFLOW, List.of("classpath:" + WORKFLOW_PREFIX), List.of()),
                        new MigrationDefinition(APP, List.of("classpath:" + APP_PREFIX), List.of()),
                        new MigrationDefinition(JOB_SIBLING, List.of("classpath:" + SIBLING_PREFIX), List.of())),
                migration.migrationDefinitions());
    }

    private static void assertPlacementHasExactlyFourEntries(DbSchemas schemas) {
        assertEquals(
                Map.of(JOB, JOB_SCHEMA, INBOX_OUTBOX, MESSAGING_SCHEMA, WORKFLOW, WORKFLOW_SCHEMA, APP, APP_SCHEMA),
                schemas.mappings());
        assertTrue(schemas.schema(JOB_SIBLING).isEmpty(), "the sibling is placed by its Flyway list only");
    }

    private static Set<Class<?>> declaredModules(Class<?> component) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(
                List.of(component.getAnnotation(Component.class).modules()));
        while (!pending.isEmpty()) {
            Class<?> module = pending.pop();
            if (seen.add(module)) {
                Module annotation = module.getAnnotation(Module.class);
                if (annotation != null) {
                    pending.addAll(List.of(annotation.includes()));
                }
            }
        }
        return seen;
    }

    // --- Loader fixtures ---

    /**
     * Parent of the caller's loader that hides the job prefix of the real job artifact, so the job scripts
     * resolve from the test archive only. The sibling prefix is not hidden: the match is segment-exact.
     */
    private static final class PackagedJobScriptsHidingLoader extends ClassLoader {

        PackagedJobScriptsHidingLoader(ClassLoader parent) {
            super("packaged-job-scripts-hidden", parent);
        }

        private static boolean hidden(String name) {
            return name.equals(JOB_PREFIX) || name.startsWith(JOB_PREFIX + "/");
        }

        @Override
        public URL getResource(String name) {
            return hidden(name) ? null : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return hidden(name) ? Collections.emptyEnumeration() : super.getResources(name);
        }
    }

    private static ClassLoader applicationLoader() {
        return StandaloneNamedMigrationsIT.class.getClassLoader();
    }

    /** Builds the archive with explicit directory entries, the job scripts byte for byte and the sibling script. */
    private static Path buildSiblingPrefixArchive(Path workDir, SortedMap<String, byte[]> jobFiles) throws IOException {
        Path archive = workDir.resolve("sibling-prefixes.jar");
        Set<String> directories = new LinkedHashSet<>();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, byte[]> file : jobFiles.entrySet()) {
                putFile(out, directories, JOB_PREFIX + "/" + file.getKey(), file.getValue());
            }
            putFile(out, directories, SIBLING_PREFIX + "/" + SIBLING_SCRIPT, siblingScript());
        }
        return archive;
    }

    private static void putFile(JarOutputStream out, Set<String> directories, String name, byte[] content)
            throws IOException {
        for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
            String directory = name.substring(0, slash + 1);
            if (directories.add(directory)) {
                out.putNextEntry(new JarEntry(directory));
                out.closeEntry();
            }
        }
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static byte[] siblingScript() throws IOException {
        try (InputStream in = applicationLoader().getResourceAsStream(SIBLING_SCRIPT_SOURCE)) {
            assertNotNull(in, "sibling script fixture on the test classpath: " + SIBLING_SCRIPT_SOURCE);
            return in.readAllBytes();
        }
    }

    private static void assertArchiveListsEveryDirectory(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            Set<String> directories = jar.stream()
                    .filter(JarEntry::isDirectory)
                    .map(JarEntry::getName)
                    .collect(Collectors.toSet());
            assertTrue(directories.containsAll(ARCHIVE_DIRECTORIES), "archive directory entries: " + directories);
        }
    }

    private void assertCallerLoaderResolvesEveryPrefixFromItsIntendedSource(Path archive) throws Exception {
        assertEquals(MIGRATION_PREFIXES, visiblePrefixes(callerLoader), "the caller's loader sees every prefix");
        assertNotNull(
                callerLoader.getResource(APP_PREFIX + "/" + APP_SCRIPT), "the caller's loader sees the app fixture");
        assertEquals(List.of(archive), archivesServing(callerLoader, JOB_PREFIX), "job scripts come from the archive");
        assertEquals(
                List.of(archive),
                archivesServing(callerLoader, SIBLING_PREFIX),
                "sibling scripts come from the archive");
        assertFalse(
                visiblePrefixes(FlywayMigrationRunner.class.getClassLoader()).contains(SIBLING_PREFIX),
                "the runner's own loader must not see the sibling prefix, or a fallback to it could pass");
    }

    /** The archives that serve a prefix through the given loader, one per resolved URL. */
    private static List<Path> archivesServing(ClassLoader loader, String prefix) throws Exception {
        List<Path> archives = new ArrayList<>();
        for (URL url : Collections.list(loader.getResources(prefix))) {
            assertEquals("jar", url.getProtocol(), prefix + " must resolve inside an archive: " + url);
            URL archiveUrl = ((JarURLConnection) url.openConnection()).getJarFileURL();
            archives.add(Path.of(archiveUrl.toURI()));
        }
        return archives;
    }

    private static List<String> visiblePrefixes(ClassLoader loader) throws IOException {
        List<String> visible = new ArrayList<>();
        for (String prefix : MIGRATION_PREFIXES) {
            if (loader.getResources(prefix).hasMoreElements()) {
                visible.add(prefix);
            }
        }
        return visible;
    }

    /** Registers the JDBC drivers from the test thread, so the worker never needs its own loader for that. */
    private static void loadJdbcDriversOnTestThread() {
        List<Driver> drivers = Collections.list(DriverManager.getDrivers());
        assertTrue(
                drivers.stream().anyMatch(driver -> driver.getClass().getName().startsWith("org.postgresql.")),
                "PostgreSQL driver registered from the test thread: " + drivers);
    }

    /** Gives the single worker thread an unrelated platform-parent loader and returns that thread. */
    private Thread pinUnrelatedLoaderOnWorkerThread() throws Exception {
        URLClassLoader unrelated =
                new URLClassLoader("unrelated-worker-loader", new URL[0], ClassLoader.getPlatformClassLoader());
        unrelatedWorkerLoader = unrelated;
        return await(vertx.executeBlocking(() -> {
            Thread.currentThread().setContextClassLoader(unrelated);
            return Thread.currentThread();
        }));
    }

    private void assertWorkerStillCarriesUnrelatedLoader(Thread worker) throws Exception {
        WorkerProbe probe = await(vertx.executeBlocking(() -> {
            ClassLoader ambient = Thread.currentThread().getContextClassLoader();
            return new WorkerProbe(Thread.currentThread(), ambient, visiblePrefixes(ambient));
        }));
        assertSame(worker, probe.thread(), "the pool has a single worker thread");
        assertSame(unrelatedWorkerLoader, probe.ambientLoader(), "the worker keeps its unrelated ambient loader");
        assertEquals(List.of(), probe.visiblePrefixes(), "the worker's ambient loader sees no migration prefix");
    }

    private record WorkerProbe(Thread thread, ClassLoader ambientLoader, List<String> visiblePrefixes) {}

    private <T> T withCallerLoader(CallerAction<T> action) throws Exception {
        return withContextClassLoader(callerLoader, action);
    }

    private static <T> T withContextClassLoader(ClassLoader loader, CallerAction<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @FunctionalInterface
    private interface CallerAction<T> {
        T run() throws Exception;
    }

    // --- Runner calls and report assertions ---

    private MigrationReport runWithCallerLoader(FlywayMigrationRunner runner) throws Exception {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        MigrationReport report;
        try {
            report = withCallerLoader(() -> await(runner.run(vertx)));
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof MigrationRunException named) {
                return fail("named run failed with " + named.failureCode() + " during " + named.operation() + ": "
                        + named.getMessage() + "; report " + named.report());
            }
            throw failure;
        }
        assertSame(before, Thread.currentThread().getContextClassLoader(), "the test thread's loader is restored");
        return report;
    }

    private static void assertLoaderCannotResolveInstance(
            FlywayMigrationRunner runner, ClassLoader loader, String unresolvedInstance) throws Exception {
        MigrationRunException failure =
                withContextClassLoader(loader, () -> assertThrows(MigrationRunException.class, runner::instances));

        assertEquals("RESOURCE", failure.failureCode(), failure.getMessage());
        assertEquals("instances", failure.operation());
        assertTrue(
                failure.getMessage().contains("'" + unresolvedInstance + "'"),
                "the failure names " + unresolvedInstance + ": " + failure.getMessage());
    }

    private static void assertConnectionFreeViews(List<MigrationInstanceView> views) {
        assertEquals(
                INSTANCES.stream().map(ExpectedInstance::name).toList(),
                views.stream().map(MigrationInstanceView::name).toList());
        for (int i = 0; i < INSTANCES.size(); i++) {
            ExpectedInstance expected = INSTANCES.get(i);
            MigrationInstanceView view = views.get(i);
            String name = expected.name();
            assertEquals(expected.schema(), view.schema(), name + " default schema");
            assertEquals(expected.managedSchemas(), view.schemas(), name + " managed schemas");
            assertEquals(expected.history(), view.table(), name + " history table");
            assertEquals(List.of(expected.location()), view.locations(), name + " locations");
            assertEquals(FlywayMode.MIGRATE, view.mode(), name + " mode");
            assertFalse(view.baselineOnMigrate(), name + " baselineOnMigrate");
            assertEquals(CredentialSource.DB_POOL, view.credentialSource(), name + " credential source");
            assertEquals(db.username(), view.user(), name + " user");
            assertEquals(Set.of(), view.placeholderKeys(), name + " placeholder keys");
        }
    }

    private static void assertEveryInstanceMigrated(
            MigrationReport report, Map<String, List<String>> scripts, boolean firstRun) {
        List<MigrationInstanceResult> results = report.instances();
        assertEquals(
                INSTANCES.stream().map(ExpectedInstance::name).toList(),
                results.stream().map(result -> result.instance().name()).toList());
        for (int i = 0; i < INSTANCES.size(); i++) {
            ExpectedInstance expected = INSTANCES.get(i);
            MigrationInstanceResult result = results.get(i);
            List<String> own = scripts.get(expected.name());
            String name = expected.name();
            assertEquals(MigrationStatus.MIGRATED, result.status(), name + " status");
            assertEquals(firstRun ? own.size() : 0, result.migrationsApplied(), name + " applied scripts");
            assertEquals(version(own.get(own.size() - 1)), result.currentVersion(), name + " current version");
            assertEquals(expected.schema(), result.instance().schema(), name + " resolved schema");
            assertEquals(expected.history(), result.instance().table(), name + " resolved history table");
        }
    }

    // --- Native state assertions ---

    private static void assertDatabaseHoldsNoMigratedObjects() throws SQLException {
        assertEquals(Set.of(DEFAULT_SCHEMA), userSchemas(), "the database starts with only its default schema");
        assertEquals(Set.of(), relationsIn(DEFAULT_SCHEMA), "the default schema starts empty");
    }

    private static void assertHistoriesHoldOnlyTheirOwnScripts(Map<String, List<String>> scripts) throws SQLException {
        for (ExpectedInstance instance : INSTANCES) {
            assertEquals(
                    scripts.get(instance.name()),
                    appliedScripts(instance),
                    instance.name() + " history holds exactly its own scripts, in version order");
            assertEquals(
                    List.of("0"),
                    column("SELECT count(*) FROM " + instance.schema() + "." + instance.history()
                            + " WHERE NOT success"),
                    instance.name() + " history has no failed entries");
        }
        assertFalse(
                appliedScripts(instance(JOB)).contains(SIBLING_SCRIPT),
                "the job instance must not pick up scripts of the sibling prefix");
        assertTrue(
                appliedScripts(instance(JOB)).contains(JOB_SCRIPT), "the job instance applied the job tables script");
        assertEquals(List.of(SIBLING_SCRIPT), appliedScripts(instance(JOB_SIBLING)));

        Map<String, Set<String>> expectedHistories = new HashMap<>();
        for (ExpectedInstance instance : INSTANCES) {
            expectedHistories.put(instance.history(), Set.of(instance.schema()));
        }
        assertEquals(expectedHistories, historyTablePlacement(), "each history exists once, in its instance's schema");
    }

    private static void assertObjectsLiveOnlyInTheirMappedSchemas() throws SQLException {
        assertEquals(EXPECTED_SCHEMAS, userSchemas(), "no schema beyond the mapped and managed ones");
        for (Map.Entry<String, String> table : TABLE_PLACEMENT.entrySet()) {
            assertEquals(
                    Set.of(table.getValue()),
                    schemasHoldingTable(table.getKey()),
                    "table " + table.getKey() + " lives only in " + table.getValue());
        }
        for (Map.Entry<String, String> index : INDEX_PLACEMENT.entrySet()) {
            assertEquals(
                    Set.of(index.getValue()),
                    schemasHoldingIndex(index.getKey()),
                    "index " + index.getKey() + " lives only in " + index.getValue());
        }
        assertEquals(Set.of(), relationsIn(DEFAULT_SCHEMA), "nothing is migrated into the default schema");
        assertEquals(Set.of(), functionsIn(DEFAULT_SCHEMA), "no function is migrated into the default schema");
        for (String schema : EXPECTED_SCHEMAS) {
            assertEquals(
                    schema.equals(MESSAGING_SCHEMA) ? Set.of(NOTIFY_FUNCTION) : Set.of(),
                    functionsIn(schema),
                    "functions in " + schema);
        }
        assertEquals(Set.of(NOTIFY_TRIGGER), userTriggers(), "the only trigger is the messaging notify trigger");
    }

    private static void assertSeededRows() throws SQLException {
        assertEquals(List.of("1"), column("SELECT count(*) FROM " + APP_SCHEMA + ".app_items"));
        assertEquals(List.of("1"), column("SELECT count(*) FROM " + SIBLING_SCHEMA + ".job_v2_markers"));
    }

    private void assertNoApplicationRuntimeStarted(MigrationComponent migration) throws Exception {
        assertTrue(migration.optionalPool().isEmpty(), "no Pool binding was created");
        assertEquals(Set.of(), vertx.deploymentIDs(), "no worker, job, workflow or HTTP verticle was deployed");
        await(vertx.timer(1, TimeUnit.MILLISECONDS));
        assertEquals("still usable", await(vertx.executeBlocking(() -> "still usable")));
    }

    private static List<String> appliedScripts(ExpectedInstance instance) throws SQLException {
        return column("SELECT script FROM " + instance.schema() + "." + instance.history()
                + " WHERE version IS NOT NULL AND success ORDER BY installed_rank");
    }

    private static ExpectedInstance instance(String name) {
        return INSTANCES.stream()
                .filter(instance -> instance.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    // --- Catalog queries ---

    private static Set<String> userSchemas() throws SQLException {
        return Set.copyOf(column("SELECT nspname FROM pg_namespace"
                + " WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema'"));
    }

    private static Set<String> schemasHoldingTable(String table) throws SQLException {
        return Set.copyOf(column("SELECT table_schema FROM information_schema.tables WHERE table_name = ?", table));
    }

    private static Set<String> schemasHoldingIndex(String index) throws SQLException {
        return Set.copyOf(column("SELECT schemaname FROM pg_indexes WHERE indexname = ?", index));
    }

    /** History table name to the schemas holding a table of that name. */
    private static Map<String, Set<String>> historyTablePlacement() throws SQLException {
        Map<String, Set<String>> placement = new HashMap<>();
        for (String entry : column("SELECT table_name || '/' || table_schema FROM information_schema.tables"
                + " WHERE table_name LIKE 'flyway\\_schema\\_history%'")) {
            String[] parts = entry.split("/", 2);
            placement
                    .computeIfAbsent(parts[0], ignored -> new LinkedHashSet<>())
                    .add(parts[1]);
        }
        Map<String, Set<String>> frozen = new HashMap<>();
        placement.forEach((table, schemas) -> frozen.put(table, Set.copyOf(schemas)));
        return frozen;
    }

    private static Set<String> relationsIn(String schema) throws SQLException {
        return Set.copyOf(column(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?",
                schema));
    }

    private static Set<String> functionsIn(String schema) throws SQLException {
        return Set.copyOf(column(
                "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = ?",
                schema));
    }

    /** Non-internal triggers outside system schemas, as "trigger on schema.table calls schema.function". */
    private static Set<String> userTriggers() throws SQLException {
        return Set.copyOf(column("SELECT t.tgname || ' on ' || tn.nspname || '.' || c.relname"
                + " || ' calls ' || pn.nspname || '.' || p.proname"
                + " FROM pg_trigger t"
                + " JOIN pg_class c ON c.oid = t.tgrelid"
                + " JOIN pg_namespace tn ON tn.oid = c.relnamespace"
                + " JOIN pg_proc p ON p.oid = t.tgfoid"
                + " JOIN pg_namespace pn ON pn.oid = p.pronamespace"
                + " WHERE NOT t.tgisinternal AND tn.nspname NOT LIKE 'pg\\_%'"
                + " AND tn.nspname <> 'information_schema'"));
    }

    // --- Packaged scripts ---

    /** Expected applied scripts per instance; packaged ones are read from the module's own artifact. */
    private static Map<String, List<String>> expectedScripts(SortedMap<String, byte[]> jobFiles) throws Exception {
        List<String> jobScripts = versionedScripts(jobFiles.keySet());
        assertTrue(jobScripts.contains(JOB_SCRIPT), "packaged job scripts: " + jobScripts);
        return Map.of(
                APP, List.of(APP_SCRIPT),
                INBOX_OUTBOX,
                        versionedScripts(packagedFiles(InboxOutboxMigrationsModule.class, INBOX_OUTBOX_PREFIX)
                                .keySet()),
                JOB, jobScripts,
                JOB_SIBLING, List.of(SIBLING_SCRIPT),
                WORKFLOW,
                        versionedScripts(packagedFiles(WorkflowMigrationsModule.class, WORKFLOW_PREFIX)
                                .keySet()));
    }

    /**
     * Reads every file under a prefix from the artifact that defines the module class: a JAR when the
     * module is packaged, a class directory otherwise. Keys are paths relative to the prefix.
     */
    private static SortedMap<String, byte[]> packagedFiles(Class<?> module, String prefix)
            throws IOException, URISyntaxException {
        CodeSource codeSource = module.getProtectionDomain().getCodeSource();
        assertNotNull(codeSource, module.getName() + " has a code source");
        Path artifact = Path.of(codeSource.getLocation().toURI());
        SortedMap<String, byte[]> files = new TreeMap<>();
        if (Files.isDirectory(artifact)) {
            Path root = artifact.resolve(prefix);
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    files.put(
                            root.relativize(file).toString().replace(File.separatorChar, '/'),
                            Files.readAllBytes(file));
                }
            }
        } else {
            String root = prefix + "/";
            try (JarFile jar = new JarFile(artifact.toFile())) {
                for (JarEntry entry : Collections.list(jar.entries())) {
                    if (!entry.isDirectory() && entry.getName().startsWith(root)) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            files.put(entry.getName().substring(root.length()), in.readAllBytes());
                        }
                    }
                }
            }
        }
        assertFalse(files.isEmpty(), module.getSimpleName() + " ships scripts under " + prefix + " in " + artifact);
        return files;
    }

    /** Versioned migration scripts among the given relative paths, in Flyway version order. */
    private static List<String> versionedScripts(Set<String> paths) {
        List<String> scripts = paths.stream()
                .filter(path -> VERSIONED_SCRIPT.matcher(fileName(path)).matches())
                .sorted(Comparator.comparing(
                        StandaloneNamedMigrationsIT::versionSegments, StandaloneNamedMigrationsIT::compareVersions))
                .toList();
        assertFalse(scripts.isEmpty(), "versioned scripts among " + paths);
        return scripts;
    }

    /** The Flyway version of a script as the report renders it, for example {@code 1} or {@code 1.2}. */
    private static String version(String script) {
        Matcher matcher = VERSIONED_SCRIPT.matcher(fileName(script));
        assertTrue(matcher.matches(), "versioned script: " + script);
        return matcher.group(1).replace('_', '.');
    }

    private static List<BigInteger> versionSegments(String script) {
        return Stream.of(version(script).split("\\.")).map(BigInteger::new).toList();
    }

    private static int compareVersions(List<BigInteger> left, List<BigInteger> right) {
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            BigInteger l = i < left.size() ? left.get(i) : BigInteger.ZERO;
            BigInteger r = i < right.size() ? right.get(i) : BigInteger.ZERO;
            int order = l.compareTo(r);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // --- Configuration ---

    /** Four placement entries, the app's extra managed schema, and the sibling placed by its Flyway list. */
    private static JsonObject migrationConfig() {
        DbPoolConfig pool = db.toPoolConfig();
        JsonObject placement = new JsonObject()
                .put(JOB, JOB_SCHEMA)
                .put(INBOX_OUTBOX, MESSAGING_SCHEMA)
                .put(WORKFLOW, WORKFLOW_SCHEMA)
                .put(APP, APP_SCHEMA);
        JsonObject instances = new JsonObject()
                .put(JOB, new JsonObject())
                .put(INBOX_OUTBOX, new JsonObject())
                .put(WORKFLOW, new JsonObject())
                .put(
                        APP,
                        new JsonObject()
                                .put("schemas", new JsonArray().add(APP_SCHEMA).add(APP_EXTRA_SCHEMA)))
                .put(JOB_SIBLING, new JsonObject().put("schemas", new JsonArray().add(SIBLING_SCHEMA)));
        return new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", pool.host())
                                .put("port", pool.port())
                                .put("database", pool.database())
                                .put("user", pool.user())
                                .put("password", pool.password())
                                .put("schemas", placement))
                .put(
                        "flyway",
                        new JsonObject()
                                .put("mode", "MIGRATE")
                                .put("jdbcUrl", db.jdbcUrl())
                                .put("baselineOnMigrate", false)
                                .put("instances", instances));
    }

    // --- Native database access ---

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    /** Runs a query and returns its first column rendered as text, one entry per row. */
    private static List<String> column(String sql, Object... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, String.valueOf(parameters[i]));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
