// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.metrics;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The difference between the database's clock and this host's clock.
 *
 * <p>The database lives in a VM and stamps rows with its own clock, while the driver, the collector and the host
 * samplers stamp theirs with the host's. To read both on one axis, a run measures the offset at its start and end
 * and moves database times onto the host's clock. The estimate takes the reading with the shortest round trip, whose
 * error is at most half of it.
 *
 * @param offsetMs how far the database clock is ahead of the host clock, in milliseconds
 * @param roundTripMs the round trip of the reading the estimate comes from, in milliseconds
 */
public record ClockOffset(double offsetMs, double roundTripMs) {

    /**
     * One reading.
     *
     * @param beforeMs the host time before the query, in epoch milliseconds
     * @param databaseMs the database time, in epoch milliseconds
     * @param afterMs the host time after the answer, in epoch milliseconds
     */
    public record Reading(double beforeMs, double databaseMs, double afterMs) {}

    /**
     * Estimates the offset from readings.
     *
     * @param readings the readings; at least one
     * @return the estimate from the reading with the shortest round trip
     */
    public static ClockOffset estimate(List<Reading> readings) {
        Reading best = null;
        for (Reading reading : readings) {
            if (best == null || reading.afterMs() - reading.beforeMs() < best.afterMs() - best.beforeMs()) {
                best = reading;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("No readings");
        }
        double middle = (best.beforeMs() + best.afterMs()) / 2;
        return new ClockOffset(best.databaseMs() - middle, best.afterMs() - best.beforeMs());
    }

    /**
     * Measures the offset.
     *
     * @param connection a connection to the database
     * @param samples the number of readings
     * @return the estimate
     * @throws SQLException if a query fails
     */
    public static ClockOffset measure(Connection connection, int samples) throws SQLException {
        List<Reading> readings = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            for (int i = 0; i < samples; i++) {
                double before = System.currentTimeMillis();
                double database;
                try (ResultSet rows = statement.executeQuery("SELECT extract(epoch from clock_timestamp()) * 1000")) {
                    rows.next();
                    database = rows.getDouble(1);
                }
                double after = System.currentTimeMillis();
                readings.add(new Reading(before, database, after));
            }
        }
        return estimate(readings);
    }

    /**
     * Moves a host time onto the database's clock.
     *
     * @param hostMs a host time in epoch milliseconds
     * @return the same instant on the database clock
     */
    public double toDatabase(double hostMs) {
        return hostMs + offsetMs;
    }

    /**
     * Moves a database time onto the host's clock.
     *
     * @param databaseMs a database time in epoch milliseconds
     * @return the same instant on the host clock
     */
    public double toHost(double databaseMs) {
        return databaseMs - offsetMs;
    }
}
