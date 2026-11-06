package net.lego.database.deployer.config;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@link SpringLiquibase} that runs the action {@link DeployerMode} names instead of always running
 * {@code update}.
 */
public class DeployerLiquibase extends SpringLiquibase {

    private static final Logger log = LoggerFactory.getLogger(DeployerLiquibase.class);

    private final DeployerMode mode;

    public DeployerLiquibase(DeployerMode mode) {
        this.mode = mode;
    }

    public DeployerMode getMode() {
        return mode;
    }

    @Override
    protected void performUpdate(Liquibase liquibase) throws LiquibaseException {
        Contexts contexts = new Contexts(getContexts());
        LabelExpression labels = new LabelExpression(getLabelFilter());
        switch (mode) {
            case UPDATE -> super.performUpdate(liquibase);
            case CHANGELOG_SYNC -> {
                List<ChangeSet> pending = liquibase.listUnrunChangeSets(contexts, labels);
                log.warn("CHANGELOG_SYNC: recording {} changeset(s) as applied WITHOUT running them: {}",
                        pending.size(), ids(pending));
                liquibase.changeLogSync(contexts, labels);
            }
            case STATUS -> {
                liquibase.validate();
                List<ChangeSet> pending = liquibase.listUnrunChangeSets(contexts, labels);
                log.info("STATUS: checksums valid; {} changeset(s) not yet applied: {}", pending.size(), ids(pending));
            }
        }
    }

    private static List<String> ids(List<ChangeSet> changeSets) {
        return changeSets.stream().map(ChangeSet::getId).toList();
    }
}
