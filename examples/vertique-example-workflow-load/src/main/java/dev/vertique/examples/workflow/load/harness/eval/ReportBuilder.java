// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.driver.LoadProfile;
import dev.vertique.examples.workflow.load.harness.eval.Gates.Result;
import dev.vertique.examples.workflow.load.harness.eval.Gates.Verdict;
import dev.vertique.examples.workflow.load.harness.eval.PointAggregator.RunVerdict;
import dev.vertique.examples.workflow.load.harness.metrics.Quantiles;
import dev.vertique.examples.workflow.load.harness.spec.Matrix;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Builds the report of a run matrix from the verdicts of the runs.
 *
 * <p>The report proves that the runs it combines compare. A run counts only when it was built from the same frozen matrix
 * file as the report, its configuration fingerprint equals the one the matrix gives its point, and it ran the same
 * executable as the other runs. Any other run is listed as excluded with the reason, and never combined. The thresholds
 * are the matrix's own.
 *
 * <p>A point has a number of replicates. A replicate may take up to the ceiling of attempts, and only the first valid
 * attempt counts; a later attempt after a valid one, or an attempt over the ceiling, is listed as a violation. A point
 * needs the minimum number of replicates with a valid run before a gate is judged. Every point of the matrix appears,
 * whether it was measured, is invalid, diagnostic, inconclusive, system-failed or not run.
 */
public final class ReportBuilder {

    private ReportBuilder() {}

