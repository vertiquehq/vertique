// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.job.JobContext;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DelayedJobContractContributor}: type argument resolution, contract annotation
 * validation, entry building, and parameter metadata correctness.
 */
@DisplayName("DelayedJobContractContributor")
class DelayedJobContractContributorTest {

    // --- Test Fixtures ---

    /** Contract annotated with all custom values. */
    @DelayedJobContract(name = "test-job", maxAttempts = 2, queue = "test-queue", priority = 5)
    interface TestJob extends DelayedJobClient<String> {}

    static class TestJobExecutor implements DelayedJobExecutor<String, TestJob> {
        @Override
        public Future<Void> execute(String payload, JobContext ctx) {
            return Future.succeededFuture();
        }
    }

    /** Second distinct executor for multi-executor tests. */
    @DelayedJobContract(name = "other-job")
    interface OtherJob extends DelayedJobClient<Integer> {}

    static class OtherJobExecutor implements DelayedJobExecutor<Integer, OtherJob> {
        @Override
        public Future<Void> execute(Integer payload, JobContext ctx) {
            return Future.succeededFuture();
        }
    }

    /** Contract whose name contains illegal characters. */
    @DelayedJobContract(name = "invalid name with spaces")
    interface InvalidNameJob extends DelayedJobClient<String> {}

    static class InvalidNameJobExecutor implements DelayedJobExecutor<String, InvalidNameJob> {
        @Override
        public Future<Void> execute(String payload, JobContext ctx) {
            return Future.succeededFuture();
        }
    }

    /** Client interface without @DelayedJobContract. */
    interface NoAnnotationJob extends DelayedJobClient<String> {}

    static class NoAnnotationJobExecutor implements DelayedJobExecutor<String, NoAnnotationJob> {
        @Override
        public Future<Void> execute(String payload, JobContext ctx) {
            return Future.succeededFuture();
        }
    }

    // --- Tests ---

    @Nested
    @DisplayName("happy path — single executor")
    class HappyPath {

        @Test
        @DisplayName("contributes exactly one entry for a single executor")
        void contributesSingleEntry() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            List<ContractEntry<?>> entries = contributor.contribute(new JsonObject());

            assertEquals(1, entries.size());
        }

