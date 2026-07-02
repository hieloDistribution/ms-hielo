package com.sales.sync.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "processed_requests")
public class ProcessedRequest {

    @Id
    @Column(name = "client_request_id", length = 36)
    private String clientRequestId; // UUID enviado por el cliente

    @Column(name = "status", nullable = false, length = 20)
    private String status; // SUCCESS, PENDING, FAILED

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedRequest() {}

    public ProcessedRequest(String clientRequestId, String status, LocalDateTime processedAt) {
        this.clientRequestId = clientRequestId;
        this.status = status;
        this.processedAt = processedAt;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }
}
