package net.lego.database.deployer.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every profile connects with a deployer account's key, never with the key the services of that environment use:
 * the service accounts have no DDL, and the deployer accounts reach one schema each.
 */
class DeployerProfileKeysTest {

    @Test
    void everyProfileUsesItsOwnDeployerKey() {
        Map<String, String> expected = Map.of(
                "sandbox", "rasppi5-01-lego-sandbox-deployer",
                "dev", "rasppi5-01-lego-dev-deployer",
                "prod", "rasppi5-01-lego-prod-deployer",
                "marketplace-pricing", "rasppi5-01-marketplace-pricing",
                "marketplace-pricing-scratch", "rasppi5-01-marketplace-pricing-scratch");

        expected.forEach((profile, key) ->
                assertThat(profile(profile).getProperty("target-database.datasource.database-key-name"))
                        .as("profile %s", profile)
                        .isEqualTo(key));
    }

    @Test
    void noEnvironmentProfileUsesTheKeyOfItsServices() {
        for (String environment : new String[]{"sandbox", "dev", "prod"}) {
            assertThat(profile(environment).getProperty("target-database.datasource.database-key-name"))
                    .isNotEqualTo("rasppi5-01-lego-" + environment);
        }
    }

    private static Properties profile(String profile) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application-" + profile + ".yml"));
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}
