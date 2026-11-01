// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The run matrix and its thresholds in one file that is frozen before a measurement set starts.
 *
 * <p>Every run of a set is built from this file, never from a hand-written specification: the point names the arm, the
 * node count, the load and the configuration; the file's {@code thresholds} are the only thresholds; the order lists
 * every replicate in the order they run. The file's hash is recorded in each run, so the report can refuse to combine
 * runs built from different files. A point's expected fingerprint comes from the same construction a run uses, so a run
 * whose configuration differs in any input is found by comparing fingerprints.
 *
 * <pre>{@code
 * {
 *   "title": "...", "version": 1,
 *   "thresholds": { },
 *   "points": [{"id": "cal-on-4", "set": "calibration", "diagnostic": false, "replicates": 1,
 *               "spec": {"arm": "ON", "nodes": 4, "load": { }, "node": { }}}],
 *   "scalingGroups": [{"id": "scaling-on", "points": ["scaling-on-1", "scaling-on-4"]}],
 *   "order": [{"point": "cal-on-4", "replicate": 1}],
 *   "floorRun": "floor"
 * }
 * }</pre>
 */
public final class Matrix {

    /**
     * One point of the matrix.
     *
     * @param id the id of the point
     * @param set the set it belongs to
     * @param diagnostic whether the set is diagnostic
     * @param replicates the replicates planned
     * @param spec the run specification without identity and thresholds
     */
    public record Point(String id, String set, boolean diagnostic, int replicates, JsonObject spec) {}

    private static final Set<String> FORBIDDEN_IN_POINT = Set.of(
            "name", "attempt", "replicate", "point", "session", "matrixSha256", "set", "diagnostic", "thresholds");

    private final String sha256;
    private final String title;
    private final JsonObject thresholds;
    private final Map<String, Point> points = new LinkedHashMap<>();
    private final JsonObject source;
    private final String session;

    private Matrix(String sha256, JsonObject source) {
        this.sha256 = sha256;
        this.source = source;
        SpecReader r = SpecReader.root(source);
        this.title = r.string("title", "Run matrix");
        r.longValue("version", 1, 1, 1_000);
        this.session = r.string("session", "");
        this.thresholds = r.rawObject("thresholds");
        Thresholds.parse(thresholds);
        for (SpecReader p : r.objects("points")) {
            String id = p.requiredString("id");
            JsonObject spec = p.rawObject("spec");
            for (String key : FORBIDDEN_IN_POINT) {
                if (spec.containsKey(key)) {
                    throw new ConfigurationException(
                            "Point " + id + " sets " + key + " in its spec; the matrix owns it");
                }
            }
            Point point = new Point(
                    id,
                    p.string("set", "adhoc"),
                    p.bool("diagnostic", false),
                    p.intValue("replicates", 1, 1, 1000),
                    spec);
            if (points.put(id, point) != null) {
                throw new ConfigurationException("Point " + id + " appears twice");
            }
        }
        for (SpecReader g : r.objects("scalingGroups")) {
            g.requiredString("id");
            for (String id : g.stringList("points", List.of())) {
                if (!points.containsKey(id)) {
                    throw new ConfigurationException("A scaling group names point " + id + ", which the matrix lacks");
                }
            }
        }
        r.string("floorRun", "");
        for (SpecReader o : r.objects("order")) {
            String id = o.requiredString("point");
            int replicate = o.requiredInt("replicate", 1, 1_000);
            Point point = points.get(id);
            if (point == null || replicate > point.replicates()) {
                throw new ConfigurationException(
                        "The order names " + id + " replicate " + replicate + ", which is not planned");
            }
        }
        r.finish();
        for (String id : points.keySet()) {
            specFor(id, 1, 1);
        }
    }

    /**
     * Loads a matrix file.
     *
     * @param file the file
     * @return the matrix; every point parses as a run specification
     * @throws IOException if the file cannot be read
     * @throws ConfigurationException if an input is unknown or invalid
     */
    public static Matrix load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return new Matrix(
                Canonical.sha256(bytes), new JsonObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Returns the hash of the file.
     *
     * @return the SHA-256 in hexadecimal
     */
    public String sha256() {
        return sha256;
    }

    /**
     * Returns the file's content, for the report.
     *
     * @return the parsed file
     */
    public JsonObject source() {
        return source;
    }

    /**
     * Returns the thresholds of the matrix.
     *
     * @return the thresholds every run and the report use
     */
    public Thresholds thresholds() {
        return Thresholds.parse(thresholds);
    }

    /**
     * Returns the points in file order.
     *
     * @return the points
     */
    public List<Point> points() {
        return new ArrayList<>(points.values());
    }

    /**
     * Returns a point.
     *
     * @param id the id
     * @return the point, or {@code null}
     */
    public Point point(String id) {
        return points.get(id);
    }

    /**
     * Returns the title.
     *
     * @return the title
     */
    public String title() {
        return title;
    }

    /**
     * Builds the specification of one attempt at one replicate of a point.
     *
     * @param id the point
     * @param replicate the replicate, from 1
     * @param attempt the attempt, from 1
     * @return the specification, ready for {@link RunSpec#parse}
     */
    public JsonObject specFor(String id, int replicate, int attempt) {
        Point point = points.get(id);
        if (point == null) {
            throw new ConfigurationException("The matrix has no point " + id);
        }
        if (replicate > point.replicates()) {
            throw new ConfigurationException("Point " + id + " plans " + point.replicates() + " replicates");
        }
        JsonObject spec = point.spec()
                .copy()
                .put("name", id + "-r" + replicate + "-a" + attempt)
                .put("set", point.set())
                .put("point", id)
                .put("diagnostic", point.diagnostic())
                .put("replicate", replicate)
                .put("attempt", attempt)
                .put("matrixSha256", sha256)
                .put("session", session)
                .put("thresholds", thresholds.copy());
        RunSpec.parse(spec);
        return spec;
    }

    /**
     * Returns the fingerprint every run of a point must have.
     *
     * @param id the point
     * @return the fingerprint
     */
    public String fingerprintOf(String id) {
        return RunSpec.parse(specFor(id, 1, 1)).fingerprint();
    }

    /**
     * Returns the specification parsed for a point.
     *
     * @param id the point
     * @return the parsed first replicate
     */
    public RunSpec specOf(String id) {
        return RunSpec.parse(specFor(id, 1, 1));
    }
}
