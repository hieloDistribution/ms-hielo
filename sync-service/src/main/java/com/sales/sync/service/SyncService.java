package com.sales.sync.service;

import com.sales.sync.model.MutationDto;
import com.sales.sync.model.ProcessedRequest;
import com.sales.sync.model.SyncResponseDto;
import com.sales.sync.repository.ProcessedRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final ProcessedRequestRepository processedRequestRepository;
    private final RestTemplate restTemplate;

    @Value("${order.service.url}")
    private String orderServiceUrl;

    public SyncService(ProcessedRequestRepository processedRequestRepository, RestTemplate restTemplate) {
        this.processedRequestRepository = processedRequestRepository;
        this.restTemplate = restTemplate;
    }

    /**
     * Procesa un lote de mutaciones del cliente de forma transaccional.
     * Si una mutación ya fue procesada con éxito (idempotencia), se salta.
     */
    @Transactional
    public SyncResponseDto processSync(List<MutationDto> mutations) {
        List<String> processedIds = new ArrayList<>();

        for (MutationDto mutation : mutations) {
            String mutationId = mutation.getId();
            
            // 1. Validar Idempotencia
            Optional<ProcessedRequest> existingRequest = processedRequestRepository.findById(mutationId);
            if (existingRequest.isPresent() && "SUCCESS".equals(existingRequest.get().getStatus())) {
                log.info("Mutacion ya procesada con exito (Idempotencia): {}", mutationId);
                processedIds.add(mutationId);
                continue;
            }

            // 2. Registrar/Actualizar estado a PENDING en base de datos local de idempotencia
            ProcessedRequest requestLog = existingRequest.orElseGet(() -> new ProcessedRequest(mutationId, "PENDING", LocalDateTime.now()));
            requestLog.setStatus("PENDING");
            requestLog.setProcessedAt(LocalDateTime.now());
            processedRequestRepository.save(requestLog);

            try {
                // 3. Delegar al Microservicio 2 (order-service)
                if ("ORDER".equalsIgnoreCase(mutation.getEntityType())) {
                    executeOrderMutation(mutation);
                } else {
                    throw new IllegalArgumentException("Tipo de entidad no soportado: " + mutation.getEntityType());
                }

                // 4. Si tiene éxito, actualizar estado a SUCCESS
                requestLog.setStatus("SUCCESS");
                processedRequestRepository.save(requestLog);
                processedIds.add(mutationId);
                log.info("Mutacion procesada exitosamente: {}", mutationId);

            } catch (Exception e) {
                log.error("Error procesando mutacion {}: {}", mutationId, e.getMessage(), e);
                // Marcar como FAILED para permitir futuros reintentos
                requestLog.setStatus("FAILED");
                processedRequestRepository.save(requestLog);
                
                // Retornamos el estado acumulado hasta el momento del fallo para que el cliente purgue lo exitoso
                return new SyncResponseDto(false, processedIds, "Error en mutacion " + mutationId + ": " + e.getMessage());
            }
        }

        return new SyncResponseDto(true, processedIds, null);
    }

    private void executeOrderMutation(MutationDto mutation) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String operation = mutation.getOperation();
        String url = orderServiceUrl + "/api/v1/orders";

        if ("CREATE".equalsIgnoreCase(operation) || "UPDATE".equalsIgnoreCase(operation)) {
            HttpEntity<String> entity = new HttpEntity<>(mutation.getPayload(), headers);
            // Enviamos POST/PUT al order-service. Usamos POST para ambos ya que el order-service manejara el guardado (save)
            restTemplate.postForEntity(url, entity, String.class);
        } else if ("DELETE".equalsIgnoreCase(operation)) {
            // Enviamos DELETE pasándole el entityId del pedido a eliminar
            restTemplate.delete(url + "/" + mutation.getEntityId());
        } else {
            throw new IllegalArgumentException("Operacion no soportada: " + operation);
        }
    }
}
