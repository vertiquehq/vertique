// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.security.CarriageRequirement;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdentitySnapshotConfig}'s {@code carriageRequirements} startup validation
 * and lookup (PRD-ID-002 §14.6 A9 "expected-but-absent carriage detection", P2.S0 commit 5b).
 *
 * <p>Verifies: a {@link CarriageRequirement#REQUIRED} target-kind fails config construction (fails
 * fast at startup) unless both freshness budgets are finite; the same config parses cleanly once
 * both budgets are set; an unlisted target-kind resolves to {@link CarriageRequirement#OPTIONAL} via
 * {@link IdentitySnapshotConfig#carriageRequirementFor(String)}; and an unrecognized requirement
 * string value fails config parse with a {@link ConfigurationException}.
 */
class IdentitySnapshotConfigTest {

    private static final String ACTIVE_SECRET = "super-secret-signing-key-material";

    private static SnapshotHmacConfig hmacKeys() {
        return new SnapshotHmacConfig(new SnapshotHmacKeyConfig("active", ACTIVE_SECRET), List.of());
    }

    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    @Test
    @DisplayName("CONTINUE_WITHOUT_IDENTITY parses as the degradation policy")
    void parsesContinueWithoutIdentityPolicy() {
        JsonObject section = new JsonObject()
                .put(
                        "hmacKeys",
                        new JsonObject()
                                .put(
                                        "active",
                                        new JsonObject().put("keyId", "active").put("secretRef", ACTIVE_SECRET)))
                .put("onDegradation", "CONTINUE_WITHOUT_IDENTITY");

        IdentitySnapshotConfig config = configParser().parse(section, IdentitySnapshotConfig.class);

        assertEquals(IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY, config.onDegradation());
    }

    @Test
    @DisplayName("the retired degradation policy literal is rejected")
    void rejectsRetiredAuditAndContinuePolicy() {
        JsonObject section = new JsonObject()
                .put(
                        "hmacKeys",
                        new JsonObject()
                                .put(
                                        "active",
                                        new JsonObject().put("keyId", "active").put("secretRef", ACTIVE_SECRET)))
                .put("onDegradation", "AUDIT_AND_" + "CONTINUE");

        assertThrows(
                ConfigurationException.class,
                () -> configParser().parse(section, IdentitySnapshotConfig.class),
                "the retired policy literal must fail config parsing rather than act as an alias");
    }

    @Test
    @DisplayName("a REQUIRED target with no finite freshness budgets fails startup with ConfigurationException")
    void requiredTargetWithoutFiniteFreshnessFailsStartup() {
        ConfigurationException ex = assertThrows(
                ConfigurationException.class,
                () -> new IdentitySnapshotConfig(
                        true,
                        IdentitySnapshotDegradationPolicy.FAIL,
                        hmacKeys(),
                        null,
                        null,
                        30_000L,
                        Map.of("delayed-job", CarriageRequirement.REQUIRED)),
                "a REQUIRED target with unbounded freshness is a standing bearer credential and must fail fast");
        assertEquals(
                true,
                ex.getMessage().contains("REQUIRED"),
                "the message must explain the REQUIRED-target constraint: " + ex.getMessage());
    }

    @Test
    @DisplayName("a REQUIRED target with both finite freshness budgets parses cleanly")
    void requiredTargetWithFiniteFreshnessParses() {
        IdentitySnapshotConfig config = assertDoesNotThrow(() -> new IdentitySnapshotConfig(
                true,
                IdentitySnapshotDegradationPolicy.FAIL,
                hmacKeys(),
                60_000L,
                3_600_000L,
                30_000L,
                Map.of("delayed-job", CarriageRequirement.REQUIRED)));

        assertEquals(CarriageRequirement.REQUIRED, config.carriageRequirementFor("delayed-job"));
    }

    @Test
    @DisplayName("an unlisted target-kind defaults to OPTIONAL")
    void unlistedTargetDefaultsToOptional() {
        IdentitySnapshotConfig config = new IdentitySnapshotConfig(
                true, IdentitySnapshotDegradationPolicy.FAIL, hmacKeys(), null, null, 30_000L, Map.of());

        assertEquals(CarriageRequirement.OPTIONAL, config.carriageRequirementFor("cron"));
    }

    @Test
    @DisplayName("an invalid carriageRequirements value fails config parse with ConfigurationException")
    void invalidRequirementValueFailsParse() {
        JsonObject section = new JsonObject()
                .put(
                        "hmacKeys",
                        new JsonObject()
                                .put(
                                        "active",
                                        new JsonObject().put("keyId", "active").put("secretRef", ACTIVE_SECRET)))
                .put("carriageRequirements", new JsonObject().put("delayed-job", "NOT_A_REAL_REQUIREMENT"));

        assertThrows(
                ConfigurationException.class,
                () -> configParser().parse(section, IdentitySnapshotConfig.class),
                "an unrecognized CarriageRequirement value must fail config parse, not silently default");
    }
}
