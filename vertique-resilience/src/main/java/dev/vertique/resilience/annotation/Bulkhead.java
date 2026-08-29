// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Configures reject or bounded-queue admission for the annotated type or method. */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Bulkhead {

    /** @return the maximum number of active logical executions */
    int maxConcurrentCalls();

    /** @return the admission mode */
    Mode mode() default Mode.REJECT;

    /** @return the maximum number of waiting executions in queue mode, or zero in reject mode */
    int maxQueueSize() default 0;

    /** @return the maximum queue wait in milliseconds, or zero in reject mode */
    long queueTimeoutMs() default 0;

    /** Bulkhead admission mode. */
    enum Mode {
        REJECT,
        QUEUE
    }
}
