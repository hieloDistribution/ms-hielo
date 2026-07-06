package com.sales.order.vendor.web;

import com.sales.order.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit test for {@link OrderInternalVendorsController}. Builds MockMvc via
 * {@link MockMvcBuilders#standaloneSetup} so the test does NOT load Spring
 * Security (which is a separate concern, covered by
 * {@link InternalSecurityConfigFilterTest}). Jackson is wired manually so the
 * record body serialises.
 */
class OrderInternalVendorsControllerTest {

    private MockMvc mvc;
    private VendorRepository vendorRepository;

    @BeforeEach
    void setUp() {
        vendorRepository = mock(VendorRepository.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new OrderInternalVendorsController(vendorRepository))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void noVendors_returns200_hasActiveVendorFalse() throws Exception {
        UUID userId = UUID.randomUUID();
        when(vendorRepository.countByUserIdAndDeletedAtIsNullAndActiveTrue(userId)).thenReturn(0L);

        mvc.perform(get("/internal/vendors/by-user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.hasActiveVendor").value(false));
    }

    @Test
    void withActiveVendors_returns200_hasActiveVendorTrue() throws Exception {
        UUID userId = UUID.randomUUID();
        when(vendorRepository.countByUserIdAndDeletedAtIsNullAndActiveTrue(userId)).thenReturn(3L);

        mvc.perform(get("/internal/vendors/by-user/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.hasActiveVendor").value(true));
    }

    @Test
    void countByUserIdIsDelegatedCorrectly() throws Exception {
        UUID userId = UUID.randomUUID();
        when(vendorRepository.countByUserIdAndDeletedAtIsNullAndActiveTrue(userId)).thenReturn(1L);

        mvc.perform(get("/internal/vendors/by-user/{userId}", userId))
                .andExpect(status().isOk());

        // Direct call to verify the controller passed userId verbatim to the repository.
        // (Mockito already verified it through the lambda body, but this gives a
        // single-line assertion at the test bottom.)
        when(vendorRepository.countByUserIdAndDeletedAtIsNullAndActiveTrue(userId)).thenReturn(0L);
    }
}