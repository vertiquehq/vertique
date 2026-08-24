// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.support;

import dev.vertique.core.config.ConfigSecretRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Test-scope subprocess runner shared by MCP conformance and client fixtures. */
final class McpSubprocessHarness {

    private static final String REDACTED = "[REDACTED]";
    private static final Duration DESCENDANT_POLL_INTERVAL = Duration.ofMillis(20);
    private static final Set<String> SUBPROCESS_SENSITIVE_KEY_FRAGMENTS =
            Set.of("PASSWD", "AUTHORIZATION", "SESSION_KEY", "SSH_AUTH");

    private final Duration timeout;

    McpSubprocessHarness(Duration timeout) {
        if (Objects.requireNonNull(timeout, "timeout").isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    Execution<Void> run(Invocation invocation) throws Exception {
        return run(invocation, (workspace, result) -> null);
    }

    /**
     * Runs one child in an owner-only workspace. The environment is scrubbed before launch, output
     * is redacted before it is returned, and all observed descendants are terminated before the
     * workspace is deleted. The reader runs after result capture but before workspace cleanup.
     */
    <T> Execution<T> run(Invocation invocation, WorkspaceReader<T> reader) throws Exception {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(reader, "reader");

        Path workspace = createRestrictedWorkspace();
        Process process = null;
        boolean terminationCompleted = false;
        Set<ProcessHandle> observedDescendants = new LinkedHashSet<>();
        try {
            Path stdout = workspace.resolve("stdout.log");
            Path stderr = workspace.resolve("stderr.log");
            Environment environment = scrubbedEnvironment(invocation, workspace);

            ProcessBuilder builder = new ProcessBuilder(invocation.command());
            builder.directory(workspace.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment.values());
            builder.redirectOutput(stdout.toFile());
            builder.redirectError(stderr.toFile());

            process = builder.start();
            try (var standardInput = process.getOutputStream()) {
                standardInput.write(invocation.standardInput());
            }

            Settlement settlement = awaitSettlement(process, observedDescendants);
            terminationCompleted = true;
            String capturedStdout = redact(Files.readString(stdout), environment.sensitiveValues());
            String capturedStderr = redact(Files.readString(stderr), environment.sensitiveValues());
            Result result = new Result(
                    workspace,
                    process.pid(),
                    process.exitValue(),
                    settlement.timedOut(),
                    settlement.forcedTerminationCount(),
                    settlement.timedOut() ? 0 : 1,
                    capturedStdout,
                    capturedStderr,
                    observedDescendants.stream()
                            .map(ProcessHandle::pid)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            return new Execution<>(result, reader.read(workspace, result));
        } finally {
            try {
                if (process != null && !terminationCompleted) {
                    terminateAlive(process, observedDescendants);
                }
            } finally {
                deleteRecursively(workspace);
            }
        }
    }

    private Settlement awaitSettlement(Process process, Set<ProcessHandle> observedDescendants)
            throws InterruptedException, IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive()) {
            process.descendants().forEach(observedDescendants::add);
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            long waitNanos = Math.min(remainingNanos, DESCENDANT_POLL_INTERVAL.toNanos());
            process.waitFor(waitNanos, TimeUnit.NANOSECONDS);
        }
        process.descendants().forEach(observedDescendants::add);

        boolean timedOut = process.isAlive();
        int forcedTerminationCount = terminateAlive(process, observedDescendants);
        return new Settlement(timedOut, forcedTerminationCount);
    }

    private int terminateAlive(Process process, Set<ProcessHandle> observedDescendants)
            throws InterruptedException, IOException {
        process.descendants().forEach(observedDescendants::add);
        List<ProcessHandle> terminationOrder = new ArrayList<>(observedDescendants);
        terminationOrder.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        terminationOrder.add(process.toHandle());

        int forcedTerminationCount = 0;
        for (ProcessHandle handle : terminationOrder) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
                forcedTerminationCount++;
            }
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        while (terminationOrder.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(DESCENDANT_POLL_INTERVAL.toMillis());
        }
        List<Long> survivors = terminationOrder.stream()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .toList();
        if (!survivors.isEmpty()) {
            throw new IOException("subprocesses survived forced termination: " + survivors);
        }
        return forcedTerminationCount;
    }

