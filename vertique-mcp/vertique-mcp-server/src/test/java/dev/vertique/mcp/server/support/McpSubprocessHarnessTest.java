// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Proves T025's bounded process, descendant, workspace, and credential-handling contract. */
class McpSubprocessHarnessTest {

    private static final String FAST_ROW = "shouldReturnTheFastChildOutput";
    private static final String HANG_ROW = "shouldTerminateAHangingChildAtTheBound";
    private static final String DESCENDANT_ROW = "shouldTerminateEveryDescendant";
    private static final String TEMP_ROW = "shouldDeleteTheRestrictedTemporaryDirectory";
    private static final String CREDENTIAL_ROW = "shouldScrubCredentialsFromTheChildEnvironmentAndCapturedOutput";
    private static final String FAST_OUTPUT = "t025-fast-output";
    private static final String SAFE_MARKER = "t025-safe-marker";
    private static final String SECRET = "t025-secret-that-must-not-leak";
    private static final String PIPE_FILLING_INPUT = "x".repeat(8 * 1024 * 1024);

    private final McpSubprocessHarness harness = new McpSubprocessHarness(Duration.ofSeconds(2));

    private static Stream<String> t025ContractRows() {
        return Stream.of(FAST_ROW, HANG_ROW, DESCENDANT_ROW, TEMP_ROW, CREDENTIAL_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t025ContractRows")
    @DisplayName("T025 bounded subprocess contract matrix")
    @Timeout(value = 12, unit = TimeUnit.SECONDS)
    void shouldEnforceT025ContractMatrix(String row) throws Exception {
        switch (row) {
            case FAST_ROW -> assertFastChild();
            case HANG_ROW -> assertHangingChild();
            case DESCENDANT_ROW -> assertDescendants();
            case TEMP_ROW -> assertRestrictedTemporaryDirectory();
            case CREDENTIAL_ROW -> assertCredentialsScrubbed();
            default -> fail("unknown T025 contract row: " + row);
        }
    }

    private void assertFastChild() throws Exception {
        McpSubprocessHarness.Result result = harness.run(fixture("fast")).result();

        assertThat(result.stdout()).contains(FAST_OUTPUT);
        assertThat(result.stderr()).isEmpty();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.normalExitCount()).isOne();
        assertThat(result.forcedTerminationCount()).isZero();
        assertThat(Files.notExists(result.workspace())).isTrue();
    }

    private void assertHangingChild() throws Exception {
        long started = System.nanoTime();
        McpSubprocessHarness.Result result = harness.run(fixture("hang").withStandardInput(PIPE_FILLING_INPUT))
                .result();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(List.of(result.timedOut(), result.forcedTerminationCount(), result.normalExitCount()))
                .containsExactly(true, 1, 0);
        assertThat(elapsed).isBetween(Duration.ofMillis(1_500), Duration.ofSeconds(8));
        assertThat(ProcessHandle.of(result.rootPid())
                        .map(ProcessHandle::isAlive)
                        .orElse(false))
                .isFalse();
        assertThat(Files.notExists(result.workspace())).isTrue();
    }

    private void assertDescendants() throws Exception {
        McpSubprocessHarness.Result result =
                harness.run(fixture("spawn-grandchild")).result();

        assertThat(result.timedOut()).isTrue();
        assertThat(result.observedDescendantPids()).hasSize(1);
        assertThat(result.forcedTerminationCount()).isEqualTo(2);
        assertThat(result.observedDescendantPids()).allSatisfy(pid -> assertThat(
                        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse());
        assertThat(Files.notExists(result.workspace())).isTrue();
    }

    private void assertRestrictedTemporaryDirectory() throws Exception {
        McpSubprocessHarness.Result result = harness.run(fixture("workspace")).result();

        assertThat(result.stdout()).contains("PERMISSIONS=rwx------");
        assertThat(result.stdout()).contains("WORKSPACE=" + result.workspace());
        assertThat(Files.notExists(result.workspace())).isTrue();
    }

    private void assertCredentialsScrubbed() throws Exception {
        McpSubprocessHarness.Invocation invocation = fixture("environment", SECRET)
                .withEnvironment(Map.of(
                        "T025_TEST_TOKEN",
                        SECRET,
                        "T025_SSL_PASSPHRASE",
                        SECRET,
                        "T025_AUTHORIZATION",
                        SECRET,
                        "T025_SAFE_MARKER",
                        SAFE_MARKER))
                .withSensitiveValues(Set.of(SECRET));

        McpSubprocessHarness.Result result = harness.run(invocation).result();

        assertThat(result.stdout()).contains("TOKEN=null");
        assertThat(result.stdout()).contains("PASSPHRASE=null");
        assertThat(result.stdout()).contains("AUTHORIZATION=null");
        assertThat(result.stdout()).contains("SAFE=" + SAFE_MARKER);
        assertThat(result.stdout()).contains("PRINTED=[REDACTED]");
        assertThat(result.stdout()).doesNotContain(SECRET);
        assertThat(result.stderr()).doesNotContain(SECRET);
        assertThat(Files.notExists(result.workspace())).isTrue();
    }

    private static McpSubprocessHarness.Invocation fixture(String mode, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(FixtureMain.class.getName());
        command.add(mode);
        command.addAll(List.of(arguments));
        return McpSubprocessHarness.Invocation.of(command);
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** Subprocess fixture kept in the test class so T025 downloads and starts no external artifact. */
    public static final class FixtureMain {

        private FixtureMain() {}

        public static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "fast" -> System.out.println(FAST_OUTPUT);
                case "hang" -> hang();
                case "spawn-grandchild" -> spawnGrandchild();
                case "workspace" -> printWorkspace();
                case "environment" -> printEnvironment(arguments[1]);
                default -> throw new IllegalArgumentException("unknown fixture mode");
            }
        }

        private static void hang() throws InterruptedException {
            System.out.println("READY");
            while (true) {
                Thread.sleep(1_000);
            }
        }

        private static void spawnGrandchild() throws Exception {
            Process descendant = new ProcessBuilder(
                            javaExecutable().toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            FixtureMain.class.getName(),
                            "hang")
                    .start();
            System.out.println("DESCENDANT=" + descendant.pid());
            hang();
        }

        private static void printWorkspace() throws Exception {
            Path workspace = Path.of(System.getenv("TMPDIR"));
            System.out.println("WORKSPACE=" + workspace);
            System.out.println(
                    "PERMISSIONS=" + PosixFilePermissions.toString(Files.getPosixFilePermissions(workspace)));
        }

        private static void printEnvironment(String secret) {
            System.out.println("TOKEN=" + System.getenv("T025_TEST_TOKEN"));
            System.out.println("PASSPHRASE=" + System.getenv("T025_SSL_PASSPHRASE"));
            System.out.println("AUTHORIZATION=" + System.getenv("T025_AUTHORIZATION"));
            System.out.println("SAFE=" + System.getenv("T025_SAFE_MARKER"));
            System.out.println("PRINTED=" + secret);
        }
    }
}
