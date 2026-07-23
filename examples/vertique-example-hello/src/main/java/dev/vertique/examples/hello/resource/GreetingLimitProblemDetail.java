// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello.resource;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.vertique.rest.core.ProblemDetail;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

/**
 * A typed RFC 9457 Problem Detail for greeting limit violations.
 *
 * <p>Extends {@link ProblemDetail} via {@code @SuperBuilder}, inheriting all standard
 * field setters ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance})
 * and adding a typed {@code limit} field specific to this problem type.
 *
 * <p>Example JSON response:
 * <pre>{@code
 * {
 *   "type": "https://example.com/problems/greeting-limit-exceeded",
 *   "title": "Greeting Limit Exceeded",
 *   "status": 429,
 *   "detail": "Greeting limit of 5 exceeded",
 *   "limit": 5
 * }
 * }</pre>
 */
@Getter
@SuperBuilder(toBuilder = true)
@Accessors(fluent = true)
@EqualsAndHashCode(callSuper = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GreetingLimitProblemDetail extends ProblemDetail {

    int limit;

    public static GreetingLimitProblemDetail of(int limit) {
        return GreetingLimitProblemDetail.builder()
                .type("https://example.com/problems/greeting-limit-exceeded")
                .title("Greeting Limit Exceeded")
                .status(429)
                .detail("Greeting limit of " + limit + " exceeded")
                .limit(limit)
                .build();
    }
}