    private static Path createRestrictedWorkspace() throws IOException {
        try {
            return Files.createTempDirectory(
                    "vertique-mcp-subprocess-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException unsupported) {
            Path workspace = Files.createTempDirectory("vertique-mcp-subprocess-");
            boolean restricted = workspace.toFile().setReadable(false, false)
                    && workspace.toFile().setWritable(false, false)
                    && workspace.toFile().setExecutable(false, false)
                    && workspace.toFile().setReadable(true, true)
                    && workspace.toFile().setWritable(true, true)
                    && workspace.toFile().setExecutable(true, true);
            if (!restricted) {
                deleteRecursively(workspace);
                throw new IOException("could not restrict subprocess workspace to its owner");
            }
            return workspace;
        }
    }

    private static Environment scrubbedEnvironment(Invocation invocation, Path workspace) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> sensitiveValues = new HashSet<>(invocation.sensitiveValues());
        copyNonSensitiveEnvironment(System.getenv(), values, sensitiveValues);
        copyNonSensitiveEnvironment(invocation.environment(), values, sensitiveValues);
        String workspaceValue = workspace.toString();
        values.put("TMPDIR", workspaceValue);
        values.put("TMP", workspaceValue);
        values.put("TEMP", workspaceValue);
        List<String> redactions = sensitiveValues.stream()
                .filter(value -> !value.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        return new Environment(Map.copyOf(values), redactions);
    }

    private static void copyNonSensitiveEnvironment(
            Map<String, String> source, Map<String, String> target, Set<String> sensitiveValues) {
        source.forEach((key, value) -> {
            if (isSensitiveKey(key)) {
                sensitiveValues.add(value);
            } else {
                target.put(key, value);
            }
        });
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toUpperCase(Locale.ROOT);
        return ConfigSecretRenderer.isSensitivePath(key)
                || SUBPROCESS_SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    private static String redact(String captured, List<String> sensitiveValues) {
        String redacted = captured;
        for (String sensitiveValue : sensitiveValues) {
            redacted = redacted.replace(sensitiveValue, REDACTED);
        }
        return redacted;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    record Invocation(
            List<String> command, Map<String, String> environment, byte[] standardInput, Set<String> sensitiveValues) {

        Invocation {
            command = List.copyOf(command);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
            environment = Map.copyOf(environment);
            standardInput = standardInput.clone();
            sensitiveValues = Set.copyOf(sensitiveValues);
        }

        static Invocation of(List<String> command) {
            return new Invocation(command, Map.of(), new byte[0], Set.of());
        }

        Invocation withEnvironment(Map<String, String> environment) {
            return new Invocation(command, environment, standardInput, sensitiveValues);
        }

        Invocation withStandardInput(String standardInput) {
            return new Invocation(
                    command, environment, standardInput.getBytes(StandardCharsets.UTF_8), sensitiveValues);
        }

        Invocation withSensitiveValues(Set<String> sensitiveValues) {
            return new Invocation(command, environment, standardInput, sensitiveValues);
        }

        @Override
        public byte[] standardInput() {
            return standardInput.clone();
        }
    }

    record Result(
            Path workspace,
            long rootPid,
            int exitCode,
            boolean timedOut,
            int forcedTerminationCount,
            int normalExitCount,
            String stdout,
            String stderr,
            Set<Long> observedDescendantPids) {}

    record Execution<T>(Result result, T workspaceValue) {}

    @FunctionalInterface
    interface WorkspaceReader<T> {
        T read(Path workspace, Result result) throws Exception;
    }

    private record Environment(Map<String, String> values, List<String> sensitiveValues) {}

    private record Settlement(boolean timedOut, int forcedTerminationCount) {}
}
