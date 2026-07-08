package com.sales.order.controller;

import com.sales.order.auth.security.DriverContext;
import com.sales.order.auth.security.VendorContext;
import com.sales.order.model.Order;
import com.sales.order.repository.OrderRepository;
import com.sales.order.repository.ProductRepository;
import com.sales.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderControllerTest {

    private MockMvc mvc;
    private OrderService orderService;
    private OrderRepository orderRepository;
    private ProductRepository productRepository;
    private VendorContext vendorContext;
    private DriverContext driverContext;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        orderRepository = mock(OrderRepository.class);
        productRepository = mock(ProductRepository.class);
        vendorContext = new VendorContext();
        driverContext = new DriverContext();

        mvc = MockMvcBuilders
                .standaloneSetup(new OrderController(orderService, orderRepository, productRepository, vendorContext, driverContext))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    // --- CREATE / UPDATE (POST) TESTS ---

    @Test
    void createOrder_withMatchingSalespersonId_returns201Created() throws Exception {
        UUID vendorId = UUID.randomUUID();
        vendorContext.set(Optional.of(vendorId));

        Order saved = new Order();
        saved.setSalespersonId(vendorId.toString());
        when(orderService.saveOrder(any(Order.class))).thenReturn(saved);

        String json = String.format("{\"salespersonId\":\"%s\",\"clientId\":\"client-123\",\"totalAmount\":150.00}", vendorId);

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.salespersonId").value(vendorId.toString()));
    }

    @Test
    void createOrder_withoutVendorInSession_returns403Forbidden() throws Exception {
        vendorContext.set(Optional.empty()); // No vendor in JWT token

        String json = "{\"salespersonId\":\"" + UUID.randomUUID() + "\",\"clientId\":\"client-123\",\"totalAmount\":150.00}";

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("asociada")));

        verify(orderService, never()).saveOrder(any());
    }

    @Test
    void createOrder_withSalespersonIdMismatch_returns403Forbidden() throws Exception {
        UUID authenticatedVendorId = UUID.randomUUID();
        UUID otherVendorId = UUID.randomUUID();
        vendorContext.set(Optional.of(authenticatedVendorId)); // Authenticated salesperson A

        // Payload salesperson B
        String json = String.format("{\"salespersonId\":\"%s\",\"clientId\":\"client-123\",\"totalAmount\":150.00}", otherVendorId);

        mvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("modificar")));

        verify(orderService, never()).saveOrder(any());
    }

    // --- GET TESTS ---

    @Test
    void getOrder_ownOrder_returns200Ok() throws Exception {
        UUID vendorId = UUID.randomUUID();
        vendorContext.set(Optional.of(vendorId));

        Order order = new Order();
        order.setSalespersonId(vendorId.toString());
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));

        mvc.perform(get("/api/v1/orders/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salespersonId").value(vendorId.toString()));
    }

    @Test
    void getOrder_otherSalespersonOrder_returns403Forbidden() throws Exception {
        UUID authenticatedVendorId = UUID.randomUUID();
        UUID ownerVendorId = UUID.randomUUID();
        vendorContext.set(Optional.of(authenticatedVendorId));

        Order order = new Order();
        order.setSalespersonId(ownerVendorId.toString());
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));

        mvc.perform(get("/api/v1/orders/order-1"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("otros")));
    }

    @Test
    void getOrder_withoutVendorInSession_returns403Forbidden() throws Exception {
        vendorContext.set(Optional.empty());

        mvc.perform(get("/api/v1/orders/order-1"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("consultar")));
    }

    // --- DELETE TESTS ---

    @Test
    void deleteOrder_ownOrder_returns200Ok() throws Exception {
        UUID vendorId = UUID.randomUUID();
        vendorContext.set(Optional.of(vendorId));

        Order order = new Order();
        order.setSalespersonId(vendorId.toString());
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));

        mvc.perform(delete("/api/v1/orders/order-1"))
                .andExpect(status().isOk());

        verify(orderService, times(1)).deleteOrder("order-1");
    }

    @Test
    void deleteOrder_otherSalespersonOrder_returns403Forbidden() throws Exception {
        UUID authenticatedVendorId = UUID.randomUUID();
        UUID ownerVendorId = UUID.randomUUID();
        vendorContext.set(Optional.of(authenticatedVendorId));

        Order order = new Order();
        order.setSalespersonId(ownerVendorId.toString());
        when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));

        mvc.perform(delete("/api/v1/orders/order-1"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("eliminar")));

        verify(orderService, never()).deleteOrder(anyString());
    }
}
