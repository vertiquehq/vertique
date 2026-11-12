// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a short command and returns what it printed. */
final class Shell {

    private Shell() {}

    /**
     * Runs a command.
     *
     * @param timeoutSeconds the longest wait
     * @param command the command and its arguments
     * @return the combined output, trimmed; empty when the command could not run or timed out
     */
    static String run(int timeoutSeconds, String... command) {
        return run(timeoutSeconds, null, command);
    }

    static String run(int timeoutSeconds, Path directory, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(List.of(command)).redirectErrorStream(true);
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(output, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
