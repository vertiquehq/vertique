// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Reconciles what the driver was told with what the database holds.
 *
 * <p>The driver's account says which start keys a node acknowledged. The snapshot of the database says which start keys
 * are durable and what became of each instance. The two can differ in ways that matter: an acknowledged start that is not
 * durable would be a lost write; a start whose answer never arrived (a timeout, a reset, an unanswered request) may still
 * be durable, which is a lost response and not a lost start. Neither may vanish from the figures, so every key is placed in
 * one class.
 */
public final class Reconciliation {

    private Reconciliation() {}

    /**
     * Reconciles the driver's arrivals with the durable starts.
     *
     * @param arrivals the driver's {@code arrivals.csv}
     * @param durable the gzip-compressed {@code key,status} lines of the snapshot
     * @param runName the name of the run, the prefix of every start key
     * @return the classes of keys, and the final outcome of the durable ones
     * @throws IOException if a file cannot be read
     */
    public static JsonObject compute(Path arrivals, Path durable, String runName) throws IOException {
        Map<String, Integer> best = new HashMap<>();
        CsvTable table = CsvTable.read(arrivals);
        for (int i = 0; i < table.size(); i++) {
            String origin = table.text(i, "origin");
            String key = runName + "-" + (origin.isEmpty() ? table.text(i, "idx") : origin);
            best.merge(key, rank(table.text(i, "outcome")), Math::max);
        }
        Map<String, String> status = new HashMap<>();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(Files.newInputStream(durable)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                int comma = line.lastIndexOf(',');
                status.put(line.substring(0, comma), line.substring(comma + 1));
            }
        }
        long acceptedDurable = 0;
        long acceptedNotDurable = 0;
        long lostResponseDurable = 0;
        long lostResponseNotDurable = 0;
        long refusedOnlyDurable = 0;
        long refusedOnlyNotDurable = 0;
        long notSubmittedDurable = 0;
        long notSubmitted = 0;
        Set<String> sent = new HashSet<>(best.keySet());
        Map<String, Long> finalOutcomes = new TreeMap<>();
        for (Map.Entry<String, Integer> e : best.entrySet()) {
            boolean durableKey = status.containsKey(e.getKey());
            switch (e.getValue()) {
                case 3 -> {
                    if (durableKey) {
                        acceptedDurable++;
                    } else {
                        acceptedNotDurable++;
                    }
                }
                case 2 -> {
                    if (durableKey) {
                        lostResponseDurable++;
                    } else {
                        lostResponseNotDurable++;
                    }
                }
                case 1 -> {
                    if (durableKey) {
                        refusedOnlyDurable++;
                    } else {
                        refusedOnlyNotDurable++;
                    }
                }
                default -> {
                    if (durableKey) {
                        notSubmittedDurable++;
                    } else {
                        notSubmitted++;
                    }
                }
            }
            if (durableKey) {
                finalOutcomes.merge(status.get(e.getKey()), 1L, Long::sum);
            }
        }
        long durableNotFromDriver =
                status.keySet().stream().filter(k -> !sent.contains(k)).count();
        JsonObject outcomes = new JsonObject();
        finalOutcomes.forEach(outcomes::put);
        return new JsonObject()
                .put("keys", best.size())
                .put("durableStarts", status.size())
                .put("acknowledgedAndDurable", acceptedDurable)
                .put("acknowledgedNotDurable", acceptedNotDurable)
                .put("noAnswerButDurable", lostResponseDurable)
                .put("noAnswerAndNotDurable", lostResponseNotDurable)
                .put("refusedOnlyButDurable", refusedOnlyDurable)
                .put("refusedOnlyAndNotDurable", refusedOnlyNotDurable)
                .put("notSubmittedButDurable", notSubmittedDurable)
                .put("notSubmitted", notSubmitted)
                .put("durableNotSentByThisDriver", durableNotFromDriver)
                .put("finalOutcomeOfDurableStarts", outcomes)
                .put(
                        "consistent",
                        acceptedNotDurable == 0
                                && refusedOnlyDurable == 0
                                && notSubmittedDurable == 0
                                && durableNotFromDriver == 0)
                .put(
                        "note",
                        "noAnswerButDurable are lost responses: the node recorded the start and the driver never heard;"
                                + " acknowledgedNotDurable would be a lost write");
    }

    private static int rank(String outcome) {
        return switch (outcome) {
            case "ACCEPTED" -> 3;
            case "FAILED", "UNANSWERED" -> 2;
            case "REFUSED" -> 1;
            default -> 0;
        };
    }
}
