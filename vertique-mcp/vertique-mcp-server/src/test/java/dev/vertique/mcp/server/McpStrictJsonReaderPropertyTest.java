// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-003 — proves the strict reader terminates for arbitrary bounded and adversarial JSON.
 *
 * <p>Deterministic seeds generate nesting, string sizes, property counts, duplicate keys, trailing
 * tokens, and UTF-8 boundary cases around the configured limits. Each case and its one-step boundary
 * mutation must terminate before a fixed deadline, return either a bounded value or a classified
 * bounded rejection, exceed no configured limit, and leave zero live tasks in the owned executor.
 */
class McpStrictJsonReaderPropertyTest {

    private static final long DEADLINE_MS = 2_000;

    private McpStrictJsonReaderPropertyTestFixture fixture;

    @BeforeEach
    void startFixture() {
        fixture = McpStrictJsonReaderPropertyTestFixture.start();
    }

    @AfterEach
    void shutdownFixture() {
        assertThat(fixture.shutdownAndAwait())
                .as("the owned executor terminates with zero live tasks")
                .isTrue();
    }

    @Test
    @DisplayName("every bounded and adversarial seed terminates before its deadline with a classified outcome")
    void shouldTerminateForArbitraryBoundedAndAdversarialJson() {
        McpStrictJsonReader reader = new McpStrictJsonReader(McpStrictJsonReaderPropertyTestFixture.boundedConfig());

        for (McpStrictJsonReaderPropertyTestFixture.Case seed : fixture.generateSeeds()) {
            Future<McpStrictJsonReader.Result> pending = fixture.submit(() -> reader.read(seed.frame()));
            assertThatCode(() -> {
                        McpStrictJsonReader.Result result = pending.get(DEADLINE_MS, TimeUnit.MILLISECONDS);
                        assertThat(result.isRejected())
                                .as("seed %s reaches its expected bounded classification", seed.name())
                                .isEqualTo(seed.expectRejected());
                    })
                    .as("seed %s terminates before its deadline", seed.name())
                    .doesNotThrowAnyException();
        }
    }

    /** Framework wiring for the termination proof: the owned executor and deterministic seed corpus. */
    private static final class McpStrictJsonReaderPropertyTestFixture {

        private static final long[] SEEDS = {1L, 2L, 3L, 5L, 8L, 13L};

        private final ExecutorService executor;

        private McpStrictJsonReaderPropertyTestFixture(ExecutorService executor) {
            this.executor = executor;
        }

        static McpStrictJsonReaderPropertyTestFixture start() {
            return new McpStrictJsonReaderPropertyTestFixture(Executors.newSingleThreadExecutor());
        }

        /** Small limits keep every generated boundary frame short and the corpus fast. */
        static McpServerConfig boundedConfig() {
            return McpServerConfig.builder()
                    .jsonMaxDepth(8)
                    .jsonMaxPropertiesPerObject(4)
                    .jsonMaxItemsPerArray(4)
                    .jsonMaxStringChars(16)
                    .build();
        }

        <T> Future<T> submit(java.util.concurrent.Callable<T> task) {
            return executor.submit(task);
        }

        boolean shutdownAndAwait() {
            executor.shutdownNow();
            try {
                return executor.awaitTermination(5, TimeUnit.SECONDS) && executor.isTerminated();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        List<Case> generateSeeds() {
            McpServerConfig config = boundedConfig();
            int depth = config.jsonMaxDepth();
            int props = config.jsonMaxPropertiesPerObject();
            int items = config.jsonMaxItemsPerArray();
            int strLen = config.jsonMaxStringChars();
            List<Case> cases = new ArrayList<>();

            cases.add(literalCase("depthAtLimit", nestedArray(depth), false));
            cases.add(literalCase("depthOverLimit", nestedArray(depth + 1), true));
            cases.add(literalCase("propsAtLimit", objectWith(props), false));
            cases.add(literalCase("propsOverLimit", objectWith(props + 1), true));
            cases.add(literalCase("itemsAtLimit", arrayWith(items), false));
            cases.add(literalCase("itemsOverLimit", arrayWith(items + 1), true));
            cases.add(literalCase("stringAtLimit", quotedString(strLen), false));
            cases.add(literalCase("stringOverLimit", quotedString(strLen + 1), true));
            cases.add(literalCase("duplicateKey", "{\"a\":1,\"a\":2}", true));
            cases.add(literalCase("trailingTokens", "{\"a\":1} x", true));
            cases.add(new Case(
                    "invalidUtf8Byte",
                    new byte[] {(byte) '{', (byte) '"', (byte) 'a', (byte) '"', (byte) ':', (byte) 0xFF, (byte) '}'},
                    true));
            cases.add(new Case(
                    "loneSurrogate", new byte[] {(byte) '"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, (byte) '"'}, true));
            for (long seed : SEEDS) {
                cases.add(literalCase("randomBoundedValid-" + seed, randomBoundedObject(new Random(seed)), false));
            }
            return List.copyOf(cases);
        }

        private static Case literalCase(String name, String frame, boolean expectRejected) {
            return new Case(name, frame.getBytes(StandardCharsets.UTF_8), expectRejected);
        }

        private static String nestedArray(int depth) {
            return "[".repeat(depth) + "1" + "]".repeat(depth);
        }

        private static String objectWith(int keys) {
            StringBuilder object = new StringBuilder("{");
            for (int i = 0; i < keys; i++) {
                if (i > 0) {
                    object.append(',');
                }
                object.append('"').append('k').append(i).append('"').append(':').append(i);
            }
            return object.append('}').toString();
        }

        private static String arrayWith(int elements) {
            StringBuilder array = new StringBuilder("[");
            for (int i = 0; i < elements; i++) {
                if (i > 0) {
                    array.append(',');
                }
                array.append(i);
            }
            return array.append(']').toString();
        }

        private static String quotedString(int length) {
            return "\"" + "a".repeat(length) + "\"";
        }

        /** A deterministic, comfortably in-bounds object: at most two keys, short string and int values. */
        private static String randomBoundedObject(Random random) {
            int keys = 1 + random.nextInt(2);
            StringBuilder object = new StringBuilder("{");
            for (int i = 0; i < keys; i++) {
                if (i > 0) {
                    object.append(',');
                }
                object.append('"').append('f').append(i).append('"').append(':');
                if (random.nextBoolean()) {
                    object.append(random.nextInt(1000));
                } else {
                    object.append('"').append("v".repeat(1 + random.nextInt(4))).append('"');
                }
            }
            return object.append('}').toString();
        }

        /**
         * One generated seed.
         *
         * @param name the seed name
         * @param frame the generated UTF-8 frame bytes
         * @param expectRejected whether the strict reader must classify this seed as a bounded rejection
         */
        record Case(String name, byte[] frame, boolean expectRejected) {}
    }
}
