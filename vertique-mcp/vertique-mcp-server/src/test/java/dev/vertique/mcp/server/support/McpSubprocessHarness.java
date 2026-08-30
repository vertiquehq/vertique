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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Test-scope subprocess runner shared by MCP conformance and client fixtures. */
public final class McpSubprocessHarness {

    private static final String REDACTED = "[REDACTED]";
    private static final Duration DESCENDANT_POLL_INTERVAL = Duration.ofMillis(20);
    private static final Set<String> SUBPROCESS_SENSITIVE_KEY_FRAGMENTS =
            Set.of("PASSWD", "AUTHORIZATION", "SESSION_KEY", "SSH_AUTH");
    private static final Set<String> AMBIENT_ENVIRONMENT_ALLOWLIST =
            Set.of("PATH", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "LANG", "LC_ALL", "TZ");
    private static final Set<String> HARNESS_OWNED_ENVIRONMENT = Set.of(
            "HOME",
            "USERPROFILE",
            "HOMEDRIVE",
            "HOMEPATH",
            "XDG_CONFIG_HOME",
            "XDG_CACHE_HOME",
            "XDG_DATA_HOME",
            "XDG_STATE_HOME",
            "TMPDIR",
            "TMP",
            "TEMP",
            "NPM_CONFIG_USERCONFIG",
            "NPM_CONFIG_CACHE",
            "GIT_CONFIG_GLOBAL",
            "GIT_CONFIG_NOSYSTEM",
            "GIT_TERMINAL_PROMPT",
            "GCM_INTERACTIVE",
            "GNUPGHOME");

    private final Duration timeout;

    public McpSubprocessHarness(Duration timeout) {
        if (Objects.requireNonNull(timeout, "timeout").isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    public Execution<Void> run(Invocation invocation) throws Exception {
        return run(invocation, (workspace, result) -> null);
    }

    /**
     * Runs one child in an owner-only workspace. The environment is scrubbed before launch, output
     * is redacted before it is returned, and all observed descendants are terminated before the
     * workspace is deleted. The reader runs after result capture but before workspace cleanup.
     */
    public <T> Execution<T> run(Invocation invocation, WorkspaceReader<T> reader) throws Exception {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(reader, "reader");

        Path workspace = createRestrictedWorkspace();
        Process process = null;
        Thread standardInputWriter = null;
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
            long deadline = System.nanoTime() + timeout.toNanos();
            AtomicReference<IOException> standardInputFailure = new AtomicReference<>();
            Process startedProcess = process;
            byte[] standardInput = invocation.standardInput();
            standardInputWriter = Thread.ofVirtual()
                    .name("mcp-subprocess-stdin-" + process.pid())
                    .start(() -> writeStandardInput(startedProcess, standardInput, standardInputFailure));

            Settlement settlement = awaitSettlement(process, observedDescendants, deadline);
            terminationCompleted = true;
            awaitStandardInput(standardInputWriter, standardInputFailure, settlement.timedOut());
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
                if (standardInputWriter != null && standardInputWriter.isAlive()) {
                    awaitStandardInputTermination(standardInputWriter);
                }
            } finally {
                deleteRecursively(workspace);
            }
        }
    }

    private static void writeStandardInput(Process process, byte[] input, AtomicReference<IOException> failure) {
        try (var standardInput = process.getOutputStream()) {
            standardInput.write(input);
        } catch (IOException exception) {
            failure.set(exception);
        }
    }

    private void awaitStandardInput(Thread writer, AtomicReference<IOException> failure, boolean processTimedOut)
            throws InterruptedException, IOException {
        awaitStandardInputTermination(writer);
        if (!processTimedOut && failure.get() != null) {
            throw new IOException("could not deliver subprocess standard input", failure.get());
        }
        // Forced termination closes the pipe while a blocked writer is being released. That
        // expected close is represented by timedOut rather than reported as a second failure.
    }

    private void awaitStandardInputTermination(Thread writer) throws InterruptedException, IOException {
        if (!writer.join(timeout)) {
            writer.interrupt();
            throw new IOException("standard input writer survived subprocess termination");
        }
    }

