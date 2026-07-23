// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

/**
 * Optional payload-side interface that provides the business key for a {@link WorkflowStart}
 * operation.
 *
 * <p>If the start payload implements this interface, the proxy will call {@link #businessKey()} to
 * populate the {@code StartCommand.businessKey} field. A parameter-level {@link BusinessKey}
 * annotation takes precedence if both are present. The business key is optional and may return
 * null.
 */
public interface BusinessKeyed {

    /**
     * Returns the business key for this payload, or {@code null} if no business key should be set.
     *
     * @return the business key, or null
     */
    String businessKey();
}
