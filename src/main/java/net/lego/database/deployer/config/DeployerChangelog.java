package net.lego.database.deployer.config;

import java.util.Locale;

/**
 * Which changelog one deployer run applies. Set with {@code deployer.changelog}.
 *
 * <p>Each changelog names the schema it builds through its own Liquibase parameter, and each refuses a
 * schema that belongs to the other, so an environment changelog can never run against the shared
 * pricing schema or the reverse.
 */
public enum DeployerChangelog {

    /** One LCMS environment schema: {@code lego_sandbox}, {@code lego_dev}, later {@code lego_prod}. The default. */
    ENVIRONMENT("classpath:/db/changelog/db.changelog-master.yaml", "environment-schema"),

    /**
     * The shared market pricing data every environment reads ({@code marketplace_pricing}, plus
     * {@code marketplace_pricing_scratch} for trial runs). HOMELAB-31.
     */
    MARKETPLACE_PRICING("classpath:/db/marketplace-pricing/db.changelog-master.yaml", "marketplace-pricing-schema");

    static final String MARKETPLACE_PRICING_PREFIX = "marketplace_pricing";

    private final String changeLog;
    private final String schemaParameter;

    DeployerChangelog(String changeLog, String schemaParameter) {
        this.changeLog = changeLog;
        this.schemaParameter = schemaParameter;
    }

    public String getChangeLog() {
        return changeLog;
    }

    /** The Liquibase parameter that holds this changelog's target schema. */
    public String getSchemaParameter() {
        return schemaParameter;
    }

    /** Whether this changelog may build the named schema. */
    boolean accepts(String schema) {
        boolean pricingSchema = schema.toLowerCase(Locale.ROOT).startsWith(MARKETPLACE_PRICING_PREFIX);
        return this == MARKETPLACE_PRICING ? pricingSchema : !pricingSchema;
    }
}
