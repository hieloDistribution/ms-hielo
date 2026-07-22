-- V9 owned by PR4 follow-up.
-- Adds the revoked_at column to admin_invites so the admin console can
-- cancel a pending one-time link before the invitee redeems it. The
-- revocation is idempotent and the redeem path treats revoked_at as a
-- hard reject (alongside used_at) — see AdminInviteRedeemController.

ALTER TABLE admin_invites
    ADD COLUMN revoked_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_admin_invites_active
    ON admin_invites (email)
    WHERE used_at IS NULL AND revoked_at IS NULL;
