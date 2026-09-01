// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.architecture.fixture;

import io.github.bucket4j.Bucket;

/**
 * Test-only fixture: exactly one public member typed with a Bucket4j type, to prove {@link
 * dev.vertique.ratelimit.architecture.RateLimitCoreArchitectureTest}'s rule actually catches a
 * violation instead of vacuously passing.
 */
public final class Bucket4jExposingFixture {

    public Bucket exposedBucket() {
        return null;
    }
}
