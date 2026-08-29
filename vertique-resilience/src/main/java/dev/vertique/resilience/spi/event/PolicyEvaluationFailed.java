// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.resilience.spi.event;

import dev.vertique.resilience.PolicyCallbackKind;

/** Marks a safe policy callback failure. */
public record PolicyEvaluationFailed(
        String operationKey,
        long executionId,
        ResilienceConcern concern,
        PolicyCallbackKind callbackKind,
        String callbackExceptionClass)
        implements ResilienceEvent {
    public PolicyEvaluationFailed {
        operationKey = EventValidation.key(operationKey);
        executionId = EventValidation.positive(executionId, "executionId");
        concern = EventValidation.required(concern, "concern");
        callbackKind = EventValidation.required(callbackKind, "callbackKind");
        callbackExceptionClass = safeClass(callbackExceptionClass);
    }

    private static String safeClass(String value) {
        if (value == null || value.isBlank() || value.length() > 256)
            throw new IllegalArgumentException("callbackExceptionClass must be a safe class name");
        return value;
    }
}
