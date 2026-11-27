// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.vertique.core.json.JsonProfileCodec;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.Jackson2JsonProfiles;
import dev.vertique.json.JacksonDefaults;

/** Named JSON profile that exposes structured weather values in snake case. */
public final class ExampleJsonProfile {
    public static final String ID = "weather-example";

    private ExampleJsonProfile() {}

    /** Creates the profile contribution used by the weather tool and MCP boundary. */
    public static JsonProfileCodec create() {
        var mapper = JacksonDefaults.applySystem(new ObjectMapper());
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return Jackson2JsonProfiles.of(JsonProfileId.of(ID), mapper);
    }
}
