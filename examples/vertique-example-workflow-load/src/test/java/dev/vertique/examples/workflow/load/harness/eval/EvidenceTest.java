// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a stall keeps the aligned evidence around it, over its whole waiting interval, and that a coincident
 * condition is never reported as its cause.
 */
class EvidenceTest {

    private static final long FROM = 1_000_000_000_000L;
    private static final long TO = FROM + 4_000;
    private final Thresholds limits = Thresholds.defaults();

    private static CsvTable empty(String header) {
        return CsvTable.of(header, List.of());
    }

    private static Evidence.Sources sources(
            CsvTable driver,
            CsvTable ticks,
            CsvTable heartbeat,
            CsvTable host,
            CsvTable vm,
            Map<String, List<JvmLog.Pause>> pauses) {
        return new Evidence.Sources(
                driver,
                FROM - 20_000,
                ticks,
                heartbeat,
                host,
                vm,
                empty("t_ms,pid,role,rss_kb,cpu_s"),
                empty("t_ms,pid,application,state,query"),
                pauses,
                List.of());
    }

    private static final String DRIVER = "sec,scheduled,started,accepted,refused,failed,max_late_ms,in_flight";
    private static final String TICKS =
            "t_ms,late_ms,db_ok,error,db_clock_ms,rtt_ms,due,outbox_pending,outbox_processing,"
                    + "notification_queue_usage,xact_commit,xact_rollback,deadlocks,blks_read,blks_hit,tup_inserted,tup_updated,"
                    + "temp_files,backends,active,idle_in_transaction,lock_waiting,connections_by_application";
    private static final String BEATS =
            "t_ms,late_ms,worker_state,worker_busy_ms,since_last_success_ms,consecutive_failures,rows_written,recorder_errors";
    private static final String HOST =
            "t_ms,load1,cpu_user,cpu_sys,cpu_idle,mem_used_mb,mem_wired_mb,mem_compressor_mb,"
                    + "mem_unused_mb,swapins,swapouts,swap_used_mb,swap_total_mb,mem_free_pct,pressure_level,system_cpu_pct,"
                    + "vm_process_cpu_pct,harness_cpu_pct,independent_cpu_pct,unattributed_cpu_pct,rows_seen,indep_pid,"
                    + "indep_command,indep_user,indep_cpu_pct";
    private static final String VM =
            "t_ms,interval_ms,vm_cpu_busy_pct,vm_mem_total_mb,vm_mem_available_mb,vm_swap_used_mb,"
                    + "container_cpu_pct_of_one_core,container_mem_mb";

    private static String tick(long t, boolean ok, String error, int lock) {
        return t + ",5," + ok + "," + error + "," + t + ",1,10,0,0,0,100,0,0,0,0,0,0,0,30,5,0," + lock + ",x=1";
    }

    @Test
    @DisplayName("a stall with nothing around it has no coincident condition, and its cause is still unresolved")
    void nothingCoincides() {
        long driverSec = (FROM - (FROM - 20_000)) / 1000;
        CsvTable driver = CsvTable.of(DRIVER, List.of(driverSec + ",100,100,100,0,0,5,3"));
        CsvTable ticks = CsvTable.of(TICKS, List.of(tick(FROM + 500, true, "", 0), tick(FROM + 1500, true, "", 0)));
        CsvTable host = CsvTable.of(
                HOST, List.of((FROM + 500) + ",2,10,5,80,1,1,1,1000,0,5,100,1000,50,1,20,0,50,0,10,40,,,,"));

        JsonObject e = Evidence.collect(
                "dip", FROM, TO, FROM, sources(driver, ticks, empty(BEATS), host, empty(VM), Map.of()), limits);

        assertTrue(e.getBoolean("noCoincidentCondition"));
        assertTrue(e.getJsonArray("coincident").isEmpty());
        assertEquals("unresolved", e.getString("cause"));
        assertEquals(1, e.getJsonArray("driver").size());
        assertEquals(2, e.getJsonArray("collector").size());
        assertEquals(1, e.getJsonArray("host").size());
        assertTrue(e.getString("note").contains("not shown to be the cause"));
    }

