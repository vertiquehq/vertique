// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/** INTERNAL: names reserved for service reply waits. */
public final class ReplySignals {
    private static final String PREFIX = "vertique.reply.";
    private static final String OK = PREFIX + "ok.";
    private static final String FAILED = PREFIX + "failed.";

    private ReplySignals() {}

    public static String replyName(String id) {
        return OK + id;
    }

    public static String failureName(String id) {
        return FAILED + id;
    }

    public static String waitStepId(String id) {
        return id + ".reply";
    }

    public static boolean isReserved(String name) {
        return name.startsWith(PREFIX);
    }

    public static boolean isFailure(String name) {
        return name.startsWith(FAILED);
    }

    public static String failureNameFor(String name) {
        return FAILED + name.substring(OK.length());
    }

    public static String replyNameFor(String name) {
        return OK + name.substring(FAILED.length());
    }
}
