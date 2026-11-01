// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import java.io.IOException;

/** The little the runner asks of Docker: whether the database container runs, and what it is made of. */
final class Docker {

    private Docker() {}

    /**
     * Requires the database container to be running. The runner never creates, starts, stops or removes one.
     *
     * @param container the container name, which must start with {@code wf017}
     * @throws IOException if the container is not running
     */
    static void requireRunning(String container) throws IOException {
        if (!container.startsWith("wf017")) {
            throw new IOException("The database container must be named wf017...: " + container);
        }
        String state = Shell.run(20, "docker", "inspect", "-f", "{{.State.Running}}", container);
        if (!state.equals("true")) {
            throw new IOException("The container " + container + " is not running (docker said '" + state
                    + "'); start it with the harness script");
        }
    }

    static String describe(String container) {
        return Shell.run(20, "docker", "inspect", "-f", "{{.Config.Image}} {{.Image}} created={{.Created}}", container);
    }

    static String info() {
        return Shell.run(
                20, "docker", "info", "--format", "{{.NCPU}} CPUs, {{.MemTotal}} bytes, server {{.ServerVersion}}");
    }
}
