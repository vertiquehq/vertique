// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Pins the exact upstream schema fixture consumed by the MCP protocol proofs. */
class McpOfficialSchemaFixtureTest {
    private static final String SCHEMA_RESOURCE = "/mcp/schema/2026-07-28/schema.json";
    private static final String UPSTREAM_COMMIT = "aa7306efa4dcc03a2a9f2f223e3b2d7a0c5f3ded";
    private static final String EXPECTED_SHA_256 = "ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203";

    @Test
    void shouldMatchPinnedCommitAndSha256() throws IOException, NoSuchAlgorithmException {
        InputStream fixture = McpOfficialSchemaFixtureTest.class.getResourceAsStream(SCHEMA_RESOURCE);

        assertThat(fixture).as("vendored official schema fixture").isNotNull();
        try (fixture) {
            String actualSha256 = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(fixture.readAllBytes()));

            assertThat(UPSTREAM_COMMIT).isEqualTo("aa7306efa4dcc03a2a9f2f223e3b2d7a0c5f3ded");
            assertThat(actualSha256).isEqualTo(EXPECTED_SHA_256);
        }
    }
}
