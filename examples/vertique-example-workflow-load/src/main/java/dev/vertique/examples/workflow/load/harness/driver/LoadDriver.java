// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.driver;

import io.vertx.core.json.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The load driver: a separate process that offers new starts to node ingresses on a fixed schedule.
 *
 * <p>The schedule is open-loop. Arrival {@code k} is due at a time that depends on the profile only; when the
 * previous arrivals are still waiting for their answers the driver starts the next one anyway, on its own virtual
 * thread, so the system under measurement cannot slow the offered load down. Arrival {@code k} goes to ingress
 * {@code k mod n}. An arrival that is due while the driver is late is started at once and in order, and its lateness
 * is recorded.
 *
 * <p>After the schedule and a recovery period in which nothing is offered, the keys the intake limit refused are
 * replayed once, in a phase labelled {@code replay} that lies outside the measurement window. Every planned arrival
 * has a row in {@code arrivals.csv}, whatever happened to it.
 *
 * <p>Files written to the output directory: {@code arrivals.csv}, {@code seconds.csv} and {@code summary.json}.
 * The exit code is 0 when the driver completed its run and wrote its files, 1 otherwise; whether the figures are
 * good enough to use is decided from the summary.
 */
public final class LoadDriver {

    private final DriverSpec spec;
    private final AtomicInteger handedOver = new AtomicInteger();

    private LoadDriver(DriverSpec spec) {
        this.spec = spec;
    }

    /**
     * Runs a driver from the command line.
     *
     * @param args {@code --spec <file>}
     */
    public static void main(String[] args) {
        int code;
        try {
            if (args.length != 2 || !args[0].equals("--spec")) {
                System.err.println("usage: --spec <driver-spec.json>");
                System.exit(2);
                return;
            }
            DriverSpec spec = DriverSpec.read(new JsonObject(Files.readString(Path.of(args[1]))));
            new LoadDriver(spec).run();
            code = 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace(System.err);
            code = 1;
        } catch (Exception e) {
            e.printStackTrace(System.err);
            code = 1;
        }
        System.exit(code);
    }

    /**
     * Returns the index of the ingress an arrival goes to: the arrival index modulo the ingress count.
     *
     * @param arrival the arrival
     * @param ingresses the number of ingresses
     * @return the position in the ingress list
     */
    public static int ingressFor(int arrival, int ingresses) {
        return arrival % ingresses;
    }

