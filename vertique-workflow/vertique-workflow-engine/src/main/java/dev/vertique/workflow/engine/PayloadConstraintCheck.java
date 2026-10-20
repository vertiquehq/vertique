// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.core.validation.JakartaPathPointers;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in constraint enforcement for coerced decision and start payloads.
 *
 * <p>When a {@link BeanValidator} is bound, {@link #check} validates the coerced payload and fails
 * with {@link WorkflowInvalidPayloadException} when it has violations. Each detail keeps the
 * message, type and arguments the validator produced; only the path is rewritten, from the Jakarta
 * property-path string to an RFC 6901 pointer over the wire names of the process mapper. When no
 * validator is bound the check does nothing and the engine behaves as before.
 *
 * <p>At most 100 violations are returned, in the order the validator produced them. When more were produced, one
 * final detail with an empty path, the message {@code further violations omitted} and the type {@code truncated}
 * follows, so the exception carries at most 101 details. Pointers are converted only for the retained violations,
 * which bounds the conversion work. The transport must still cap the request body before the engine is called.
 *
 * <p>The check never logs a payload or a violation. It states once, at construction, whether
 * enforcement is enabled.
 */
@Singleton
final class PayloadConstraintCheck {

    private static final Logger log = LoggerFactory.getLogger(PayloadConstraintCheck.class);

    /** The largest number of violations returned before the truncation detail. */
    private static final int MAX_VIOLATIONS = 100;

    private static final String TRUNCATED_MESSAGE = "further violations omitted";
    private static final String TRUNCATED_TYPE = "truncated";

    private final BeanValidator validator;

    /**
     * Creates the check and logs once whether enforcement is enabled.
     *
     * @param validator the bound bean validator, or empty when the application binds none
     */
    @Inject
    PayloadConstraintCheck(Optional<BeanValidator> validator) {
        this.validator = validator.orElse(null);
        log.info("workflow payload constraint enforcement: {}", this.validator == null ? "disabled" : "enabled");
    }

    /**
     * Validates a coerced payload.
     *
     * @param coerced the payload after coercion to its declared type; {@code null} is not checked
     * @param message the rejection message of the exception thrown on violations
     * @throws WorkflowInvalidPayloadException when the validator reports violations
     */
    void check(Object coerced, String message) {
        if (validator == null || coerced == null) {
            return;
        }
        List<ViolationDetail> violations = validator.check(coerced);
        if (violations.isEmpty()) {
            return;
        }
        Class<?> rootType = coerced.getClass();
        int retained = Math.min(violations.size(), MAX_VIOLATIONS);
        List<ViolationDetail> converted = new ArrayList<>(retained + 1);
        for (ViolationDetail detail : violations.subList(0, retained)) {
            converted.add(ViolationDetail.atPointer(
                    JakartaPathPointers.toPointer(detail.path(), rootType, VertiqueJson.mapper()),
                    detail.message(),
                    detail.type(),
                    detail.args()));
        }
        if (violations.size() > MAX_VIOLATIONS) {
            converted.add(ViolationDetail.unlocated(TRUNCATED_MESSAGE, TRUNCATED_TYPE));
        }
        throw new WorkflowInvalidPayloadException(message, converted);
    }
}
