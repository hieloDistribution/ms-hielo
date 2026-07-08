package com.sales.order.controller;

import com.sales.order.auth.security.DriverContext;
import com.sales.order.auth.security.VendorContext;
import com.sales.order.model.Order;
import com.sales.order.model.Product;
import com.sales.order.repository.OrderRepository;
import com.sales.order.repository.ProductRepository;
import com.sales.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final VendorContext vendorContext;
    private final DriverContext driverContext;

    public OrderController(OrderService orderService,
                           OrderRepository orderRepository,
                           ProductRepository productRepository,
                           VendorContext vendorContext,
                           DriverContext driverContext) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.vendorContext = vendorContext;
        this.driverContext = driverContext;
    }

    @PostMapping
    public ResponseEntity<?> createOrUpdateOrder(@RequestBody @Valid Order order) {
        try {
            Optional<UUID> authenticatedVendorId = vendorContext.get();
            if (authenticatedVendorId.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a realizar pedidos: esta cuenta no está asociada a ningún vendedor.");
            }
            if (!authenticatedVendorId.get().toString().equalsIgnoreCase(order.getSalespersonId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a registrar o modificar pedidos para el distribuidor: " + order.getSalespersonId());
            }

            Order savedOrder = orderService.saveOrder(order);
            return ResponseEntity.status(HttpStatus.CREATED).body(savedOrder);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error interno: " + e.getMessage());
        }
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<?> deleteOrder(@PathVariable String orderId) {
        try {
            Optional<UUID> authenticatedVendorId = vendorContext.get();
            if (authenticatedVendorId.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a eliminar pedidos.");
            }

            Optional<Order> existingOrder = orderRepository.findById(orderId);
            if (existingOrder.isPresent() && !authenticatedVendorId.get().toString().equalsIgnoreCase(existingOrder.get().getSalespersonId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a eliminar pedidos de otros vendedores.");
            }

            orderService.deleteOrder(orderId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error interno: " + e.getMessage());
        }
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable String orderId) {
        Optional<UUID> authenticatedVendorId = vendorContext.get();
        if (authenticatedVendorId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("No está autorizado a consultar pedidos.");
        }

        return orderRepository.findById(orderId)
                .map(order -> {
                    if (!authenticatedVendorId.get().toString().equalsIgnoreCase(order.getSalespersonId())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body("No está autorizado a consultar pedidos de otros vendedores.");
                    }
                    return ResponseEntity.ok(order);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/catalog")
    public ResponseEntity<List<Product>> getCatalog() {
        return ResponseEntity.ok(productRepository.findAll());
    }

    @PostMapping("/{orderId}/accept")
    public ResponseEntity<?> acceptOrder(@PathVariable String orderId) {
        try {
            Optional<UUID> driverIdOpt = driverContext.get();
            if (driverIdOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a aceptar entregas: esta cuenta no está asociada a ningún repartidor.");
            }
            Order acceptedOrder = orderService.acceptOrder(orderId, driverIdOpt.get());
            return ResponseEntity.ok(acceptedOrder);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error interno: " + e.getMessage());
        }
    }

    @PostMapping("/{orderId}/dispatch")
    public ResponseEntity<?> dispatchOrder(@PathVariable String orderId) {
        try {
            Optional<UUID> vendorIdOpt = vendorContext.get();
            if (vendorIdOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a despachar pedidos: esta cuenta no está asociada a ningún vendedor.");
            }

            Optional<Order> existingOrderOpt = orderRepository.findById(orderId);
            if (existingOrderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Order existingOrder = existingOrderOpt.get();

            if (!vendorIdOpt.get().toString().equalsIgnoreCase(existingOrder.getSalespersonId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a despachar pedidos de otros vendedores.");
            }

            Order dispatchedOrder = orderService.dispatchOrder(orderId);
            return ResponseEntity.ok(dispatchedOrder);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error interno: " + e.getMessage());
        }
    }

    @PostMapping("/{orderId}/deliver")
    public ResponseEntity<?> deliverOrder(@PathVariable String orderId, @RequestParam String code) {
        try {
            Optional<UUID> driverIdOpt = driverContext.get();
            if (driverIdOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a realizar entregas: esta cuenta no está asociada a ningún repartidor.");
            }

            Optional<Order> existingOrderOpt = orderRepository.findById(orderId);
            if (existingOrderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Order existingOrder = existingOrderOpt.get();

            if (existingOrder.getDeliveryDriver() == null || 
                !driverIdOpt.get().equals(existingOrder.getDeliveryDriver().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a entregar este pedido porque no le está asignado.");
            }

            Order deliveredOrder = orderService.deliverOrder(orderId, code);
            return ResponseEntity.ok(deliveredOrder);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error interno: " + e.getMessage());
        }
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable String orderId) {
        try {
            Optional<UUID> vendorIdOpt = vendorContext.get();
            if (vendorIdOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a cancelar pedidos.");
            }

            Optional<Order> existingOrderOpt = orderRepository.findById(orderId);
            if (existingOrderOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Order existingOrder = existingOrderOpt.get();

            if (!vendorIdOpt.get().toString().equalsIgnoreCase(existingOrder.getSalespersonId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("No está autorizado a cancelar pedidos de otros vendedores.");
            }

            Order cancelledOrder = orderService.cancelOrder(orderId);
            return ResponseEntity.ok(cancelledOrder);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error interno: " + e.getMessage());
        }
    }
}