    /**
     * Runs the schedule, the recovery and the replay, and writes the files.
     *
     * @return the summary that was written
     * @throws IOException if the files cannot be written
     * @throws InterruptedException if the run is interrupted
     */
    JsonObject run() throws IOException, InterruptedException {
        Files.createDirectories(spec.outDir());
        long epochNow = System.currentTimeMillis();
        long nanoNow = System.nanoTime();
        long t0 = nanoNow + (spec.startAtEpochMs() - epochNow) * 1_000_000L;
        List<IngressClient> clients = new ArrayList<>();
        for (DriverSpec.Ingress ingress : spec.ingresses()) {
            clients.add(new IngressClient(ingress.host(), ingress.port(), spec.requestTimeoutMs()));
        }
        Thread sampler = Thread.ofPlatform().name("fd-sampler").daemon().start(this::sampleDescriptors);
        ArrivalLedger main = new ArrivalLedger(spec.profile(), "main", 0);
        long profileEnd = t0 + Math.round(spec.profile().endSec() * 1e9);
        long recoveryEnd = profileEnd + spec.recoverySec() * 1_000_000_000L;
        ArrivalLedger replay;
        List<Integer> refused;
        List<Integer> notReplayed;
        long replayStart;
        long finalAt;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            offer(main, t0, clients, executor, null);
            sleepUntil(recoveryEnd);
            // A refusal can still be on its way when the recovery ends. The refused keys are collected only when every
            // request of the main phase has been answered, or has been waited for as long as the grace allows.
            long mainDeadline = System.nanoTime() + spec.graceSec() * 1_000_000_000L;
            while (handedOver.get() > 0 && System.nanoTime() < mainDeadline) {
                Thread.sleep(25);
            }
            refused = main.refused();
            replayStart = Math.max(System.nanoTime(), recoveryEnd);
            replay = replayLedger(refused, replayStart - t0);
            if (replay.planned() > 0) {
                for (int i = 0; i < refused.size(); i++) {
                    replay.origin(i, refused.get(i));
                }
                offer(replay, replayStart, clients, executor, refused);
            }
            long lastSchedule = Math.max(
                    recoveryEnd, replayStart + Math.round(replay.profile().endSec() * 1e9));
            finalAt = lastSchedule + spec.graceSec() * 1_000_000_000L;
            // Wait for every arrival handed to a virtual thread, also those whose thread has not yet begun to run.
            while (handedOver.get() > 0 && System.nanoTime() < finalAt) {
                Thread.sleep(25);
            }
            // A refusal that was answered after the grace for the main phase is in no replay. Its key is reported, so
            // that the ledger still closes: refused equals replayed plus not replayed.
            notReplayed = new java.util.ArrayList<>(main.refused());
            notReplayed.removeAll(refused);
            main.closeOutstanding();
            replay.closeOutstanding();
            executor.shutdownNow();
        } finally {
            clients.forEach(IngressClient::close);
        }
        long retries = clients.stream().mapToLong(IngressClient::freshRetries).sum();
        sampler.interrupt();
        return write(main, replay, t0, replayStart - t0, retries, descriptors(), notReplayed);
    }

    private final AtomicLong openPeak = new AtomicLong();

    private void sampleDescriptors() {
        var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        while (!Thread.currentThread().isInterrupted()) {
            if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
                openPeak.accumulateAndGet(unix.getOpenFileDescriptorCount(), Math::max);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private JsonObject descriptors() {
        var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        JsonObject json = new JsonObject().put("openPeak", openPeak.get());
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            json.put("max", unix.getMaxFileDescriptorCount()).put("openAtEnd", unix.getOpenFileDescriptorCount());
        }
        return json;
    }

    private ArrivalLedger replayLedger(List<Integer> refused, long baseNs) {
        if (refused.isEmpty()) {
            return new ArrivalLedger(LoadProfile.of(List.of()), "replay", baseNs);
        }
        double rate = spec.replayRatePerSec();
        LoadProfile profile =
                LoadProfile.of(List.of(new LoadProfile.Segment("replay", refused.size() / rate, rate, 1, false)));
        return new ArrivalLedger(profile, "replay", baseNs);
    }

    private void offer(
            ArrivalLedger ledger,
            long origin,
            List<IngressClient> clients,
            ExecutorService executor,
            List<Integer> keyOf) {
        int total = ledger.planned();
        for (int k = 0; k < total; k++) {
            sleepUntil(origin + ledger.profile().offsetNanos(k));
            int index = k;
            int keyIndex = keyOf == null ? k : keyOf.get(k);
            int slot = ingressFor(k, clients.size());
            int node = spec.ingresses().get(slot).node();
            IngressClient client = clients.get(slot);
            handedOver.incrementAndGet();
            executor.execute(() -> {
                try {
                    send(ledger, index, keyIndex, node, client, origin);
                } finally {
                    handedOver.decrementAndGet();
                }
            });
        }
    }

    private void send(ArrivalLedger ledger, int index, int keyIndex, int node, IngressClient client, long origin) {
        ledger.started(index, node, System.nanoTime() - origin);
        String key = URLEncoder.encode(spec.name() + "-" + keyIndex, StandardCharsets.UTF_8);
        ArrivalLedger.Outcome outcome;
        int status = 0;
        String reason = null;
        try {
            IngressClient.Reply reply =
                    client.post("/start?key=" + key, () -> ledger.wired(index, System.nanoTime() - origin));
            status = reply.status();
            if (status == 200 && reply.body().contains("\"accepted\"")) {
                outcome = ArrivalLedger.Outcome.ACCEPTED;
            } else if (status == 503 && reply.body().contains("\"refused\"")) {
                outcome = ArrivalLedger.Outcome.REFUSED;
            } else {
                outcome = ArrivalLedger.Outcome.FAILED;
                reason = "http-" + status;
            }
        } catch (IOException | RuntimeException e) {
            outcome = ArrivalLedger.Outcome.FAILED;
            reason = classify(e);
        }
        ledger.answered(index, outcome, status, reason, System.nanoTime() - origin);
    }

    /**
     * Names a failure of a request by what went wrong at the socket, so that exhaustion of the driver's own
     * descriptors or ports is told from a node that refused, reset or did not answer.
     *
     * @param e the failure
     * @return the reason
     */
    static String classify(Exception e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("Too many open files") || message.contains("Can't assign requested address")) {
            return "socket-exhaustion";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "timeout";
        }
        if (e instanceof java.net.ConnectException) {
            return "connect-failed";
        }
        if (message.contains("reset") || message.contains("Broken pipe")) {
            return "reset";
        }
        return "io-" + e.getClass().getSimpleName();
    }

    private JsonObject write(
            ArrivalLedger main,
            ArrivalLedger replay,
            long t0,
            long replayBaseNs,
            long freshRetries,
            JsonObject descriptors,
            List<Integer> notReplayed)
            throws IOException {
        Path arrivals = spec.outDir().resolve("arrivals.csv");
        int rows;
        try (BufferedWriter out = Files.newBufferedWriter(arrivals, StandardCharsets.UTF_8)) {
            rows = main.writeRows(out, true) + replay.writeRows(out, false);
        }
        main.writeSeconds(spec.outDir().resolve("seconds.csv"));
        int expected = main.planned() + replay.planned();
        JsonObject summary = new JsonObject()
                .put("name", spec.name())
                .put("startAtEpochMs", spec.startAtEpochMs())
                .put("finishedAtEpochMs", System.currentTimeMillis())
                .put("windowStartSec", spec.profile().windowStartSec())
                .put("windowEndSec", spec.profile().windowEndSec())
                .put("scheduleEndSec", spec.profile().endSec())
                .put("recoverySec", spec.recoverySec())
                .put("replayStartSec", replayBaseNs / 1e9)
                .put("ingresses", spec.ingresses().size())
                .put("distributionRule", "arrival index modulo ingress count")
                .put("main", main.summary(spec.lateArrivalMs()).toJson())
                .put("replay", replay.summary(spec.lateArrivalMs()).toJson())
                .put("requestsSentAgainOnNewConnection", freshRetries)
                .put("fileDescriptors", descriptors)
                .put("refusedNotReplayed", notReplayed.size())
                .put("refusedNotReplayedArrivals", new io.vertx.core.json.JsonArray(new ArrayList<Object>(notReplayed)))
                .put(
                        "socketExhaustion",
                        main.summary(spec.lateArrivalMs()).failureDetails().containsKey("socket-exhaustion")
                                || replay.summary(spec.lateArrivalMs())
                                        .failureDetails()
                                        .containsKey("socket-exhaustion"))
                .put("rowsExpected", expected)
                .put("rowsWritten", rows)
                .put("recorderLoss", rows != expected);
        Files.writeString(spec.outDir().resolve("summary.json"), summary.encodePrettily());
        return summary;
    }

    private static void sleepUntil(long targetNanos) {
        while (true) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            LockSupport.parkNanos(remaining);
        }
    }
}
