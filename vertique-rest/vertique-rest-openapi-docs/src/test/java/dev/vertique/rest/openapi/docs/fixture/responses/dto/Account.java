// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An asymmetric DTO whose request and response wire shapes differ: {@code displayName} is read and
 * written, {@code password} is only read from a request ({@code WRITE_ONLY}), and {@code id} is
 * only written to a response ({@code READ_ONLY}).
 *
 * <p>Under a snake-case profile its request carries {@code display_name} and {@code password}, and
 * its response {@code display_name} and {@code id}.
 */
public class Account {

    private String displayName;
    private String password;
    private String id;

    /** Creates an empty account. */
    public Account() {}

    /**
     * Returns the display name, read and written.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Sets the display name.
     *
     * @param displayName the display name
     */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the password, which is read from a request and never written to a response.
     *
     * @return the password
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getPassword() {
        return password;
    }

    /**
     * Sets the password.
     *
     * @param password the password
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the identifier, which is written to a response and never read from a request.
     *
     * @return the identifier
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getId() {
        return id;
    }

    /**
     * Sets the identifier.
     *
     * @param id the identifier
     */
    public void setId(String id) {
        this.id = id;
    }
}
