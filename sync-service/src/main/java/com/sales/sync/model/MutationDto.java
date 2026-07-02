package com.sales.sync.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class MutationDto {

    @NotBlank(message = "El id de la mutacion es requerido")
    private String id; // UUID de la mutacion

    @NotBlank(message = "El tipo de entidad es requerido")
    private String entityType; // "ORDER"

    @NotBlank(message = "El id de la entidad es requerido")
    private String entityId; // UUID del pedido

    @NotBlank(message = "La operacion es requerida")
    private String operation; // "CREATE", "UPDATE", "DELETE"

    @NotBlank(message = "El payload no puede estar vacio")
    private String payload; // JSON serializado de la entidad

    @NotNull(message = "El timestamp es requerido")
    private Long timestamp;

    public MutationDto() {}

    public MutationDto(String id, String entityType, String entityId, String operation, String payload, Long timestamp) {
        this.id = id;
        this.entityType = entityType;
        this.entityId = entityId;
        this.operation = operation;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
