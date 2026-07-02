package com.sales.order.service;

import com.sales.order.model.Order;
import com.sales.order.model.OrderItem;
import com.sales.order.model.Product;
import com.sales.order.repository.OrderRepository;
import com.sales.order.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * Guarda o actualiza un pedido y actualiza el stock de los productos correspondientes.
     */
    @Transactional
    public Order saveOrder(Order order) {
        log.info("Procesando guardado de pedido: {}", order.getClientOrderId());

        // 1. Si el pedido ya existe (es una actualización), revertimos primero el stock anterior
        Optional<Order> existingOrderOpt = orderRepository.findById(order.getClientOrderId());
        if (existingOrderOpt.isPresent()) {
            Order existingOrder = existingOrderOpt.get();
            log.info("El pedido {} ya existe. Revirtiendo stock anterior para actualizar.", order.getClientOrderId());
            revertStock(existingOrder);
        }

        // 2. Validar stock para el nuevo estado del pedido y descontar
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

        // 3. Vincular los items con el pedido principal (JPA mapping)
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                item.setOrder(order);
            }
        }

        // 4. Guardar pedido en PostgreSQL
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
