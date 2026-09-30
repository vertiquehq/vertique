// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import java.util.Collections;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The comparable facts of one mount's publication: its mount part and one digest per operation.
 * A snapshot holds strings only, so it retains nothing of the publication it was rendered from.
 *
 * @param mountPart the mount-level facts, held as strings
 * @param operationDigests the digest of each operation, keyed by operation id in sorted order
 */
record Snapshot(MountPart mountPart, SortedMap<String, String> operationDigests) {

    /** The snapshot of a publication that carries no facts. */
    static final Snapshot EMPTY = new Snapshot(new MountPart("", "", "", ""), new TreeMap<>());

    /** Stores an unmodifiable copy of {@code operationDigests}. */
    Snapshot {
        operationDigests = Collections.unmodifiableSortedMap(new TreeMap<>(operationDigests));
    }

    /**
     * Finds the first operation, in operation-id order over the ids of both snapshots, that differs
     * between this snapshot and another: present in only one of them, or present in both with
     * different digests.
     *
     * @param other the snapshot to compare with
     * @return the id of the first differing operation, or empty when every operation matches
     */
    Optional<String> firstDifferingOperation(Snapshot other) {
        SortedSet<String> ids = new TreeSet<>(operationDigests.keySet());
        ids.addAll(other.operationDigests.keySet());
        for (String id : ids) {
            String mine = operationDigests.get(id);
            if (mine == null || !mine.equals(other.operationDigests.get(id))) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    /**
     * The mount-level facts of a snapshot.
     *
     * @param mountPath the mount path
     * @param strategyId the strategy id
     * @param applicationName the application name
     * @param declaringType the declaring type name
     */
    record MountPart(String mountPath, String strategyId, String applicationName, String declaringType) {}
}