    private Settlement awaitSettlement(Process process, Set<ProcessHandle> observedDescendants, long deadline)
            throws InterruptedException, IOException {
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

    private static Environment scrubbedEnvironment(Invocation invocation, Path workspace) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> sensitiveValues = new HashSet<>(invocation.sensitiveValues());
        copyAllowedAmbientEnvironment(values);
        rejectHarnessOwnedOverrides(invocation.environment());
        copyNonSensitiveEnvironment(invocation.environment(), values, sensitiveValues);

        String workspaceValue = workspace.toString();
        Path home = Files.createDirectories(workspace.resolve("home"));
        Path config = Files.createDirectories(workspace.resolve("config"));
        Path cache = Files.createDirectories(workspace.resolve("cache"));
        Path data = Files.createDirectories(workspace.resolve("data"));
        Path state = Files.createDirectories(workspace.resolve("state"));
        Path npmCache = Files.createDirectories(cache.resolve("npm"));
        Path gnupgHome = Files.createDirectories(config.resolve("gnupg"));
        Path npmConfig = Files.createFile(config.resolve("npmrc"));
        Path gitConfig = Files.createFile(config.resolve("gitconfig"));

        values.put("HOME", home.toString());
        values.put("USERPROFILE", home.toString());
        values.put("XDG_CONFIG_HOME", config.toString());
        values.put("XDG_CACHE_HOME", cache.toString());
        values.put("XDG_DATA_HOME", data.toString());
        values.put("XDG_STATE_HOME", state.toString());
        values.put("TMPDIR", workspaceValue);
        values.put("TMP", workspaceValue);
        values.put("TEMP", workspaceValue);
        values.put("NPM_CONFIG_USERCONFIG", npmConfig.toString());
        values.put("NPM_CONFIG_CACHE", npmCache.toString());
        values.put("GIT_CONFIG_GLOBAL", gitConfig.toString());
        values.put("GIT_CONFIG_NOSYSTEM", "1");
        values.put("GIT_TERMINAL_PROMPT", "0");
        values.put("GCM_INTERACTIVE", "Never");
        values.put("GNUPGHOME", gnupgHome.toString());
        List<String> redactions = sensitiveValues.stream()
                .filter(value -> !value.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        return new Environment(Map.copyOf(values), redactions);
    }

    private static void copyAllowedAmbientEnvironment(Map<String, String> target) {
        System.getenv().forEach((key, value) -> {
            if (AMBIENT_ENVIRONMENT_ALLOWLIST.contains(key.toUpperCase(Locale.ROOT))) {
                target.put(key, value);
            }
        });
    }

    private static void rejectHarnessOwnedOverrides(Map<String, String> environment) {
        environment.keySet().forEach(key -> {
            if (HARNESS_OWNED_ENVIRONMENT.contains(key.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("environment variable is owned by the harness: " + key);
            }
        });
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

    public record Invocation(
            List<String> command, Map<String, String> environment, byte[] standardInput, Set<String> sensitiveValues) {

        public Invocation {
            command = List.copyOf(command);
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
            environment = Map.copyOf(environment);
            standardInput = standardInput.clone();
            sensitiveValues = Set.copyOf(sensitiveValues);
        }

        public static Invocation of(List<String> command) {
            return new Invocation(command, Map.of(), new byte[0], Set.of());
        }

        public Invocation withEnvironment(Map<String, String> environment) {
            return new Invocation(command, environment, standardInput, sensitiveValues);
        }

        public Invocation withStandardInput(String standardInput) {
            return new Invocation(
                    command, environment, standardInput.getBytes(StandardCharsets.UTF_8), sensitiveValues);
        }

        public Invocation withSensitiveValues(Set<String> sensitiveValues) {
            return new Invocation(command, environment, standardInput, sensitiveValues);
        }

        @Override
        public byte[] standardInput() {
            return standardInput.clone();
        }
    }

    public record Result(
            Path workspace,
            long rootPid,
            int exitCode,
            boolean timedOut,
            int forcedTerminationCount,
            int normalExitCount,
            String stdout,
            String stderr,
            Set<Long> observedDescendantPids) {}

    public record Execution<T>(Result result, T workspaceValue) {}

    @FunctionalInterface
    public interface WorkspaceReader<T> {
        T read(Path workspace, Result result) throws Exception;
    }

    private record Environment(Map<String, String> values, List<String> sensitiveValues) {}

    private record Settlement(boolean timedOut, int forcedTerminationCount) {}
}
