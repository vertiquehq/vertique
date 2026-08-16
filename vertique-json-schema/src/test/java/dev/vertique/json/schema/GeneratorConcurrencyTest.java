// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves {@link AnnotationJsonSchemaGenerator}'s concurrency contract (FR-JSON-080): the complete
 * generate-and-canonicalize operation is serialized <em>per instance</em>, different instances share
 * no lock, and concurrent callers of one instance only ever observe whole, uncorrupted documents.
 *
 * <p>Overlap and non-overlap are established with latches and bounded waits only — never with {@code
 * Thread.sleep} as a synchronization device. Instrumented {@link SchemaGenerator} subclasses are
 * injected through the package-private {@link AnnotationJsonSchemaGenerator#AnnotationJsonSchemaGenerator(SchemaGenerator)}
 * seam, so a caller can be parked <em>inside</em> generation while the test observes what a second
 * caller is able to do.
 *
 * <p>Strength note: {@link #singleInstanceSerializesGeneration()} is the mutation-sensitive test —
 * removing the instance-local lock from {@code generateCanonical} makes it fail, because the second
 * caller then enters {@code generateSchema} while the first is parked inside it.
 */
@Timeout(60)
class GeneratorConcurrencyTest {

    /** Bounded wait for an event the contract says must happen. */
    private static final long AWAIT_SECONDS = 5L;

    /**
     * Bounded wait for an event the contract says must <em>not</em> happen yet. Kept short because a
     * violation is immediate: without the lock the second caller enters generation as soon as it is
     * scheduled.
     */
    private static final long NEGATIVE_PROBE_MILLIS = 500L;

    /** Threads hammering one shared generator instance. */
    private static final int HAMMER_THREADS = 8;

    /** Generation calls each hammer thread performs. */
    private static final int HAMMER_CALLS_PER_THREAD = 50;

    /** The body type every case in this class generates a schema for. */
    private static final Type FIXTURE = ProofFixtures.ConstrainedDto.class;

    @Test
    @DisplayName("one instance serializes generation: a second caller cannot enter while the first is inside")
    void singleInstanceSerializesGeneration() throws Exception {
        String reference = AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(FIXTURE);

        SerializationProbeGenerator probe = new SerializationProbeGenerator(sharedConfig());
        AnnotationJsonSchemaGenerator generator = new AnnotationJsonSchemaGenerator(probe);
        CountDownLatch secondCallerStarted = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = threads.submit(() -> generator.generateCanonical(FIXTURE));
            await(probe.firstEntered, "the first caller to enter generation");

            Future<String> second = threads.submit(() -> {
                secondCallerStarted.countDown();
                return generator.generateCanonical(FIXTURE);
            });
            await(secondCallerStarted, "the second caller to start calling");

            // The first caller is parked inside generateSchema. The second must be held outside it.
            assertFalse(
                    probe.secondEntered.await(NEGATIVE_PROBE_MILLIS, MILLISECONDS),
                    "second caller entered generation while the first was still inside it");
            assertEquals(
                    1,
                    probe.maxConcurrentCallers.get(),
                    "more than one caller was inside generation at the same time on one instance");

            probe.releaseFirst.countDown();

            assertEquals(reference, first.get(AWAIT_SECONDS, SECONDS), "first caller's document differs");
            assertEquals(reference, second.get(AWAIT_SECONDS, SECONDS), "second caller's document differs");
        } finally {
            probe.releaseFirst.countDown();
            threads.shutdownNow();
        }

        assertEquals(0, probe.secondEntered.getCount(), "the second caller never entered generation");
        assertEquals(2, probe.entries.get(), "expected exactly two generation calls");
        assertEquals(1, probe.maxConcurrentCallers.get(), "generation was not serialized per instance");
    }

    @Test
    @DisplayName("independent instances generate concurrently: no lock is shared between instances")
    void independentInstancesGenerateConcurrently() throws Exception {
        String reference = AnnotationJsonSchemaGenerator.withVictoolsDefaults().generateCanonical(FIXTURE);

        CountDownLatch release = new CountDownLatch(1);
        BarrierGenerator firstProbe = new BarrierGenerator(sharedConfig(), release);
        BarrierGenerator secondProbe = new BarrierGenerator(sharedConfig(), release);
        AnnotationJsonSchemaGenerator firstGenerator = new AnnotationJsonSchemaGenerator(firstProbe);
        AnnotationJsonSchemaGenerator secondGenerator = new AnnotationJsonSchemaGenerator(secondProbe);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = threads.submit(() -> firstGenerator.generateCanonical(FIXTURE));
            Future<String> second = threads.submit(() -> secondGenerator.generateCanonical(FIXTURE));

            // Both callers park inside generation and are only released once both have arrived: a
            // lock shared between instances would keep the second one outside and time this out.
            await(firstProbe.entered, "the first instance's caller to enter generation");
            await(secondProbe.entered, "the second instance's caller to enter generation");

            release.countDown();

            assertEquals(reference, first.get(AWAIT_SECONDS, SECONDS), "first instance's document differs");
            assertEquals(reference, second.get(AWAIT_SECONDS, SECONDS), "second instance's document differs");
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("hammering one instance yields only whole, byte-identical documents")
    void concurrentHammerProducesOnlyValidUncorruptedDocuments() throws Exception {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.withVictoolsDefaults();
        String reference = generator.generateCanonical(FIXTURE);

        List<Callable<List<String>>> tasks = new ArrayList<>(HAMMER_THREADS);
        for (int thread = 0; thread < HAMMER_THREADS; thread++) {
            tasks.add(() -> {
                List<String> documents = new ArrayList<>(HAMMER_CALLS_PER_THREAD);
                for (int call = 0; call < HAMMER_CALLS_PER_THREAD; call++) {
                    documents.add(generator.generateCanonical(FIXTURE));
                }
                return documents;
            });
        }

        ExecutorService threads = Executors.newFixedThreadPool(HAMMER_THREADS);
        List<Future<List<String>>> results;
        try {
            results = threads.invokeAll(tasks, AWAIT_SECONDS * 6, SECONDS);
        } finally {
            threads.shutdownNow();
        }

        int documents = 0;
        for (Future<List<String>> result : results) {
            assertFalse(result.isCancelled(), "a hammer thread did not finish within the bounded timeout");
            // A propagating exception fails the test here, which is the "no exceptions" assertion.
            for (String document : result.get()) {
                assertEquals(reference, document, "a concurrently generated document differed from the reference");
                documents++;
            }
        }
        assertEquals(HAMMER_THREADS * HAMMER_CALLS_PER_THREAD, documents, "not every hammer call produced a document");
    }

    // --- Probes ---

    /**
     * Victools configuration mirroring what every {@link AnnotationJsonSchemaGenerator} construction
     * mode installs in default mode, so an injected probe's documents are byte-identical to the ones
     * {@link AnnotationJsonSchemaGenerator#withVictoolsDefaults()} produces.
     *
     * @return a freshly built configuration; never shared between probes
     */
    private static SchemaGeneratorConfig sharedConfig() {
        return new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(new JacksonModule())
                .with(new JakartaValidationModule(
                        JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                        JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
                .with(new Swagger2Module())
                .build();
    }

    /**
     * Awaits an event the contract requires, failing the test rather than hanging when it does not
     * arrive.
     *
     * @param latch the latch signalling the event
     * @param what  what the test was waiting for, for the failure message
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertTrue(latch.await(AWAIT_SECONDS, SECONDS), "timed out waiting for " + what);
    }

    /**
     * Generator that parks the <em>first</em> caller inside {@code generateSchema} until released,
     * lets every later caller through, and records how many callers were inside generation at the
     * same time.
     */
    private static final class SerializationProbeGenerator extends SchemaGenerator {

        /** Signalled once the first caller is inside generation. */
        private final CountDownLatch firstEntered = new CountDownLatch(1);

        /** Signalled once a second caller is inside generation. */
        private final CountDownLatch secondEntered = new CountDownLatch(1);

        /** Releases the parked first caller. */
        private final CountDownLatch releaseFirst = new CountDownLatch(1);

        /** Total number of generation calls that entered. */
        private final AtomicInteger entries = new AtomicInteger();

        /** Callers currently inside generation. */
        private final AtomicInteger concurrentCallers = new AtomicInteger();

        /** High-water mark of {@link #concurrentCallers}. */
        private final AtomicInteger maxConcurrentCallers = new AtomicInteger();

        /**
         * @param config the Victools configuration this probe generates with
         */
        private SerializationProbeGenerator(SchemaGeneratorConfig config) {
            super(config);
        }

        @Override
        public ObjectNode generateSchema(Type mainTargetType, Type... typeParameters) {
            int entry = entries.getAndIncrement();
            maxConcurrentCallers.accumulateAndGet(concurrentCallers.incrementAndGet(), Math::max);
            try {
                if (entry == 0) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(AWAIT_SECONDS, SECONDS)) {
                        throw new IllegalStateException("the parked first caller was never released");
                    }
                } else {
                    secondEntered.countDown();
                }
                return super.generateSchema(mainTargetType, typeParameters);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while parked inside generation", interrupted);
            } finally {
                concurrentCallers.decrementAndGet();
            }
        }
    }

    /**
     * Generator that parks <em>every</em> caller inside {@code generateSchema} until a shared release
     * latch fires, so two instances can be observed inside generation simultaneously.
     */
    private static final class BarrierGenerator extends SchemaGenerator {

        /** Signalled once this instance's caller is inside generation. */
        private final CountDownLatch entered = new CountDownLatch(1);

        /** The release latch shared by every barrier probe in one test. */
        private final CountDownLatch release;

        /**
         * @param config  the Victools configuration this probe generates with
         * @param release the shared latch releasing all parked callers
         */
        private BarrierGenerator(SchemaGeneratorConfig config, CountDownLatch release) {
            super(config);
            this.release = release;
        }

        @Override
        public ObjectNode generateSchema(Type mainTargetType, Type... typeParameters) {
            entered.countDown();
            try {
                if (!release.await(AWAIT_SECONDS, SECONDS)) {
                    throw new IllegalStateException("the parked caller was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while parked inside generation", interrupted);
            }
            return super.generateSchema(mainTargetType, typeParameters);
        }
    }
}