    /**
     * Writes {@code report.json} and {@code report.md}.
     *
     * @param args the matrix file, the output directory and the directory that holds the run directories
     * @throws IOException if a file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: <matrix.json> <output-dir> <runs-root>");
            System.exit(2);
        }
        Matrix matrix = Matrix.load(Path.of(args[0]));
        List<JsonObject> results = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(Path.of(args[2]))) {
            for (Path dir : dirs.sorted().toList()) {
                if (Files.exists(dir.resolve("result.json"))) {
                    results.add(new JsonObject(Files.readString(dir.resolve("result.json"))));
                }
            }
        }
        JsonObject report = build(matrix, results);
        Files.createDirectories(Path.of(args[1]));
        Files.writeString(Path.of(args[1], "report.json"), report.encodePrettily());
        Files.writeString(Path.of(args[1], "report.md"), markdown(report));
    }

    /**
     * Builds the report.
     *
     * @param matrix the frozen matrix
     * @param results the {@code result.json} of every run found
     * @return the report
     */
    public static JsonObject build(Matrix matrix, List<JsonObject> results) {
        Thresholds limits = matrix.thresholds();
        JsonArray excluded = new JsonArray();
        JsonArray violations = new JsonArray();
        // The executable most runs used is the reference; a run of another build does not compare with them.
        Map<String, Integer> identities = new LinkedHashMap<>();
        for (JsonObject r : results) {
            if (!identityOf(r).isEmpty()) {
                identities.merge(identityOf(r), 1, Integer::sum);
            }
        }
        String reference = identities.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        Map<String, Map<Integer, List<JsonObject>>> byReplicate = new LinkedHashMap<>();
        for (JsonObject r : results) {
            String why = incomparable(matrix, r, reference);
            if (why != null) {
                excluded.add(new JsonObject().put("run", r.getString("name")).put("reason", why));
                continue;
            }
            byReplicate
                    .computeIfAbsent(r.getString("point"), k -> new TreeMap<>())
                    .computeIfAbsent(r.getInteger("replicate", 1), k -> new ArrayList<>())
                    .add(r);
        }
        JsonArray points = new JsonArray();
        Map<String, JsonObject> rows = new LinkedHashMap<>();
        Map<String, List<Double>> completedByPoint = new LinkedHashMap<>();
        for (Matrix.Point p : matrix.points()) {
            Map<Integer, List<JsonObject>> replicates = byReplicate.getOrDefault(p.id(), Map.of());
            List<JsonObject> counted = new ArrayList<>();
            JsonArray runRows = new JsonArray();
            for (var entry : replicates.entrySet()) {
                List<JsonObject> attempts = new ArrayList<>(entry.getValue());
                attempts.sort((a, b) -> Integer.compare(a.getInteger("attempt", 1), b.getInteger("attempt", 1)));
                JsonObject firstValid = null;
                for (JsonObject a : attempts) {
                    boolean valid = "valid".equals(a.getString("validity"));
                    String note = "";
                    if (a.getInteger("attempt", 1) > limits.maxAttemptsPerReplicate()) {
                        note = "over the ceiling of " + limits.maxAttemptsPerReplicate() + " attempts";
                        violations.add(
                                new JsonObject().put("run", a.getString("name")).put("violation", note));
                    } else if (firstValid != null) {
                        note = "run after a valid attempt; not counted";
                        violations.add(
                                new JsonObject().put("run", a.getString("name")).put("violation", note));
                    } else if (valid) {
                        firstValid = a;
                        counted.add(a);
                    }
                    runRows.add(runRow(a, note));
                }
            }
            JsonObject row = point(matrix, p, counted, replicates.size(), runRows, limits);
            points.add(row);
            rows.put(p.id(), row);
            List<Double> completed = new ArrayList<>();
            for (JsonObject r : counted) {
                if ("measured".equals(r.getString("outcome"))
                        && r.getJsonObject("load", new JsonObject()).getValue("completedInstancesPerSecond")
                                instanceof Number n) {
                    completed.add(n.doubleValue());
                } else if ("measured".equals(r.getString("outcome"))) {
                    completed.add(Double.NaN);
                }
            }
            completedByPoint.put(p.id(), completed);
        }
        JsonArray outside = new JsonArray();
        results.stream()
                .map(r -> r.getString("point", r.getString("name")))
                .distinct()
                .filter(id -> matrix.point(id) == null)
                .forEach(id -> outside.add(new JsonObject().put("point", id).put("note", "not in the matrix")));
        JsonArray groups = new JsonArray();
        for (Object o : matrix.source().getJsonArray("scalingGroups", new JsonArray())) {
            JsonObject g = (JsonObject) o;
            List<Gates.Scaling> scalings = new ArrayList<>();
            for (Object id : g.getJsonArray("points")) {
                JsonObject row = rows.get(id.toString());
                if (row == null) {
                    continue;
                }
                scalings.add(new Gates.Scaling(
                        row.getInteger("nodes"),
                        row.getDouble("offeredStartsPerSecond"),
                        completedByPoint.get(id.toString())));
            }
            Result r = Gates.scaling(scalings, limits);
            groups.add(new JsonObject().put("id", g.getString("id")).put("scaling", r.toJson()));
        }
        return new JsonObject()
                .put("title", matrix.title())
                .put("matrixSha256", matrix.sha256())
                .put("thresholds", matrix.source().getJsonObject("thresholds", new JsonObject()))
                .put("executable", reference)
                .put("points", points)
                .put("scalingGroups", groups)
                .put("excludedRuns", excluded)
                .put("violations", violations)
                .put("runsOutsideTheMatrix", outside);
    }

    private static String identityOf(JsonObject result) {
        Object code = result.getValue("code");
        return code instanceof JsonObject c ? c.getString("classpathSha256", "") : "";
    }

    private static String incomparable(Matrix matrix, JsonObject r, String reference) {
        if (!matrix.sha256().equals(r.getString("matrixSha256", ""))) {
            return "built from another matrix file (" + r.getString("matrixSha256", "none") + ")";
        }
        Matrix.Point point = matrix.point(r.getString("point", ""));
        if (point == null) {
            return "its point is not in the matrix";
        }
        if (!matrix.fingerprintOf(point.id()).equals(r.getString("fingerprint", ""))) {
            return "its configuration fingerprint differs from the one the matrix gives point " + point.id();
        }
        if (identityOf(r).isEmpty()) {
            return "records no executable identity, so it matches no other run";
        }
        if (!identityOf(r).equals(reference)) {
            return "ran another executable than the other runs";
        }
        return null;
    }

