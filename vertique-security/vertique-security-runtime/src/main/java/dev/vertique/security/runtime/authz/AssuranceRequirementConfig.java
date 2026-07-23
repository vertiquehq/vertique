// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRef;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Root configuration record for the identity-002 minimum-assurance policy hooks, deserialized from
 * the {@code identity.assurance} section of the application config via
 * {@link dev.vertique.core.config.ConfigParser} (PRD identity-002 FR-ID-AR-003).
 *
 * <p>{@link #patterns()} maps an {@link ActionPattern} — the same action-PATTERN grammar
 * {@link dev.vertique.security.authz.PolicyStatement} authors policies with: exact
 * ({@code "cms.content.delete"}) or trailing-suffix wildcard ({@code "payments.refund.*"},
 * {@code "payments.*"}) — to the {@link AssuranceRequirement} it places on a matching action's
 * caller. This is a legitimate dictionary under config.md R9: the <em>values</em> are
 * framework-schema'd ({@link AssuranceRequirement}'s two fixed fields), but the <em>keys</em> are
 * application-defined action patterns, not a framework routing identity — the same shape as
 * {@code IdentitySnapshotConfig#carriageRequirements()}'s per-target-kind map. An action matching no
 * configured pattern is not assurance-gated at all — see {@link AssuranceRequirementNarrower}'s
 * no-requirement passthrough.
 *
 * <p><strong>Overlapping patterns combine strictest-wins.</strong> When more than one configured
 * pattern matches the same action (e.g. a wildcard rule and a more-specific rule both apply),
 * {@link #requirementFor(ActionRef)} returns the <strong>strictest</strong> combination of every
 * match — the highest {@code minProviderLevel} and the shortest {@code maxAge} each win
 * independently (see {@link AssuranceRequirement#strictest(AssuranceRequirement, AssuranceRequirement)}).
 * A wildcard never weakens a more-specific sibling rule, and vice versa.
 *
 * <p>Config path: {@code identity.assurance.actions} — for example:
 *
 * <pre>{@code
 * identity:
 *   assurance:
 *     actions:
 *       "cms.content.delete":
 *         minProviderLevel: 2
 *         maxAgeMs: 300000
 *       "payments.refund.*":
 *         minProviderLevel: 3
 *         maxAgeMs: 60000
 * }</pre>
 *
 * @param patterns the per-action-pattern minimum-assurance requirements, keyed by the parsed
 *                 {@link ActionPattern}; never {@code null}, defensively copied; defaults to an
 *                 empty map when omitted (no action is assurance-gated by default)
 */
public record AssuranceRequirementConfig(Map<ActionPattern, AssuranceRequirement> patterns) {

    /**
     * Compact constructor — defensively copies {@link #patterns()}.
     */
    public AssuranceRequirementConfig {
        patterns = patterns == null ? Map.of() : Map.copyOf(patterns);
    }

    /**
     * Jackson-friendly factory. Parses each raw {@code identity.assurance.actions} key into an
     * {@link ActionPattern} — the FR-ID-AR-003 authoring contract — failing fast at config-load time
     * (never silently ungating a malformed pattern) when a key is blank or does not parse as a valid
     * action pattern.
     *
     * @param actions the configured pattern-string-to-requirement map; {@code null} treated as empty
     * @return the deserialized, validated configuration
     * @throws ConfigurationException if any key is blank or is not a valid {@link ActionPattern}
     */
    @JsonCreator
    static AssuranceRequirementConfig fromJson(
            @JsonProperty("actions") @Nullable Map<String, AssuranceRequirement> actions) {
        return new AssuranceRequirementConfig(parsePatterns(actions));
    }

    /**
     * Parses every raw {@code (patternString, requirement)} entry into an
     * {@code (ActionPattern, AssuranceRequirement)} entry, failing fast on a blank or malformed key.
     *
     * @param actions the raw config map; may be {@code null} (treated as empty)
     * @return the parsed, pattern-keyed map
     * @throws ConfigurationException if any key is blank or fails {@link ActionPattern} validation
     */
    private static Map<ActionPattern, AssuranceRequirement> parsePatterns(
            @Nullable Map<String, AssuranceRequirement> actions) {
        if (actions == null || actions.isEmpty()) {
            return Map.of();
        }
        Map<ActionPattern, AssuranceRequirement> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, AssuranceRequirement> entry : actions.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new ConfigurationException("identity.assurance.actions keys must be non-blank");
            }
            try {
                parsed.put(new ActionPattern(key), entry.getValue());
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException(
                        "identity.assurance.actions key \"" + key + "\" is not a valid action pattern: "
                                + e.getMessage(),
                        e);
            }
        }
        return Map.copyOf(parsed);
    }

    /**
     * Returns the default configuration: no actions are assurance-gated.
     *
     * @return the default configuration, with an empty {@link #patterns()} map
     */
    public static AssuranceRequirementConfig defaults() {
        return new AssuranceRequirementConfig(Map.of());
    }

    /**
     * Resolves the effective {@link AssuranceRequirement} for a canonical action, matching against
     * every configured {@link ActionPattern} (FR-ID-AR-003) rather than an exact-string lookup — a
     * wildcard pattern such as {@code "payments.refund.*"} gates every action it matches.
     *
     * <p>When more than one pattern matches {@code action}, the returned requirement is the
     * <strong>strictest</strong> combination of every match — see the class javadoc and
     * {@link AssuranceRequirement#strictest(AssuranceRequirement, AssuranceRequirement)}.
     *
     * @param action the action to resolve a requirement for; must not be {@code null}
     * @return the effective requirement, or {@link Optional#empty()} if no configured pattern matches
     *     {@code action}
     */
    public Optional<AssuranceRequirement> requirementFor(ActionRef action) {
        Objects.requireNonNull(action, "action");
        AssuranceRequirement strictest = null;
        for (Map.Entry<ActionPattern, AssuranceRequirement> entry : patterns.entrySet()) {
            if (entry.getKey().matches(action)) {
                strictest = strictest == null
                        ? entry.getValue()
                        : AssuranceRequirement.strictest(strictest, entry.getValue());
            }
        }
        return Optional.ofNullable(strictest);
    }
}
