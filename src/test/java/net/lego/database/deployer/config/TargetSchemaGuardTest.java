package net.lego.database.deployer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetSchemaGuardTest {

    @Test
    void acceptsTheConfiguredSchema() {
        assertThat(TargetSchemaGuard.check("lego_sandbox", "lego_sandbox")).isEqualTo("lego_sandbox");
        assertThat(TargetSchemaGuard.check("LEGO_DEV", "lego_dev")).isEqualTo("lego_dev");
    }

    @Test
    void refusesAConnectionToAnotherSchema() {
        assertThatThrownBy(() -> TargetSchemaGuard.check("lego_sandbox", "lego_dev"))
                .hasMessageContaining("connection is schema lego_sandbox but environment-schema is lego_dev");
    }

    @Test
    void refusesLegacyProdEitherWay() {
        assertThatThrownBy(() -> TargetSchemaGuard.check("lego_prod_legacy", "lego_prod_legacy"))
                .hasMessageContaining("never manages");
        assertThatThrownBy(() -> TargetSchemaGuard.check("lego_prod_legacy", "lego_sandbox"))
                .hasMessageContaining("never manages");
    }

    @Test
    void refusesAConnectionWithNoSchemaOrAMissingParameter() {
        assertThatThrownBy(() -> TargetSchemaGuard.check(null, "lego_dev")).hasMessageContaining("no default schema");
        assertThatThrownBy(() -> TargetSchemaGuard.verify(null, " ")).hasMessageContaining("environment-schema is not set");
    }

    @Test
    void eachChangelogBuildsOnlyItsOwnSchemas() {
        assertThat(TargetSchemaGuard.check(DeployerChangelog.MARKETPLACE_PRICING, "marketplace_pricing", "marketplace_pricing"))
                .isEqualTo("marketplace_pricing");
        assertThat(TargetSchemaGuard.check(DeployerChangelog.MARKETPLACE_PRICING,
                "marketplace_pricing_scratch", "marketplace_pricing_scratch")).isEqualTo("marketplace_pricing_scratch");

        assertThatThrownBy(() -> TargetSchemaGuard.check(DeployerChangelog.MARKETPLACE_PRICING, "lego_sandbox", "lego_sandbox"))
                .hasMessageContaining("MARKETPLACE_PRICING changelog does not build schema lego_sandbox");
        assertThatThrownBy(() -> TargetSchemaGuard.check(DeployerChangelog.ENVIRONMENT, "marketplace_pricing", "marketplace_pricing"))
                .hasMessageContaining("ENVIRONMENT changelog does not build schema marketplace_pricing");
    }

    @Test
    void namesTheParameterOfTheChangelogBeingRun() {
        assertThatThrownBy(() -> TargetSchemaGuard.check(DeployerChangelog.MARKETPLACE_PRICING,
                "marketplace_pricing_scratch", "marketplace_pricing"))
                .hasMessageContaining("but marketplace-pricing-schema is marketplace_pricing");
        assertThatThrownBy(() -> TargetSchemaGuard.verify(null, DeployerChangelog.MARKETPLACE_PRICING, null))
                .hasMessageContaining("marketplace-pricing-schema is not set");
    }
}
