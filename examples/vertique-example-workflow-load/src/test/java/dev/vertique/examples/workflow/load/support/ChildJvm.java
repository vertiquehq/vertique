// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A separate JVM process started from the test classpath, with its output in a file.
 *
 * <p>The processes the tests start are the same ones a load run starts: the launcher with a node configuration, or
 * the admin entry point. Their log files are kept so that a failing test can show what the process said.
 */
public final class ChildJvm implements AutoCloseable {

    private static final String[] JVM_FLAGS = {
        "-Xms32m", "-Xmx384m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xshare:auto"
    };

    private final String name;
    private final Process process;
    private final Path log;

    private ChildJvm(String name, Process process, Path log) {
        this.name = name;
        this.process = process;
        this.log = log;
    }

    /**
     * Starts a JVM that runs a main class on the classpath of the current test.
     *
     * @param name a label for messages
     * @param log the file that receives the process's output
     * @param workingDirectory the working directory, which decides where {@code config/} is read from
     * @param environment variables added to the environment
     * @param mainClass the class to run
     * @param arguments the program arguments
     * @return the running process
     * @throws IOException if the process cannot be started
     */
    public static ChildJvm start(
            String name,
            Path log,
            Path workingDirectory,
            Map<String, String> environment,
            String mainClass,
            List<String> arguments)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(List.of(JVM_FLAGS));
        command.add("-cp");
        command.add(classpath());
        command.add(mainClass);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        return new ChildJvm(name, builder.start(), log);
    }

    private static String classpath() {
        String surefire = System.getProperty("surefire.test.class.path");
        return surefire != null && !surefire.isBlank() ? surefire : System.getProperty("java.class.path");
    }

    /**
     * Returns whether the process is still running.
     *
     * @return {@code true} while it runs
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Waits for the process to end.
     *
     * @param timeout the longest wait
     * @return the exit code
     * @throws InterruptedException if the wait is interrupted
     * @throws IllegalStateException if the process is still running after the timeout; the message carries its log
     */
    public int waitFor(Duration timeout) throws InterruptedException {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(name + " did not exit within " + timeout + ":\n" + output());
        }
        return process.exitValue();
    }

    /**
     * Returns everything the process has written so far.
     *
     * @return the log text, or a note when the file cannot be read
     */
    public String output() {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return "(log unreadable: " + e + ")";
        }
    }

    /**
     * Returns the name given at start.
     *
     * @return the label
     */
    public String name() {
        return name;
    }

    /** Ends the process: a termination request, then a kill if it ignores it. */
    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
