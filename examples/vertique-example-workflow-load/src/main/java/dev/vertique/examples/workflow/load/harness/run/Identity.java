// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import dev.vertique.examples.workflow.load.harness.spec.Canonical;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The identity of the executable code a run uses: a hash of every entry of the classpath, the module's own jar apart from
 * the dependencies.
 *
 * <p>A commit and a flag for uncommitted changes do not say what ran. The runs of a set are comparable only when the
 * same jar and the same dependencies ran in all of them, so the manifest records the hashes, and the script that starts a
 * run refuses a jar whose hashes differ from those recorded when it was built.
 *
 * <pre>
 * java -cp CLASSPATH dev.vertique.examples.workflow.load.harness.run.Identity write FILE SOURCE-STATE
 * </pre>
 */
public final class Identity {

    private Identity() {}

    /**
     * Writes the identity of the current classpath.
     *
     * @param args {@code write <file> <source-state>}
     * @throws IOException if a file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !args[0].equals("write")) {
            System.err.println("usage: write <file> <source-state>");
            System.exit(2);
        }
        JsonObject identity = compute(System.getProperty("java.class.path"));
        identity.put("sourceState", args[2]);
        Files.writeString(Path.of(args[1]), identity.encodePrettily());
    }

    /**
     * Hashes every entry of a classpath.
     *
     * @param classpath the classpath, as {@code java.class.path} holds it
     * @return the entries with their hashes, the hash of the module's jar, and the hash of the dependencies
     * @throws IOException if an entry cannot be read
     */
    public static JsonObject compute(String classpath) throws IOException {
        TreeMap<String, String> hashes = new TreeMap<>();
        String moduleJar = "";
        List<String> dependencyLines = new ArrayList<>();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry);
            String name = path.getFileName().toString();
            String hash = hashOf(path);
            hashes.put(name + "#" + hash.substring(0, 12), hash);
            if (name.startsWith("vertique-example-workflow-load")) {
                moduleJar = hash;
            } else {
                dependencyLines.add(name + ":" + hash);
            }
        }
        dependencyLines.sort(String::compareTo);
        JsonArray entries = new JsonArray();
        hashes.forEach((k, v) -> entries.add(
                new JsonObject().put("entry", k.substring(0, k.indexOf('#'))).put("sha256", v)));
        return new JsonObject()
                .put("moduleSha256", moduleJar)
                .put(
                        "dependenciesSha256",
                        Canonical.sha256(String.join("\n", dependencyLines).getBytes(StandardCharsets.UTF_8)))
                .put("entries", entries)
                .put(
                        "classpathSha256",
                        Canonical.sha256(String.join("\n", dependencyLines)
                                .concat("\n" + moduleJar)
                                .getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Compares the classpath of this process with the identity recorded at build time.
     *
     * @param classpath the classpath
     * @param recorded the file written by {@code write}
     * @return empty when the hashes agree, otherwise what differs
     * @throws IOException if a file cannot be read
     */
    public static String mismatch(String classpath, Path recorded) throws IOException {
        JsonObject now = compute(classpath);
        JsonObject then = new JsonObject(Files.readString(recorded));
        if (!now.getString("classpathSha256").equals(then.getString("classpathSha256"))) {
            return "the classpath hashes " + now.getString("classpathSha256") + " but the build recorded "
                    + then.getString("classpathSha256") + " (module jar " + now.getString("moduleSha256")
                    + " against " + then.getString("moduleSha256") + ")";
        }
        return "";
    }

    private static String hashOf(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            List<String> lines = new ArrayList<>();
            try (Stream<Path> files = Files.walk(path)) {
                for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                    lines.add(path.relativize(f) + ":" + Canonical.sha256(Files.readAllBytes(f)));
                }
            }
            return Canonical.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        }
        return Files.exists(path) ? Canonical.sha256(Files.readAllBytes(path)) : "missing";
    }
}
