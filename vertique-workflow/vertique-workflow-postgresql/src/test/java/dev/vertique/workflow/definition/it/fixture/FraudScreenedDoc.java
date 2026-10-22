// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

/**
 * Signal payload for the {@code fraud.screened.doc} event in the document-defined fan-out
 * integration test.
 *
 * <p>This record is intentionally distinct from any Slice H fraud signal to avoid coupling between
 * the linear saga IT and the fan-out IT. Only used by
 * {@link dev.vertique.workflow.definition.it.DocumentDefinitionFanOutIT}.
 *
 * @param fraudCleared {@code true} if the fraud screen passed; {@code false} if flagged
 */
public record FraudScreenedDoc(boolean fraudCleared) {}
