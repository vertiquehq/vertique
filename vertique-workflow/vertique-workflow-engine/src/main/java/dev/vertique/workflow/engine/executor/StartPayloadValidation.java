// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.engine.RecordTimePayloadValidator;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.registry.store.DefinitionKey;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Checks the payload of a start against the JSON Schema stored with the definition version the start selected.
 *
 * <p>The check loads no payload class and no plan: it reads only the stored schema, through the bounded
 * {@link RecordTimePayloadValidator}. A payload larger than {@code workflow.recording.maxPayloadBytes} or nested
 * deeper than 64 levels is refused before the schema is consulted, a schema with a non-local {@code $ref} is refused
 * before it is compiled, and compiled schemas are shared through the validator's bounded cache. A version stored
 * without a schema (written by an earlier release) leaves validation to the executor, which deserializes the payload
 * into the plan's start type and applies its constraints; its size and depth are still bounded here.
 *
 * <p>Every refusal is a {@link WorkflowInvalidPayloadException} that names the definition version and, for a schema
 * violation, the path of the first violation; none carries the submitted payload.
 *
 * <p>INTERNAL: framework wiring between the start recording path and the executor package. It is not application
 * API.
 */
public final class StartPayloadValidation {

    private final RecordTimePayloadValidator validator;

    /**
     * Creates the check over a record-time validator.
     *
     * @param validator the bounded validator whose size limit and schema cache apply
     */
    public StartPayloadValidation(RecordTimePayloadValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Validates a start payload without I/O, locks or transactions.
     *
     * @param payload     the payload of the start, including {@code null}
     * @param startSchema the stored start schema of the selected version, or {@code null} when the row has none
     * @param key         the selected definition version, named in a refusal
     * @throws WorkflowInvalidPayloadException when the payload exceeds the size or depth bound, the schema holds a
     *     non-local {@code $ref}, or the payload violates the schema
     */
    public void validate(@Nullable Object payload, @Nullable JsonObject startSchema, DefinitionKey key) {
        Objects.requireNonNull(key, "key");
        String subject = "Start of definition '" + key.definitionId() + "' version " + key.definitionVersion();
        try {
            if (startSchema == null) {
                validator.validate(payload);
            } else {
                validator.validate(payload, startSchema, subject);
            }
        } catch (WorkflowInvalidPayloadException refused) {
            throw named(refused, subject);
        }
    }

    /** Names the definition version in every refusal and the first violation path in a schema refusal. */
    private static WorkflowInvalidPayloadException named(WorkflowInvalidPayloadException refused, String subject) {
        List<ViolationDetail> violations = refused.violations();
        String message = refused.getMessage();
        if (!message.startsWith(subject)) {
            message = subject + " is refused: " + message;
        }
        if (!violations.isEmpty()) {
            String path = violations.get(0).path();
            if (path != null && !path.isEmpty() && !"payload".equals(path)) {
                message = message + " (first violation at " + path + ")";
            }
        }
        return new WorkflowInvalidPayloadException(message, violations);
    }
}