    @Test
    @DisplayName("coincident conditions are listed from every source and the cause stays unresolved")
    void coincident() {
        CsvTable driver = CsvTable.of(DRIVER, List.of("20,100,100,100,0,0,400,3"));
        CsvTable ticks = CsvTable.of(
                TICKS, List.of(tick(FROM + 500, false, "PSQLException", 0), tick(FROM + 1500, true, "", 3)));
        CsvTable beats = CsvTable.of(BEATS, List.of((FROM + 500) + ",900,querying,5000,6000,4,10,0"));
        CsvTable host = CsvTable.of(
                HOST,
                List.of(
                        (FROM + 500) + ",20,10,5,2,1,1,1,1000,0,5,100,1000,50,2,150,0,50,400,250,40,90001,mvn,mika,400",
                        (FROM + 1500)
                                + ",20,10,5,2,1,1,1,1000,0,9,100,1000,50,2,150,0,50,400,250,40,90001,mvn,mika,400"));
        CsvTable vm = CsvTable.of(
                VM,
                List.of((FROM + 500) + ",1000,99,8000,5000,0,100,200", (FROM + 1500) + ",1000,99,8000,5000,0,100,200"));
        JvmLog.Pause pause = new JvmLog.Pause(FROM + 2_000, 2_500, "safepoint", "G1CollectForAllocation");

        JsonObject e = Evidence.collect(
                "dip",
                FROM,
                TO,
                FROM,
                sources(driver, ticks, beats, host, vm, Map.of("node-3", List.of(pause))),
                limits);

        List<String> flags =
                e.getJsonArray("coincident").stream().map(Object::toString).toList();
        assertFalse(e.getBoolean("noCoincidentCondition"));
        assertEquals("unresolved", e.getString("cause"), "an indicator is not an explanation");
        for (String expected : List.of(
                "generator-late",
                "database-unanswered",
                "database-samples-unavailable",
                "collector-heartbeat-late",
                "sessions-waiting-on-locks",
                "host-cpu-saturated",
                "host-load-above-14",
                "host-memory-pressure",
                "host-swapping",
                "independent-process:mvn[90001]",
                "system-cpu-high",
                "unattributed-cpu-high",
                "vm-cpu-saturated",
                "jvm-pause:node-3")) {
            assertTrue(flags.contains(expected), expected + " missing from " + flags);
        }
        assertEquals(1, e.getJsonObject("jvmPauses").getJsonArray("node-3").size());
        assertEquals(1, e.getJsonArray("heartbeat").size());
    }

    @Test
    @DisplayName("an excursion is sliced over its whole waiting interval: evidence from before the waits ended is kept")
    void waitingIntervalIsCovered() {
        // The slow waits ended in the stretch [FROM, TO); the commands that waited had been recorded 30 s earlier.
        long origin = FROM - 30_000;
        CsvTable host = CsvTable.of(
                HOST,
                List.of(
                        (origin + 1000) + ",2,10,5,80,1,1,1,1000,0,5,100,1000,50,1,20,0,50,0,10,40,,,,",
                        (FROM + 500) + ",2,10,5,80,1,1,1,1000,0,5,100,1000,50,1,20,0,50,0,10,40,,,,"));
        JvmLog.Pause earlier = new JvmLog.Pause(origin + 5_000, 400, "gc", "Pause Full");

        JsonObject e = Evidence.collect(
                "excursion",
                FROM,
                TO,
                origin,
                sources(empty(DRIVER), empty(TICKS), empty(BEATS), host, empty(VM), Map.of("node-1", List.of(earlier))),
                limits);

        assertEquals(origin, e.getLong("originFromMs"));
        assertEquals(2, e.getJsonArray("host").size(), "the host rows around the origin are in the slice");
        assertTrue(
                e.getJsonArray("coincident").contains("jvm-pause:node-1"),
                e.getJsonArray("coincident").encode());
        assertEquals(origin, e.getJsonArray("waitingIntervalMs").getLong(0));
        assertEquals(TO, e.getJsonArray("waitingIntervalMs").getLong(1));
    }

    @Test
    @DisplayName("a short JVM pause does not coincide with a stall")
    void shortPauseIgnored() {
        JvmLog.Pause pause = new JvmLog.Pause(FROM + 2_000, 30, "safepoint", "x");

        JsonObject e = Evidence.collect(
                "excursion",
                FROM,
                TO,
                FROM,
                sources(
                        empty(DRIVER),
                        empty(TICKS),
                        empty(BEATS),
                        empty(HOST),
                        empty(VM),
                        Map.of("driver", List.of(pause))),
                limits);

        assertTrue(e.getBoolean("noCoincidentCondition"));
        assertEquals(1, e.getJsonObject("jvmPauses").getJsonArray("driver").size(), "kept as evidence anyway");
    }
}
