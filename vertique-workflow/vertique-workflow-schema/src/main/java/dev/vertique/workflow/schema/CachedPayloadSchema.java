// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.json.schema.validation.PatternInputGuard;
import io.vertx.core.json.JsonObject;

/**
 * One cached payload schema: the canonical document, the compiled validator for it and the guard that bounds the
 * input of its pattern checks.
 *
 * @param document the canonical Draft 2020-12 document
 * @param validator the validator compiled from the document through {@code guard}
 * @param guard the guard the validator was compiled with; a validation call opens a window on it
 */
record CachedPayloadSchema(JsonObject document, io.vertx.json.schema.Validator validator, PatternInputGuard guard) {}
