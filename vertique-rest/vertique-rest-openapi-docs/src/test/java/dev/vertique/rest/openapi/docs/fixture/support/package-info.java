// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Shared test infrastructure for this module's tests: bounded waits on Vert.x futures, teardown that
 * attempts every step and reports every failure, deployments of a component's HTTP verticle with the
 * port it publishes, and document requests on the loopback interface. Every wait takes an explicit
 * bound from its caller or names the one it applies. Scenario configuration, fixture applications, and
 * per-test constants stay with the tests that use them.
 */
package dev.vertique.rest.openapi.docs.fixture.support;
