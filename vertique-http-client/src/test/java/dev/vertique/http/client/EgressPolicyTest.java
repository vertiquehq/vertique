// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.http.client.exception.HttpEgressDeniedException;
import dev.vertique.http.client.exception.HttpEgressDeniedException.Reason;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the egress matching rules. {@link EgressPolicy#check(URI)} never resolves a name and
 * never opens a connection, so every target here, including the admitted non-local hosts, is judged
 * from its text alone.
 */
class EgressPolicyTest {

    private static void assertAdmitted(EgressPolicy policy, String target) {
        assertThatCode(() -> policy.check(URI.create(target)))
                .as("target %s", target)
                .doesNotThrowAnyException();
    }

    private static void assertDenied(EgressPolicy policy, String target, Reason reason) {
        assertThatThrownBy(() -> policy.check(URI.create(target)))
                .as("target %s", target)
                .isInstanceOfSatisfying(
                        HttpEgressDeniedException.class,
                        e -> assertThat(e.reason()).as("reason for %s", target).isEqualTo(reason));
    }

    /** AC-058. */
    @Test
    @DisplayName("a wildcard never admits an IP literal or localhost, an exact entry does")
    void wildcardNeverAdmitsAnIpLiteralOrLocalhost() {
        // Given a policy with the bare wildcard and a suffix wildcard, and one with exact entries
        EgressPolicy wildcard = EgressPolicy.allowHosts(List.of("*", "*.example.com"), true);
        EgressPolicy exact = EgressPolicy.allowHosts(List.of("localhost", "203.0.113.7"), true);

        // When check runs for IP literals, localhost and ordinary names
        // Then the wildcard policy admits the names
        assertAdmitted(wildcard, "https://api.example.com/");
        assertAdmitted(wildcard, "https://internal.corp/");

        // And denies every IP literal form and localhost with HOST
        assertDenied(wildcard, "http://127.0.0.1:8080/", Reason.HOST);
        assertDenied(wildcard, "http://[::1]:8080/", Reason.HOST);
        assertDenied(wildcard, "http://localhost:8080/", Reason.HOST);
        assertDenied(wildcard, "https://203.0.113.7/", Reason.HOST);
        assertDenied(wildcard, "http://0x7f000001/", Reason.HOST);
        assertDenied(wildcard, "http://127.1/", Reason.HOST);

        // And the exact-entry policy admits localhost and the listed IP literal
        assertAdmitted(exact, "http://localhost:8080/");
        assertAdmitted(exact, "https://203.0.113.7/");
    }

    /** Supporting proof of the matching rules behind AC-056. */
    @Test
    @DisplayName(
            "exact and suffix matching lower-case, strip one trailing dot, and plain HTTP needs loopback or permission")
    void exactAndSuffixMatchingLowerCasesStripsOneTrailingDotAndPlainHttpNeedsLoopbackOrPermission() {
        // Given an exact entry, a suffix entry and a loopback entry, with plain HTTP not permitted
        EgressPolicy strict = EgressPolicy.allowHosts(List.of("api.example.com", "*.suffix.test", "127.0.0.1"), false);

        // When check runs for names in other letter case and with a trailing dot
        // Then the host compares lower-case with one trailing dot stripped
        assertAdmitted(strict, "https://API.Example.COM/");
        assertAdmitted(strict, "https://api.example.com./");
        assertAdmitted(strict, "https://A.SUFFIX.test./");

        // And a suffix wildcard matches subdomains at any depth but neither the suffix nor a longer name
        assertAdmitted(strict, "https://a.suffix.test/");
        assertAdmitted(strict, "https://b.a.suffix.test/");
        assertDenied(strict, "https://suffix.test/", Reason.HOST);
        assertDenied(strict, "https://xsuffix.test/", Reason.HOST);
        assertDenied(strict, "https://other.example.com/", Reason.HOST);

        // And plain HTTP to a non-loopback host is refused for its scheme, https always passes the scheme rule
        assertDenied(strict, "http://api.example.com/", Reason.SCHEME);
        assertDenied(strict, "http://a.suffix.test/", Reason.SCHEME);

        // And plain HTTP to a loopback literal passes without permission
        assertAdmitted(strict, "http://127.0.0.1:8080/");

        // And a host outside the list is refused for its host whatever the scheme
        assertDenied(strict, "http://evil.test/", Reason.HOST);

        // And permitting plain HTTP admits the listed non-loopback hosts over http
        EgressPolicy permissive =
                EgressPolicy.allowHosts(List.of("api.example.com", "*.suffix.test", "127.0.0.1"), true);
        assertAdmitted(permissive, "http://api.example.com/");
        assertAdmitted(permissive, "http://b.a.suffix.test/");
        assertDenied(permissive, "http://evil.test/", Reason.HOST);

        // And a target with no host or with user information is refused under every policy
        for (EgressPolicy policy : List.of(strict, permissive, EgressPolicy.allowAll())) {
            assertDenied(policy, "https:///path", Reason.NO_HOST);
            assertDenied(policy, "https://user:pw@api.example.com/", Reason.USER_INFO);
        }

        // And allowAll admits any host name over https
        assertAdmitted(EgressPolicy.allowAll(), "https://anything.example.org/");
    }
}