    private static JsonObject runRow(JsonObject a, String note) {
        JsonObject load = a.getJsonObject("load", new JsonObject());
        JsonObject stalls = a.getJsonObject("stalls", new JsonObject());
        return new JsonObject()
                .put("run", a.getString("name"))
                .put("replicate", a.getInteger("replicate", 1))
                .put("attempt", a.getInteger("attempt", 1))
                .put("validity", a.getString("validity"))
                .put("outcome", a.getString("outcome"))
                .put("violations", a.getJsonArray("violations"))
                .put("systemFailure", a.getValue("systemFailure"))
                .put("completedInstancesPerSecond", load.getValue("completedInstancesPerSecond"))
                .put("stalls", stalls.getValue("count"))
                .put("stallsWithoutCoincidentCondition", stalls.getValue("withoutCoincidentCondition"))
                .put("note", note);
    }

    private static double offeredPerSecond(Matrix matrix, String id) {
        LoadProfile profile = matrix.specOf(id).load().profile();
        long offered = 0;
        for (LoadProfile.Segment s : profile.segments()) {
            if (s.window()) {
                offered += s.arrivals();
            }
        }
        double seconds = profile.windowEndSec() - profile.windowStartSec();
        return seconds > 0 ? offered / seconds : Double.NaN;
    }

    private static JsonObject point(
            Matrix matrix,
            Matrix.Point p,
            List<JsonObject> counted,
            int replicatesFound,
            JsonArray runRows,
            Thresholds limits) {
        List<RunVerdict> claim = new ArrayList<>();
        List<RunVerdict> overload = new ArrayList<>();
        List<RunVerdict> lock = new ArrayList<>();
        int systemFailed = 0;
        for (JsonObject r : counted) {
            JsonObject gates = r.getValue("gates") instanceof JsonObject g ? g : new JsonObject();
            claim.add(new RunVerdict(true, verdictOf(gates, "claimLatency")));
            overload.add(new RunVerdict(true, verdictOf(gates, "overload")));
            lock.add(new RunVerdict(true, verdictOf(gates, "notificationLock")));
            if ("system-failed".equals(r.getString("outcome"))) {
                systemFailed++;
            }
        }
        List<Double> completed = new ArrayList<>();
        for (JsonObject r : counted) {
            if (r.getJsonObject("load", new JsonObject()).getValue("completedInstancesPerSecond") instanceof Number n) {
                completed.add(n.doubleValue());
            }
        }
        String status;
        if (replicatesFound == 0) {
            status = "not run";
        } else if (counted.isEmpty()) {
            status = "invalid";
        } else if (systemFailed == counted.size()) {
            status = "system-failed";
        } else if (p.diagnostic()) {
            status = "diagnostic";
        } else if (counted.size() < limits.minValidRuns()) {
            status = "inconclusive";
        } else {
            status = "measured";
        }
        JsonObject row = new JsonObject()
                .put("id", p.id())
                .put("set", p.set())
                .put("arm", matrix.specOf(p.id()).arm().name())
                .put("nodes", matrix.specOf(p.id()).nodes())
                .put("diagnostic", p.diagnostic())
                .put("plannedReplicates", p.replicates())
                .put("replicatesWithARun", replicatesFound)
                .put("replicatesCounted", counted.size())
                .put("status", status)
                .put("offeredStartsPerSecond", offeredPerSecond(matrix, p.id()))
                .put(
                        "medianCompletedInstancesPerSecond",
                        completed.isEmpty()
                                ? null
                                : Quantiles.median(completed.stream()
                                        .mapToDouble(Double::doubleValue)
                                        .toArray()))
                .put("runs", runRows);
        if (replicatesFound > 0) {
            row.put(
                            "claimLatency",
                            PointAggregator.aggregate("claim-latency", claim, p.diagnostic(), limits)
                                    .toJson())
                    .put(
                            "overload",
                            PointAggregator.aggregate("overload", overload, p.diagnostic(), limits)
                                    .toJson())
                    .put(
                            "notificationLock",
                            PointAggregator.aggregate("notification-lock", lock, p.diagnostic(), limits)
                                    .toJson());
        }
        return row;
    }

