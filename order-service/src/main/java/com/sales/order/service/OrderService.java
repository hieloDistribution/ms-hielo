package com.sales.order.service;

import com.sales.order.model.DeliveryDriver;
import com.sales.order.model.Order;
import com.sales.order.model.OrderItem;
import com.sales.order.model.Product;
import com.sales.order.repository.DeliveryDriverRepository;
import com.sales.order.repository.OrderRepository;
import com.sales.order.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final double MIN_ORDER_WEIGHT_KG = 100.0;
    private static final double MAX_ROUTE_WEIGHT_KG = 5000.0;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final DeliveryDriverRepository deliveryDriverRepository;
    private final Random random = new Random();

    public OrderService(OrderRepository orderRepository, 
                        ProductRepository productRepository,
                        DeliveryDriverRepository deliveryDriverRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.deliveryDriverRepository = deliveryDriverRepository;
    }

    /**
     * Guarda o actualiza un pedido y actualiza el stock de los productos correspondientes.
     */
    @Transactional
    public Order saveOrder(Order order) {
        log.info("Procesando guardado de pedido: {}", order.getClientOrderId());

        // 1. Calcular el peso del nuevo estado del pedido
        double newOrderWeight = 0.0;
        for (OrderItem item : order.getItems()) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("El producto " + item.getProductId() + " no existe."));
            newOrderWeight += product.getWeightKg() * item.getQuantity();
        }

        // 2. Validar que cumpla con el pedido mínimo (100 kg)
        if (newOrderWeight < MIN_ORDER_WEIGHT_KG) {
            throw new IllegalArgumentException("El pedido no cumple con el peso mínimo requerido de 100 kg. Peso del pedido: " + newOrderWeight + " kg.");
        }

        double oldOrderWeight = 0.0;
        // 3. Si el pedido ya existe (es una actualización), calculamos su peso anterior y revertimos primero el stock anterior
        Optional<Order> existingOrderOpt = orderRepository.findById(order.getClientOrderId());
        if (existingOrderOpt.isPresent()) {
            Order existingOrder = existingOrderOpt.get();
            log.info("El pedido {} ya existe. Revirtiendo stock anterior para actualizar.", order.getClientOrderId());
            revertStock(existingOrder);
            
            for (OrderItem item : existingOrder.getItems()) {
                Product product = productRepository.findById(item.getProductId()).orElse(null);
                if (product != null) {
                    oldOrderWeight += product.getWeightKg() * item.getQuantity();
                }
            }
        }

        // 4. Validar capacidad máxima de la ruta del camión (5.000 kg) para hoy
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        double currentRouteWeight = orderRepository.sumOrderWeightBySalespersonAndDate(order.getSalespersonId(), startOfDay);

        if (currentRouteWeight - oldOrderWeight + newOrderWeight > MAX_ROUTE_WEIGHT_KG) {
            throw new IllegalArgumentException("La ruta del camión para hoy está al límite. No se puede cargar este pedido (" 
                    + newOrderWeight + " kg). Capacidad disponible: " + (MAX_ROUTE_WEIGHT_KG - (currentRouteWeight - oldOrderWeight)) + " kg.");
        }

        // 5. Validar stock para el nuevo estado del pedido y descontar
        for (OrderItem item : order.getItems()) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("El producto " + item.getProductId() + " no existe."));

            if (product.getStock() < item.getQuantity()) {
                throw new IllegalArgumentException("Stock insuficiente para el producto: " + product.getName() 
                        + ". Stock disponible: " + product.getStock() + ", solicitado: " + item.getQuantity());
            }

            // Descontar stock
            product.setStock(product.getStock() - item.getQuantity());
            productRepository.save(product);
        }

        // 6. Vincular los items con el pedido principal (JPA mapping)
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                item.setOrder(order);
            }
        }

        // 7. Guardar pedido en PostgreSQL
        return orderRepository.save(order);
    }

    /**
     * Asigna un repartidor a un pedido y marca el pedido como ACEPTADO.
     */
    @Transactional
    public Order acceptOrder(String orderId, UUID driverId) {
        log.info("Repartidor {} intenta aceptar el pedido {}", driverId, orderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró el pedido con id: " + orderId));
        
        DeliveryDriver driver = deliveryDriverRepository.findById(driverId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró el repartidor con id: " + driverId));

        if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalStateException("El pedido no se puede aceptar porque su estado actual es: " + order.getStatus());
        }

        order.setDeliveryDriver(driver);
        order.setStatus("ACCEPTED");
        order.setAcceptedAt(Instant.now());
        
        return orderRepository.save(order);
    }

    /**
     * Despacha el pedido generando el código de verificación (OTP) de 4 dígitos.
     */
    @Transactional
    public Order dispatchOrder(String orderId) {
        log.info("Despachando pedido: {}", orderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró el pedido con id: " + orderId));

        if (!"ACCEPTED".equalsIgnoreCase(order.getStatus()) && !"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalStateException("El pedido no se puede despachar en su estado actual: " + order.getStatus());
        }

        // Generar OTP de 4 dígitos
        String code = String.format("%04d", random.nextInt(10000));
        order.setVerificationCode(code);
        order.setStatus("DISPATCHED");
        
        log.info("Pedido {} despachado. Código OTP generado: {}", orderId, code);
        return orderRepository.save(order);
    }

    /**
     * Finaliza la entrega del pedido validando el código de verificación (OTP).
     */
    @Transactional
    public Order deliverOrder(String orderId, String code) {
        log.info("Intentando entregar pedido {} con código OTP: {}", orderId, code);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró el pedido con id: " + orderId));

        if (!"DISPATCHED".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalStateException("El pedido no está despachado. Estado actual: " + order.getStatus());
        }

        if (order.getVerificationCode() == null || !order.getVerificationCode().equals(code)) {
            throw new IllegalArgumentException("El código de verificación ingresado es incorrecto.");
        }

        order.setStatus("DELIVERED");
        order.setDeliveredAt(Instant.now());
        
        log.info("Pedido {} entregado exitosamente.", orderId);
        return orderRepository.save(order);
    }

    /**
     * Cancela un pedido y devuelve las cantidades al stock.
     */
    @Transactional
    public Order cancelOrder(String orderId) {
        log.info("Cancelando pedido: {}", orderId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró el pedido con id: " + orderId));

        if ("DELIVERED".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalStateException("No se puede cancelar un pedido que ya ha sido entregado.");
        }
        
        if ("CANCELLED".equalsIgnoreCase(order.getStatus())) {
            return order;
        }

        revertStock(order);
        order.setStatus("CANCELLED");
        return orderRepository.save(order);
    }

    /**
     * Elimina un pedido y devuelve las cantidades al stock de los productos.
     */
    @Transactional
    public void deleteOrder(String clientOrderId) {
        log.info("Procesando eliminacion de pedido: {}", clientOrderId);
        Order order = orderRepository.findById(clientOrderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontro el pedido a eliminar con id: " + clientOrderId));

        // Revertir el stock antes de eliminar físicamente
        revertStock(order);

        // Eliminar pedido
        orderRepository.delete(order);
        log.info("Pedido {} eliminado correctamente de la base de datos.", clientOrderId);
    }

    /**
     * Metodo helper para devolver cantidades de items al stock de productos.
     */
    private void revertStock(Order order) {
        for (OrderItem item : order.getItems()) {
            Optional<Product> productOpt = productRepository.findById(item.getProductId());
            if (productOpt.isPresent()) {
                Product product = productOpt.get();
                product.setStock(product.getStock() + item.getQuantity());
                productRepository.save(product);
                log.info("Stock revertido para producto {}: +{}", product.getId(), item.getQuantity());
            }
        }
    }
}
