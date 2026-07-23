// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory implementation of {@link JobLogger}.
 *
 * <p>Entries are appended to a {@link CopyOnWriteArrayList} which provides safe concurrent
 * writes without explicit synchronisation and stable snapshot reads.
 *
 * <p>Instances are created per execution by {@link DefaultJobContext}.
 */
public class DefaultJobLogger implements JobLogger {

    private final CopyOnWriteArrayList<LogEntry> buffer = new CopyOnWriteArrayList<>();

    @Override
    public void info(String message) {
        buffer.add(new LogEntry("INFO", message, Instant.now()));
    }

    @Override
    public void warn(String message) {
        buffer.add(new LogEntry("WARN", message, Instant.now()));
    }

    @Override
    public void error(String message) {
        buffer.add(new LogEntry("ERROR", message, Instant.now()));
    }

    @Override
    public List<LogEntry> entries() {
        return Collections.unmodifiableList(buffer);
    }
}
