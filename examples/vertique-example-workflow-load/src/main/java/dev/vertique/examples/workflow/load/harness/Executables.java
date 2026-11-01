// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds the host tools the harness starts in a fixed list of system directories, so that a run never starts a program
 * that only a writable directory on {@code PATH} happens to provide.
 */
public final class Executables {

    private static final List<Path> DIRECTORIES = List.of(
            Path.of("/usr/bin"),
            Path.of("/bin"),
            Path.of("/usr/sbin"),
            Path.of("/sbin"),
            Path.of("/usr/local/bin"),
            Path.of("/opt/homebrew/bin"),
            Path.of("/Applications/Docker.app/Contents/Resources/bin"),
            Path.of(System.getProperty("user.home", "/nonexistent"), ".docker", "bin"));

    private Executables() {}

    /**
     * Returns the absolute path of a tool.
     *
     * @param name the file name of the tool, such as {@code ps}
     * @return the absolute path of the first executable of that name in the fixed directories
     * @throws IOException if no directory holds the tool
     */
    public static String path(String name) throws IOException {
        for (Path directory : DIRECTORIES) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IOException("Cannot run " + name + ": it is not in " + DIRECTORIES);
    }
}
