// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

/** Top-level contract fixture whose generated proxy companion exists on the test classpath. */
@DelayedJobContract(name = "selection-test")
interface SelectionTestJob extends DelayedJobClient<String> {}
