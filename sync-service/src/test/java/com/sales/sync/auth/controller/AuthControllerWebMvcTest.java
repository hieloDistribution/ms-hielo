package com.sales.sync.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.security.AccountLockedException;
import com.sales.sync.auth.security.AuthContext;
import com.sales.sync.auth.security.InvalidCredentialsException;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.SecurityConfig;
import com.sales.sync.auth.service.AuthService;
import com.sales.sync.auth.service.RefreshRotationService;
import com.sales.sync.auth.service.SignupService;
import com.sales.sync.auth.service.UserProfileService;
import com.sales.sync.auth.support.AuthExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class)
@Import({AuthExceptionHandler.class, SecurityConfig.class})
class AuthControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AuthService authService;
    @MockBean RefreshRotationService refreshRotationService;
    @MockBean SignupService signupService;
    @MockBean UserProfileService userProfileService;
    @MockBean RefreshTokenRepository refreshTokenRepository;
    @MockBean PasswordEncoder passwordEncoder;
    @MockBean JwtService jwtService;
    @MockBean AuthContext authContext;

    @Test
    void login_valid_returns_200_with_tokens_and_expires_in_900() throws Exception {
        when(authService.login(any()))
                .thenReturn(new AuthResponse("access", "refresh", 900L, false));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"correctPassword123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token", is("access")))
                .andExpect(jsonPath("$.refresh_token", is("refresh")))
                .andExpect(jsonPath("$.expires_in", is(900)));
    }

    @Test
    void login_wrong_password_returns_401_invalid_credentials() throws Exception {
        when(authService.login(any()))
                .thenThrow(new InvalidCredentialsException("wrong password"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com","password":"wrongPassword!!"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("invalid_credentials")));
    }

    @Test
    void login_locked_account_returns_423_account_locked() throws Exception {
        when(authService.login(any()))
                .thenThrow(new AccountLockedException("locked"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"bob@example.com","password":"correctPassword123"}"""))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("account_locked")));
    }

    @Test
    void login_missing_email_returns_400_invalid_request() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"correctPassword123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_request")));
    }

    @Test
    void login_invalid_email_format_returns_400_invalid_request() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"correctPassword123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_request")));
    }

    @Test
    void refresh_missing_refresh_token_field_returns_400_invalid_request() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_request")));
    }
}
