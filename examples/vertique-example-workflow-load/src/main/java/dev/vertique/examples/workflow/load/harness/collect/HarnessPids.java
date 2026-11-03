// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The processes the harness started, so that CPU used by anything else can be told apart.
 *
 * <p>The runner lists every process it starts in a file, one {@code pid role} per line. The collector adds the
 * helper processes it starts itself, such as {@code top}.
 */
public final class HarnessPids {

    private static final Set<Integer> HELPERS = ConcurrentHashMap.newKeySet();

    private HarnessPids() {}

    /**
     * Reads the list.
     *
     * @param file the list file
     * @return role by pid; empty when the file does not exist
     */
    public static Map<Integer, String> read(Path file) {
        Map<Integer, String> map = new TreeMap<>();
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2) {
                        map.put(Integer.parseInt(parts[0]), parts[1]);
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            // a half-written line is read again at the next sample
        }
        return map;
    }

    /**
     * Adds a process to the list.
     *
     * @param file the list file
     * @param pid the process id
     * @param role what the process is
     * @throws IOException if the file cannot be written
     */
    public static synchronized void add(Path file, long pid, String role) throws IOException {
        Files.writeString(
                file,
                pid + " " + role + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    static void addHelper(long pid, String role) {
        HELPERS.add((int) pid);
    }

    static boolean isHelper(int pid) {
        return HELPERS.contains(pid);
    }
}
