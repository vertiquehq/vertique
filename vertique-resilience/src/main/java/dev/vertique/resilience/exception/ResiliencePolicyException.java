// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.exception;

import dev.vertique.resilience.PolicyCallbackKind;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Signals a safe policy construction or synchronous callback failure. */
public final class ResiliencePolicyException extends ResilienceException {

    private static final String CALLBACK_MESSAGE = "Resilience policy callback failed";
    private static final String INVALID_CONFIGURATION_MESSAGE = "Invalid resilience policy configuration";
    private static final String INCOMPLETE_CONFIGURATION_MESSAGE = "Incomplete resilience policy configuration";
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9:-]{1,32}:[0-9a-f]{64}");

    private final Optional<String> operationKey;
    private final Optional<PolicyCallbackKind> callbackKind;
    private final Optional<String> callbackExceptionClass;
    private final Optional<String> attemptExceptionClass;

    /**
     * Creates a policy construction failure with a fixed message.
     *
     * @param reason fixed failure reason
     */
    public ResiliencePolicyException(ResiliencePolicyFailureReason reason) {
        super(messageFor(reason));
        this.operationKey = Optional.empty();
        this.callbackKind = Optional.empty();
        this.callbackExceptionClass = Optional.empty();
        this.attemptExceptionClass = Optional.empty();
    }

    /**
     * Creates a cause-free policy callback failure retaining only safe metadata.
     *
     * @param operationKey validated opaque operation key
     * @param callbackKind callback category
     * @param callbackExceptionClass callback throwable class
     * @param attemptExceptionClass optional preceding attempt throwable class
     */
    public ResiliencePolicyException(
            String operationKey,
            PolicyCallbackKind callbackKind,
            Class<? extends Throwable> callbackExceptionClass,
            Optional<Class<? extends Throwable>> attemptExceptionClass) {
        super(CALLBACK_MESSAGE);
        this.operationKey = Optional.of(requireKey(operationKey));
        this.callbackKind = Optional.of(Objects.requireNonNull(callbackKind, "callbackKind"));
        this.callbackExceptionClass =
                Optional.of(Objects.requireNonNull(callbackExceptionClass, "callbackExceptionClass")
                        .getName());
        this.attemptExceptionClass = className(attemptExceptionClass);
    }

    /** @return the optional validated opaque operation key */
    public Optional<String> operationKey() {
        return operationKey;
    }

    /** @return the optional callback category */
    public Optional<PolicyCallbackKind> callbackKind() {
        return callbackKind;
    }

    /** @return the optional callback throwable class name */
    public Optional<String> callbackExceptionClass() {
        return callbackExceptionClass;
    }

    /** @return the optional preceding attempt throwable class name */
    public Optional<String> attemptExceptionClass() {
        return attemptExceptionClass;
    }

    private static String messageFor(ResiliencePolicyFailureReason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            case INVALID_CONFIGURATION -> INVALID_CONFIGURATION_MESSAGE;
            case INCOMPLETE_CONFIGURATION -> INCOMPLETE_CONFIGURATION_MESSAGE;
        };
    }

    private static Optional<String> className(Optional<Class<? extends Throwable>> type) {
        if (type == null) {
            throw new NullPointerException("attemptExceptionClass");
        }
        return type.map(
                value -> Objects.requireNonNull(value, "attemptExceptionClass").getName());
    }

    private static String requireKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("operationKey must be a derived resilience key");
        }
        return key;
    }
}
