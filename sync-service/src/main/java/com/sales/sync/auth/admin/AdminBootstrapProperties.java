package com.sales.sync.auth.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration knobs for the first-admin bootstrap. All values have safe
 * defaults; the only operator-relevant ones are
 * {@link #bootstrapEnabled} (off in CI) and {@link #recoverEmail}
 * (used only on a recovery boot when no admin exists).
 *
 * <p>Owner: change {@code admin-console} PR2.
 */
@ConfigurationProperties(prefix = "app.admin")
public class AdminBootstrapProperties {

    /**
     * Whether the bootstrap runs at all. Set to {@code false} in CI /
     * test pipelines so a fresh test DB never produces an admin with
     * credentials in CI logs.
     */
    private boolean bootstrapEnabled = true;

    /**
     * If set, used as the email of the recovered admin when no active
     * admin exists. If unset and no active admin exists, a synthetic
     * {@code admin+<uuid>@bootstrap.local} email is generated.
     */
    private String recoverEmail = "";

    public boolean isBootstrapEnabled() { return bootstrapEnabled; }
    public void setBootstrapEnabled(boolean bootstrapEnabled) { this.bootstrapEnabled = bootstrapEnabled; }

    public String getRecoverEmail() { return recoverEmail; }
    public void setRecoverEmail(String recoverEmail) { this.recoverEmail = recoverEmail; }
}
