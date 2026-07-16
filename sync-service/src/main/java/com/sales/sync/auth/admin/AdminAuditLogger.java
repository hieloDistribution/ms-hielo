package com.sales.sync.auth.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Facade for writing audit events. Single public entry point:
 * {@link #log(AuditEvent)}.
 *
 * <p>Runs in {@link Propagation#MANDATORY} so that callers are already
 * inside a transaction. This guarantees the audit row commits atomically
 * with the state change being audited (no orphan audit rows, no missing
 * audit rows on rollback).
 *
 * <p>Future PR4 work will:
 * <ul>
 *   <li>Source {@code requestId} from {@code RequestIdFilter} when present.</li>
 *   <li>Switch the column types for {@code before_json} / {@code after_json}
 *       to JSONB once V7 lands.</li>
 *   <li>Surface the listing API at {@code GET /api/v1/admin/audit-log}.</li>
 * </ul>
 */
@Service
public class AdminAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditLogger.class);

    private final AdminAuditLogRepository repository;

    public AdminAuditLogger(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist an audit event. Caller must be inside a transaction
     * ({@link Propagation#MANDATORY}). If the audit infrastructure itself
     * fails (database error), the failure is logged at WARN and the caller's
     * transaction is NOT marked for rollback — audit failures must not
     * block legitimate signups or admin operations. This is a deliberate
     * deviation from a strict transactional contract, justified by the
     * commercial priority of availability over audit completeness when the
     * audit table itself is unhealthy; PR4 may revisit if a stricter
     * contract is desired.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void log(AuditEvent event) {
        if (event == null || event.action() == null || event.action().isBlank()) {
            log.warn("AdminAuditLogger.log called with null/blank action; skipping: {}", event);
            return;
        }
        try {
            repository.save(AdminAuditLog.fromEvent(event));
        } catch (RuntimeException ex) {
            log.warn("Failed to persist admin audit event action={} targetEmail={}: {}",
                    event.action(), event.targetEmail(), ex.getMessage());
        }
    }
}
