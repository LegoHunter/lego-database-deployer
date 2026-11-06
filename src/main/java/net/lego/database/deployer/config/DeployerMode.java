package net.lego.database.deployer.config;

/**
 * What one deployer run does with the changelog. Set with {@code deployer.mode}.
 */
public enum DeployerMode {

    /** Apply every changeset that has not run yet (Liquibase {@code update}). The default. */
    UPDATE,

    /**
     * Record every changeset as applied without running any SQL (Liquibase {@code changelog-sync}).
     * Use it once to bring an existing, already-correct schema under Liquibase (HOMELAB-46).
     */
    CHANGELOG_SYNC,

    /**
     * Validate checksums and log the changesets that have not run yet. Applies nothing. Liquibase
     * still creates its two tracking tables when a schema has none.
     */
    STATUS
}
