-- V3: Idempotency log for the sync endpoint.
-- Tracks each client mutation ID so the SyncService can skip already-processed
-- requests and return a consistent SUCCESS response on replay.
CREATE TABLE IF NOT EXISTS processed_requests (
    client_request_id VARCHAR(36)  PRIMARY KEY,
    status            VARCHAR(20)  NOT NULL, -- SUCCESS | PENDING | FAILED
    processed_at      TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_processed_requests_status
    ON processed_requests(status);
