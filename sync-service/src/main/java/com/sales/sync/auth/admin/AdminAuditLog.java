package com.sales.sync.auth.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of an administrative write or detected bypass attempt.
 *
 * <p>Mapped to {@code admin_audit_log} (created by the V7 Flyway migration
 * owned by change {@code admin-console} PR4). Hibernate's
 * {@code ddl-auto: create-drop} in the {@code test} profile generates the
 * same shape from this entity for {@code *IT} tests.
 *
 * <p>Append-only. The V7 migration revokes UPDATE and DELETE on this table
 * from public roles. This entity therefore exposes no setters other than
 * via the JPA lifecycle (i.e., setters are kept for tests but must not be
 * used in production code).
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
     * The shape will switch to JSONB in the V7 migration. For PR1 the
     * entity is TEXT-mapped to keep the {@code test} profile (H2 +
     * ddl-auto=create-drop) producing a workable schema; PR4 will revisit
     * this with a {@code @Convert} JSONB converter once V7 lands.
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

    /**
     * Convert an {@link AuditEvent} into a persistable entity. This is the
     * bridge {@link AdminAuditLogger} uses; keeping the conversion in the
     * entity keeps the {@code AdminAuditLogger} class minimal.
     */
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
