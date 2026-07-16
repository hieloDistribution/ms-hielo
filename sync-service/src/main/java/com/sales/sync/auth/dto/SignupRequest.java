package com.sales.sync.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service signup payload.
 *
 * <p>The {@code role} field is {@link Deprecated}: the server always creates
 * the user with {@code role = CLIENT} and ignores any client-supplied value.
 * The field is kept (so old/curious clients that still send it do not break
 * validation) and it is the trigger for a forensic audit row written with
 * {@code action='signup_role_ignored'} when the value is non-default. New
 * clients (Flutter UI updated to PR4+) should send no {@code role} field at
 * all.
 *
 * <p>Owned by change {@code admin-console} (PR1 signup-bypass closure).
 *
 * @param email       unique account email; lower-cased server-side.
 * @param password    bcrypt-hashed server-side; minimum 8 chars, max 128.
 * @param role        DEPRECATED. Always ignored server-side. If non-null and
 *                    not {@code "cliente"}, triggers an audit row. May be
 *                    {@code null}.
 * @param full_name   display name.
 * @param phone       optional contact phone.
 * @param dni         optional national-id (cliente-side).
 * @param business_name   optional commercial name (cliente-side).
 * @param business_lat    optional latitude (cliente-side).
 * @param business_lng    optional longitude (cliente-side).
 * @param business_address optional street address (cliente-side).
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 128) String password,

        @Deprecated
        @JsonProperty("role")
        @Size(max = 20)
        String role,

        @NotBlank @Size(max = 255) String full_name,
        @Size(max = 50) String phone,
        @Size(max = 50) String dni,
        @Size(max = 255) String business_name,
        Double business_lat,
        Double business_lng,
        @Size(max = 500) String business_address
) {
}
