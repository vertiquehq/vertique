// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package thirdparty.fixture;

import java.util.Set;

/**
 * Test-only stand-in for a record owned by somebody other than Vertique — a third-party or JDK
 * record an application could legitimately embed in the identity-snapshot serialization graph.
 *
 * <p>It lives deliberately outside {@code dev.vertique.*} so
 * {@code dev.vertique.security.runtime.IdentitySnapshotCodecTest} can prove the snapshot structural
 * guard descends into records by <em>shape</em> rather than by code ownership: an embedded foreign
 * record whose component is an unordered {@link Set} reintroduces the issue-#181 cross-process HMAC
 * failure exactly like a Vertique-owned one would. Its only reason to exist is to be walked.
 *
 * @param tags an unordered set component — the offender the structural guard must catch
 */
public record ThirdPartyRecordFixture(Set<String> tags) {}
