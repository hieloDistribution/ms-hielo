package com.sales.sync.auth.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent audit row. Append-only.
 *
 * <p>Mapped to the {@code admin_audit_log} table (V7 migration). The
 * before/after columns are JSONB in production; here they are
 * persisted as TEXT for portability with the H2 test profile (the
 * V7 migration uses {@code jsonb} which H2 does not understand; the
 * JPA mapping uses {@link Column#columnDefinition()} to match each
 * backend's actual type at the application level).
 *
 * <p>Append-only contract: the V7 migration revokes UPDATE and DELETE
 * on this table from the runtime role. Application code only
 * INSERTs (via {@link AuditLogWriter}) and SELECTs (via the
 * {@code /api/v1/admin/audit-log} endpoint).
 */
@Entity
@Table(name = "admin_audit_log")
public class AdminAuditLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(name = "target_email", length = 320)
    private String targetEmail;

    /**
     * Pre-PR4: TEXT-mapped for H2 portability. The V7 migration uses
     * JSONB; the JPA mapping declares both via columnDefinition so the
     * schema matches the DB at runtime.
     */
    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (requestId == null) requestId = UUID.randomUUID().toString();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getActorUserId() { return actorUserId; }
    public void setActorUserId(UUID actorUserId) { this.actorUserId = actorUserId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public UUID getTargetUserId() { return targetUserId; }
    public void setTargetUserId(UUID targetUserId) { this.targetUserId = targetUserId; }

    public String getTargetEmail() { return targetEmail; }
    public void setTargetEmail(String targetEmail) { this.targetEmail = targetEmail; }

    public String getBeforeJson() { return beforeJson; }
    public void setBeforeJson(String beforeJson) { this.beforeJson = beforeJson; }

    public String getAfterJson() { return afterJson; }
    public void setAfterJson(String afterJson) { this.afterJson = afterJson; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** Bridge from {@link AuditEvent} (PR1) to a persistable entity. */
    public static AdminAuditLog fromEvent(AuditEvent event) {
        AdminAuditLog row = new AdminAuditLog();
        row.actorUserId = event.actorUserId();
        row.action = event.action();
        row.targetUserId = event.targetUserId();
        row.targetEmail = event.targetEmail();
        row.beforeJson = event.beforeJson();
        row.afterJson = event.afterJson();
        row.notes = event.notes();
        row.requestId = event.requestId();
        return row;
    }
}
