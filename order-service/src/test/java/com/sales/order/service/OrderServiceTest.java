package com.sales.order.service;

import com.sales.order.model.Order;
import com.sales.order.model.OrderItem;
import com.sales.order.model.Product;
import com.sales.order.repository.OrderRepository;
import com.sales.order.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    void saveOrder_failsWhenOrderWeightIsBelow100Kg() {
        // Arrange
        Order order = new Order("order-123", "client-1", "salesperson-1", LocalDateTime.now(), new BigDecimal("600.00"));
        // Item is 5 quantity of a 10 kg product = 50 kg total weight (< 100 kg minimum)
        OrderItem item = new OrderItem("PROD-1", 5, new BigDecimal("120.00"));
        order.addItem(item);

        Product product = new Product("PROD-1", "Saco 10kg", new BigDecimal("120.00"), 100, 10.0);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product));

        // Act & Assert
        assertThatThrownBy(() -> orderService.saveOrder(order))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("El pedido no cumple con el peso mínimo requerido de 100 kg");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void saveOrder_failsWhenRouteWeightExceeds5000Kg() {
        // Arrange
        Order order = new Order("order-123", "client-1", "salesperson-1", LocalDateTime.now(), new BigDecimal("1200.00"));
        // Item is 12 quantity of a 10 kg product = 120 kg total weight (>= 100 kg)
        OrderItem item = new OrderItem("PROD-1", 12, new BigDecimal("120.00"));
        order.addItem(item);

        Product product = new Product("PROD-1", "Saco 10kg", new BigDecimal("120.00"), 100, 10.0);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product));
        when(orderRepository.findById("order-123")).thenReturn(Optional.empty());

        // Mock route weight already has 4900 kg. Adding 120 kg would be 5020 kg (> 5000 kg capacity)
        when(orderRepository.sumOrderWeightBySalespersonAndDate(eq("salesperson-1"), any(LocalDateTime.class)))
                .thenReturn(4900.0);

        // Act & Assert
        assertThatThrownBy(() -> orderService.saveOrder(order))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("La ruta del camión para hoy está al límite");

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void saveOrder_succeedsWhenAllConstraintsAreMet() {
        // Arrange
        Order order = new Order("order-123", "client-1", "salesperson-1", LocalDateTime.now(), new BigDecimal("1200.00"));
        OrderItem item = new OrderItem("PROD-1", 12, new BigDecimal("120.00")); // 120 kg
        order.addItem(item);

        Product product = new Product("PROD-1", "Saco 10kg", new BigDecimal("120.00"), 100, 10.0);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product));
        when(orderRepository.findById("order-123")).thenReturn(Optional.empty());
        
        // Mock route weight has 3000 kg. Adding 120 kg is 3120 kg (<= 5000 kg)
        when(orderRepository.sumOrderWeightBySalespersonAndDate(eq("salesperson-1"), any(LocalDateTime.class)))
                .thenReturn(3000.0);

        when(orderRepository.save(order)).thenReturn(order);

        // Act
        Order savedOrder = orderService.saveOrder(order);

        // Assert
        assertThat(savedOrder).isNotNull();
        assertThat(product.getStock()).isEqualTo(88); // 100 - 12
        verify(productRepository).save(product);
        verify(orderRepository).save(order);
    }

    @Test
    void saveOrder_subtractsOldOrderWeightOnUpdate() {
        // Arrange
        Order order = new Order("order-123", "client-1", "salesperson-1", LocalDateTime.now(), new BigDecimal("1200.00"));
        OrderItem item = new OrderItem("PROD-1", 12, new BigDecimal("120.00")); // New weight: 120 kg
        order.addItem(item);

        // Existing order had 15 quantity of PROD-1 = 150 kg
        Order existingOrder = new Order("order-123", "client-1", "salesperson-1", LocalDateTime.now(), new BigDecimal("1500.00"));
        OrderItem existingItem = new OrderItem("PROD-1", 15, new BigDecimal("120.00"));
        existingOrder.addItem(existingItem);

        Product product = new Product("PROD-1", "Saco 10kg", new BigDecimal("120.00"), 100, 10.0);
        when(productRepository.findById("PROD-1")).thenReturn(Optional.of(product));
        when(orderRepository.findById("order-123")).thenReturn(Optional.of(existingOrder));
        
        // Mock route weight has 4900 kg (which includes the old order's 150 kg)
        // Calculating: 4900 (current) - 150 (old) + 120 (new) = 4870 kg (<= 5000 kg)
        when(orderRepository.sumOrderWeightBySalespersonAndDate(eq("salesperson-1"), any(LocalDateTime.class)))
                .thenReturn(4900.0);

        when(orderRepository.save(order)).thenReturn(order);

        // Act
        Order savedOrder = orderService.saveOrder(order);

        // Assert
        assertThat(savedOrder).isNotNull();
        // Initial stock: 100
        // Reverted stock: 100 + 15 = 115
        // Subtracted stock: 115 - 12 = 103
        assertThat(product.getStock()).isEqualTo(103);
        verify(orderRepository).save(order);
    }
}
