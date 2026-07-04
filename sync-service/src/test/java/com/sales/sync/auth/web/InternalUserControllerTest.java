package com.sales.sync.auth.web;

import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test for {@link InternalUserController}. Built via standalone MockMvc
 * (no Spring Security — covered separately by
 * {@code InternalSecurityConfigFilterTest}). Jackson is wired explicitly.
 */
class InternalUserControllerTest {

    private MockMvc mvc;
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new InternalUserController(userRepository))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void userExists_returns200_withBody() throws Exception {
        UUID id = UUID.randomUUID();
        User u = new User();
        u.setId(id);
        u.setEmail("user@example.com");
        u.setLocked(false);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));

        mvc.perform(get("/internal/auth/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
    }

    @Test
    void userNotFound_returns404_withErrorBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/auth/users/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("user_not_found"))
                .andExpect(jsonPath("$.id").value(id.toString()));
    }
}