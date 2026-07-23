// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.job.JobContext;
import dev.vertique.job.delayed.exception.DelayedJobRegistrationException;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DelayedJobHandlerRegistrar}: annotation scanning, duplicate detection,
 * validation, and address map construction.
 */
@DisplayName("DelayedJobHandlerRegistrar")
class DelayedJobHandlerRegistrarTest {

    // --- Helpers ---

    /**
     * Returns a lenient {@link ConfigParser} for use in test call-sites that need to parse config.
     *
     * @return a {@link DefaultConfigParser} with lenient mapper
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- Test service contract ---

    /** Minimal service contract used by tests. */
    @ServiceContract(namespace = "test", value = "job-handler-svc")
    interface JobHandlerService {
        @ServiceOperation("processItem")
        Future<Void> processItem(String payload);

        @ServiceOperation("sendNotification")
        Future<Void> sendNotification(String payload);
    }

    /** Implementation with a single @DelayedJobHandlerMethod. */
    static class SingleHandlerImpl implements JobHandlerService {
        @DelayedJobHandlerMethod("process-item")
        @Override
        public Future<Void> processItem(String payload) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> sendNotification(String payload) {
            return Future.succeededFuture();
        }
    }

    /** Implementation with two @DelayedJobHandlerMethod annotations. */
    static class MultiHandlerImpl implements JobHandlerService {
        @DelayedJobHandlerMethod("process-item")
        @Override
        public Future<Void> processItem(String payload) {
            return Future.succeededFuture();
        }

        @DelayedJobHandlerMethod("send-notification")
        @Override
        public Future<Void> sendNotification(String payload) {
            return Future.succeededFuture();
        }
    }

    /** Implementation with a blank handler name. */
    static class BlankNameImpl implements JobHandlerService {
        @DelayedJobHandlerMethod("")
        @Override
        public Future<Void> processItem(String payload) {
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> sendNotification(String payload) {
            return Future.succeededFuture();
        }
    }

    // --- Second service for duplicate detection tests ---

    @ServiceContract(namespace = "test", value = "other-svc")
    interface OtherService {
        @ServiceOperation("doWork")
        Future<Void> doWork(String payload);
    }

    static class OtherServiceImpl implements OtherService {
        @DelayedJobHandlerMethod("process-item") // duplicate of SingleHandlerImpl
        @Override
        public Future<Void> doWork(String payload) {
            return Future.succeededFuture();
        }
    }

    // --- Tests ---

    @Nested
    @DisplayName("scan with single handler")
    class SingleHandlerScan {

        @Test
        @DisplayName("finds annotated method and builds address map")
        void findsHandlerMethod() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new SingleHandlerImpl()), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);
            registrar.scan();

