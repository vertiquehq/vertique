// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.services.reply;

import java.util.UUID;

/** Opaque address of a reply wait. INTERNAL.
 * @param instanceId workflow instance id
 * @param waitId wait entry id
 */
public record ReplyHandle(UUID instanceId, UUID waitId) {
    /** Validates both identifiers. */
    public ReplyHandle {
        java.util.Objects.requireNonNull(instanceId, "instanceId");
        java.util.Objects.requireNonNull(waitId, "waitId");
    }

    /** Parses a handle without exposing invalid input.
     * @param text encoded handle
     * @return parsed handle
     * @throws IllegalArgumentException if the handle is invalid
     */
    public static ReplyHandle parse(String text) {
        try {
            String[] parts = text.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new ReplyHandle(UUID.fromString(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid reply handle");
        }
    }

    @Override
    public String toString() {
        return instanceId + ":" + waitId;
    }
}