    private static Verdict verdictOf(JsonObject gates, String key) {
        JsonObject g = gates.getJsonObject(key);
        if (g == null) {
            return Verdict.NOT_APPLICABLE;
        }
        return Verdict.valueOf(g.getString("verdict").toUpperCase(Locale.ROOT));
    }

    /**
     * Renders the report as Markdown.
     *
     * @param report the report
     * @return the text
     */
    public static String markdown(JsonObject report) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(report.getString("title")).append("\n\n");
        out.append("Matrix file hash `")
                .append(report.getString("matrixSha256"))
                .append("`, executable `")
                .append(report.getString("executable"))
                .append("`.\n\n");
        out.append(
                "| Point | Set | Arm | Nodes | Offered/s | Replicates (counted/with a run/planned) | Status | Completed/s (median) | Claim latency | Overload | Notification lock |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Object o : report.getJsonArray("points")) {
            JsonObject p = (JsonObject) o;
            out.append("| ")
                    .append(p.getString("id"))
                    .append(" | ")
                    .append(p.getString("set"))
                    .append(" | ")
                    .append(p.getString("arm"))
                    .append(" | ")
                    .append(p.getInteger("nodes"))
                    .append(" | ")
                    .append(fmt(p.getValue("offeredStartsPerSecond")))
                    .append(" | ")
                    .append(p.getInteger("replicatesCounted"))
                    .append('/')
                    .append(p.getInteger("replicatesWithARun"))
                    .append('/')
                    .append(p.getInteger("plannedReplicates"))
                    .append(" | ")
                    .append(p.getString("status"))
                    .append(" | ")
                    .append(fmt(p.getValue("medianCompletedInstancesPerSecond")))
                    .append(" | ")
                    .append(verdict(p, "claimLatency"))
                    .append(" | ")
                    .append(verdict(p, "overload"))
                    .append(" | ")
                    .append(verdict(p, "notificationLock"))
                    .append(" |\n");
        }
        out.append("\n## Scaling\n\n");
        for (Object o : report.getJsonArray("scalingGroups")) {
            JsonObject g = (JsonObject) o;
            out.append("- **")
                    .append(g.getString("id"))
                    .append(":** ")
                    .append(g.getJsonObject("scaling").getString("verdict"))
                    .append(" - ")
                    .append(g.getJsonObject("scaling").getString("detail"))
                    .append('\n');
        }
        out.append("\n## Runs excluded from every figure\n\n");
        for (Object o : report.getJsonArray("excludedRuns")) {
            JsonObject e = (JsonObject) o;
            out.append("- ")
                    .append(e.getString("run"))
                    .append(": ")
                    .append(e.getString("reason"))
                    .append('\n');
        }
        out.append("\n## Violations of the replicate rules\n\n");
        for (Object o : report.getJsonArray("violations")) {
            JsonObject e = (JsonObject) o;
            out.append("- ")
                    .append(e.getString("run"))
                    .append(": ")
                    .append(e.getString("violation"))
                    .append('\n');
        }
        out.append("\n## Runs\n\n");
        for (Object o : report.getJsonArray("points")) {
            JsonObject p = (JsonObject) o;
            for (Object r : p.getJsonArray("runs")) {
                JsonObject run = (JsonObject) r;
                out.append("- ")
                        .append(p.getString("id"))
                        .append(" / ")
                        .append(run.getString("run"))
                        .append(": ")
                        .append(run.getString("validity"))
                        .append(", ")
                        .append(run.getString("outcome"))
                        .append(' ')
                        .append(run.getJsonArray("violations").encode())
                        .append(", stalls ")
                        .append(run.getValue("stalls"))
                        .append(" (without a coincident condition ")
                        .append(run.getValue("stallsWithoutCoincidentCondition"))
                        .append(")\n");
            }
        }
        return out.toString();
    }

    private static String verdict(JsonObject p, String gate) {
        JsonObject g = p.getJsonObject(gate);
        return g == null ? "-" : g.getString("verdict");
    }

    private static String fmt(Object value) {
        return value instanceof Number n ? String.format(Locale.ROOT, "%.1f", n.doubleValue()) : "-";
    }
}