            Map<String, String> addresses = registrar.handlerAddresses();
            assertEquals(1, addresses.size());
            assertTrue(addresses.containsKey("process-item"));
        }

        @Test
        @DisplayName("stores the service method event bus address")
        void storesEventBusAddress() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new SingleHandlerImpl()), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);
            registrar.scan();

            // The address is the ServiceMethodMeta address: "test/job-handler-svc/processItem"
            String address = registrar.handlerAddresses().get("process-item");
            assertTrue(address.contains("processItem"), "Expected address to contain 'processItem', got: " + address);
        }
    }

    @Nested
    @DisplayName("scan with multiple handlers")
    class MultiHandlerScan {

        @Test
        @DisplayName("finds all annotated methods")
        void findsAllHandlers() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new MultiHandlerImpl()), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);
            registrar.scan();

            Map<String, String> addresses = registrar.handlerAddresses();
            assertEquals(2, addresses.size());
            assertTrue(addresses.containsKey("process-item"));
            assertTrue(addresses.containsKey("send-notification"));
        }
    }

    @Nested
    @DisplayName("validation failures")
    class ValidationFailures {

        @Test
        @DisplayName("rejects blank handler name")
        void rejectsBlankHandlerName() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new BlankNameImpl()), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);

            DelayedJobRegistrationException ex = assertThrows(DelayedJobRegistrationException.class, registrar::scan);
            assertTrue(ex.violations().stream().anyMatch(v -> v.contains("blank handler name")));
        }

        @Test
        @DisplayName("rejects duplicate handler names across services")
        void rejectsDuplicateHandlerNames() {
            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new SingleHandlerImpl(), new OtherServiceImpl()), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);

            DelayedJobRegistrationException ex = assertThrows(DelayedJobRegistrationException.class, registrar::scan);
            assertTrue(ex.violations().stream().anyMatch(v -> v.contains("Duplicate")));
        }
    }

    @Nested
    @DisplayName("initial state")
    class InitialState {

        @Test
        @DisplayName("handlerAddresses is empty before scan")
        void handlerAddressesEmptyBeforeScan() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new SingleHandlerImpl()), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);

            assertTrue(registrar.handlerAddresses().isEmpty());
        }
    }

    // --- Contributor-based handler fixtures ---

    /** Typed executor contract for contributor-based registration. */
    @DelayedJobContract(name = "contrib-job")
    interface ContribJob extends DelayedJobClient<String> {}

    static class ContribJobExecutor implements DelayedJobExecutor<String, ContribJob> {
        @Override
        public Future<Void> execute(String payload, JobContext ctx) {
            return Future.succeededFuture();
        }
    }

    /** A second typed executor for duplicate-detection tests. */
    @DelayedJobContract(name = "process-item") // same name as SingleHandlerImpl's handler
    interface DuplicateContribJob extends DelayedJobClient<String> {}

    static class DuplicateContribJobExecutor implements DelayedJobExecutor<String, DuplicateContribJob> {
        @Override
        public Future<Void> execute(String payload, JobContext ctx) {
            return Future.succeededFuture();
        }
    }

    @Nested
    @DisplayName("contributor-backed entries (Phase 2)")
    class ContributorBackedEntries {

        @Test
        @DisplayName("contributor entry with type='delayed-job' is merged into handlerAddresses")
        void contributorEntryMergedIntoAddresses() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new ContribJobExecutor()));
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(), Set.of(contributor), new JsonObject(), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);

            registrar.scan();

            Map<String, String> addresses = registrar.handlerAddresses();
            assertTrue(addresses.containsKey("contrib-job"), "Expected 'contrib-job' in handler addresses");
            assertTrue(
                    addresses.get("contrib-job").equals("jobs/delayed/contrib-job/execute"),
                    "Expected address 'jobs/delayed/contrib-job/execute', got: " + addresses.get("contrib-job"));
        }

        @Test
        @DisplayName("contributor entry does not prevent annotation-based entries from being registered")
        void contributorAndAnnotationBasedCoexist() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new ContribJobExecutor()));
            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new SingleHandlerImpl()), Set.of(contributor), new JsonObject(), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);

            registrar.scan();

            Map<String, String> addresses = registrar.handlerAddresses();
            assertTrue(
                    addresses.containsKey("contrib-job"),
                    "Expected contributor-backed handler 'contrib-job' to be registered");
            assertTrue(
                    addresses.containsKey("process-item"),
                    "Expected annotation-based handler 'process-item' to be registered");
            assertEquals(2, addresses.size());
        }

        @Test
        @DisplayName("duplicate name across annotation scan and contributor throws exception")
        void duplicateNameAcrossAnnotationAndContributorThrows() {
            // DuplicateContribJobExecutor has @DelayedJobContract(name = "process-item")
            // which matches SingleHandlerImpl's @DelayedJobHandlerMethod("process-item")
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new DuplicateContribJobExecutor()));
            ServiceContractRegistry registry = ServiceContractRegistry.build(
                    Set.of(new SingleHandlerImpl()), Set.of(contributor), new JsonObject(), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);

            DelayedJobRegistrationException ex = assertThrows(DelayedJobRegistrationException.class, registrar::scan);
            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("Duplicate")),
                    "Expected a duplicate-name violation, got: " + ex.violations());
        }

        @Test
        @DisplayName("no 'job' entries in registry — annotation-based handlers still work")
        void noJobEntriesRegistryStillWorks() {
            // Registry with only annotation-based entries (no contributors)
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new MultiHandlerImpl()), configParser());
            DelayedJobHandlerRegistrar registrar = new DelayedJobHandlerRegistrar(registry);

            registrar.scan();

            Map<String, String> addresses = registrar.handlerAddresses();
            assertEquals(2, addresses.size());
            assertTrue(addresses.containsKey("process-item"));
            assertTrue(addresses.containsKey("send-notification"));
        }
    }
}
