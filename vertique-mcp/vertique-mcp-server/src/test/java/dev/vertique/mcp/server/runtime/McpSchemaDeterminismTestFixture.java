// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Framework wiring for {@link McpSchemaDeterminismTest}: seed loading and shuffled tree building. */
final class McpSchemaDeterminismTestFixture {

    private static final String SEEDS_RESOURCE = "/mcp/schema/differential-corpus/random-seeds.txt";

    private McpSchemaDeterminismTestFixture() {}

    /**
     * Reads every committed seed, one per line, from the corpus resource.
     *
     * @return the seeds, in file order
     */
    static List<Long> readSeeds() {
        List<Long> seeds = new ArrayList<>();
        try (InputStream resource = McpSchemaDeterminismTestFixture.class.getResourceAsStream(SEEDS_RESOURCE);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        Objects.requireNonNull(resource, SEEDS_RESOURCE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    seeds.add(Long.parseLong(line.trim()));
                }
            }
        } catch (IOException failed) {
            throw new UncheckedIOException("failed to read the seed corpus", failed);
        }
        return List.copyOf(seeds);
    }

    /**
     * Builds the same logical JSON-005-shaped nested-object document — a root carrier with a
     * {@code label} string property and an {@code address} object property carrying {@code city} and
     * {@code street} strings — with its object members inserted in a seed-shuffled order at every
     * level (root's own members, the root's {@code properties} map, the nested object's own members,
     * and its {@code properties} map).
     *
     * @param seed the seed driving the shuffle
     * @return a fresh tree, logically equal at every seed but structurally reordered
     */
    static ObjectNode orderedDocument(long seed) {
        Random random = new Random(seed);
        JsonNodeFactory nodes = JsonNodeFactory.instance;

        ObjectNode addressProperties = nodes.objectNode();
        insertShuffled(
                addressProperties,
                random,
                Map.entry("city", stringSchema(nodes)),
                Map.entry("street", stringSchema(nodes)));

        ObjectNode address = nodes.objectNode();
        insertShuffled(
                address,
                random,
                Map.entry("type", nodes.textNode("object")),
                Map.entry("properties", addressProperties));

        ObjectNode rootProperties = nodes.objectNode();
        insertShuffled(rootProperties, random, Map.entry("label", stringSchema(nodes)), Map.entry("address", address));

        ObjectNode root = nodes.objectNode();
        insertShuffled(
                root, random, Map.entry("type", nodes.textNode("object")), Map.entry("properties", rootProperties));
        return root;
    }

    private static ObjectNode stringSchema(JsonNodeFactory nodes) {
        ObjectNode schema = nodes.objectNode();
        schema.put("type", "string");
        return schema;
    }

    @SafeVarargs
    private static void insertShuffled(ObjectNode target, Random random, Map.Entry<String, JsonNode>... entries) {
        List<Map.Entry<String, JsonNode>> shuffled = new ArrayList<>(List.of(entries));
        Collections.shuffle(shuffled, random);
        for (Map.Entry<String, JsonNode> entry : shuffled) {
            target.set(entry.getKey(), entry.getValue());
        }
    }
}
