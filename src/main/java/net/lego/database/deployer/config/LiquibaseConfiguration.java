package net.lego.database.deployer.config;

import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(LiquibaseProperties.class)
public class LiquibaseConfiguration {
    private static final Logger log = LoggerFactory.getLogger(LiquibaseConfiguration.class);

    static final String CHANGE_LOG = DeployerChangelog.ENVIRONMENT.getChangeLog();

    @Bean
    @ConditionalOnProperty(prefix = "spring.liquibase", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SpringLiquibase liquibase(@Qualifier("targetDataSource") final DataSource targetDataSource,
                                     final LiquibaseProperties liquibaseProperties,
                                     @Value("${deployer.mode:UPDATE}") final DeployerMode mode,
                                     @Value("${deployer.changelog:ENVIRONMENT}") final DeployerChangelog changelog) throws Exception {
        String targetSchema = liquibaseProperties.getParameters() == null
                ? null : liquibaseProperties.getParameters().get(changelog.getSchemaParameter());
        String schema = TargetSchemaGuard.verify(targetDataSource, changelog, targetSchema);
        log.info("Deployer mode [{}], changelog [{}] on schema [{}], parameters {}",
                mode, changelog, schema, liquibaseProperties.getParameters());

        DeployerLiquibase liquibase = new DeployerLiquibase(mode);
        liquibase.setDataSource(targetDataSource);
        liquibase.setChangeLogParameters(liquibaseProperties.getParameters());
        liquibase.setChangeLog(changelog.getChangeLog());
        return liquibase;
    }
}