        @Test
        @DisplayName("contract key is the executor implementation class")
        void contractKeyIsExecutorClass() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);

            assertEquals(TestJobExecutor.class, entry.contract());
        }

        @Test
        @DisplayName("entry namespace is 'delayed-job'")
        void entryNamespaceIsDelayedJob() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);

            assertEquals("delayed-job", entry.namespace());
        }

        @Test
        @DisplayName("entry name comes from @DelayedJobContract")
        void entryNameFromAnnotation() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);

            assertEquals("test-job", entry.name());
        }

        @Test
        @DisplayName("event bus address follows jobs/delayed/{name}/execute pattern")
        void addressFollowsPattern() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);
            ServiceMethodMeta executeMeta = entry.operations().get("execute");

            assertNotNull(executeMeta, "execute operation must be present");
            assertEquals("jobs/delayed/test-job/execute", executeMeta.address());
        }

        @Test
        @DisplayName("payload type is correctly resolved to String")
        void payloadTypeResolvedToString() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);
            ServiceMethodMeta executeMeta = entry.operations().get("execute");

            assertEquals(String.class, executeMeta.payloadType());
        }

        @Test
        @DisplayName("operation has PAYLOAD parameter for the payload")
        void operationHasPayloadParam() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);
            ServiceMethodMeta executeMeta = entry.operations().get("execute");
            List<ParamMeta> params = executeMeta.params();

            boolean hasPayloadParam =
                    params.stream().anyMatch(p -> p.source() == ParamSource.PAYLOAD && p.type() == String.class);
            assertTrue(hasPayloadParam, "Expected a PAYLOAD param of type String");
        }

        @Test
        @DisplayName("operation has DISPATCH_CONTEXT parameter for the JobContext")
        void operationHasDispatchContextParam() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);
            ServiceMethodMeta executeMeta = entry.operations().get("execute");
            List<ParamMeta> params = executeMeta.params();

            boolean hasCtxParam = params.stream()
                    .anyMatch(p -> p.source() == ParamSource.DISPATCH_CONTEXT && p.type() == JobContext.class);
            assertTrue(hasCtxParam, "Expected a DISPATCH_CONTEXT param of type JobContext");
        }

        @Test
        @DisplayName("deployment options instances read from services.contracts.delayed-job.{name}")
        void deploymentOptionsFromConfig() {
            JsonObject config = new JsonObject()
                    .put(
                            "services",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "delayed-job",
                                                            new JsonObject()
                                                                    .put(
                                                                            "test-job",
                                                                            new JsonObject().put("instances", 4)))));
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(config).get(0);

            assertEquals(4, entry.deploymentOptions().getInstances());
        }

        @Test
        @DisplayName("worker flag read from services.contracts.delayed-job.{name}")
        void workerFlagFromConfig() {
            JsonObject config = new JsonObject()
                    .put(
                            "services",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "delayed-job",
                                                            new JsonObject()
                                                                    .put(
                                                                            "test-job",
                                                                            new JsonObject().put("worker", true)))));
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(config).get(0);

            assertEquals(
                    io.vertx.core.ThreadingModel.WORKER,
                    entry.deploymentOptions().getThreadingModel(),
                    "worker=true must produce WORKER threading model");
        }

        @Test
        @DisplayName("config at old wrong path services.job.{name} is silently ignored")
        void oldWrongPathIsIgnored() {
            // Verify the old wrong path has no effect — instances default to 1
            JsonObject config = new JsonObject()
                    .put(
                            "services",
                            new JsonObject()
                                    .put(
                                            "job",
                                            new JsonObject().put("test-job", new JsonObject().put("instances", 99))));
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(config).get(0);

            assertEquals(
                    1, entry.deploymentOptions().getInstances(), "old path must be ignored; instances default to 1");
        }
    }

    @Nested
    @DisplayName("multiple executors")
    class MultipleExecutors {

        @Test
        @DisplayName("contributes one entry per executor")
        void contributesEntryPerExecutor() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor(), new OtherJobExecutor()));

            List<ContractEntry<?>> entries = contributor.contribute(new JsonObject());

            assertEquals(2, entries.size());
        }

        @Test
        @DisplayName("each entry has distinct name and address")
        void entriesHaveDistinctNameAndAddress() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new TestJobExecutor(), new OtherJobExecutor()));

            List<ContractEntry<?>> entries = contributor.contribute(new JsonObject());

            long distinctNames =
                    entries.stream().map(ContractEntry::name).distinct().count();
            assertEquals(2, distinctNames, "Both entries must have distinct names");

            long distinctAddresses = entries.stream()
                    .map(e -> e.operations().get("execute").address())
                    .distinct()
                    .count();
            assertEquals(2, distinctAddresses, "Both entries must have distinct execute addresses");
        }
    }

    @Nested
    @DisplayName("validation failures")
    class ValidationFailures {

        @Test
        @DisplayName("throws IllegalStateException when @DelayedJobContract name is invalid")
        void throwsOnInvalidContractName() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new InvalidNameJobExecutor()));

            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> contributor.contribute(new JsonObject()));
            assertTrue(ex.getMessage().contains("Invalid @DelayedJobContract name"), ex.getMessage());
        }

        @Test
        @DisplayName("throws IllegalStateException when contract interface lacks @DelayedJobContract")
        void throwsWhenAnnotationMissing() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new NoAnnotationJobExecutor()));

            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> contributor.contribute(new JsonObject()));
            assertTrue(ex.getMessage().contains("must be annotated with @DelayedJobContract"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("empty executor set")
    class EmptyExecutorSet {

        @Test
        @DisplayName("returns empty list when no executors registered")
        void returnsEmptyListForNoExecutors() {
            DelayedJobContractContributor contributor = new DelayedJobContractContributor(Set.of());

            List<ContractEntry<?>> entries = contributor.contribute(new JsonObject());

            assertNotNull(entries);
            assertTrue(entries.isEmpty());
        }
    }

    @Nested
    @DisplayName("payload type resolution")
    class PayloadTypeResolution {

        @Test
        @DisplayName("resolves Integer payload type for OtherJobExecutor")
        void resolvesIntegerPayloadType() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new OtherJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);
            ServiceMethodMeta executeMeta = entry.operations().get("execute");

            assertEquals(Integer.class, executeMeta.payloadType());
        }

        @Test
        @DisplayName("payload param type matches resolved payload type")
        void payloadParamTypeMatchesResolved() {
            DelayedJobContractContributor contributor =
                    new DelayedJobContractContributor(Set.of(new OtherJobExecutor()));

            ContractEntry<?> entry = contributor.contribute(new JsonObject()).get(0);
            ServiceMethodMeta executeMeta = entry.operations().get("execute");

            boolean hasIntegerPayload = executeMeta.params().stream()
                    .anyMatch(p -> p.source() == ParamSource.PAYLOAD && p.type() == Integer.class);
            assertFalse(
                    executeMeta.params().stream()
                            .anyMatch(p -> p.source() == ParamSource.PAYLOAD && p.type() == String.class),
                    "Should not have a String payload param for Integer executor");
            assertTrue(hasIntegerPayload, "Expected PAYLOAD param of type Integer");
        }
    }
}
