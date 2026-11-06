package net.lego.database.deployer.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs each {@link DeployerMode} against H2 with a changelog that has the same guard as the real
 * starting changeset: halt when {@code item_inventory} already exists.
 */
class DeployerLiquibaseTest {

    private static final String CHANGE_LOG = "classpath:/db/test-changelog/master.yaml";

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:deployer" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void updateBuildsAnEmptySchemaAndRecordsTheChangeset() throws Exception {
        run(DeployerMode.UPDATE);

        assertThat(tableExists("ITEM_INVENTORY")).isTrue();
        assertThat(recorded()).isEqualTo("test-baseline:EXECUTED");
    }

    @Test
    void updateRefusesASchemaThatIsAlreadyBuilt() throws Exception {
        execute("CREATE TABLE item_inventory (item_inventory_id INT)");
        execute("INSERT INTO item_inventory VALUES (42)");

        assertThatThrownBy(() -> run(DeployerMode.UPDATE))
                .hasStackTraceContaining("Use deployer.mode=CHANGELOG_SYNC");
        assertThat(count("SELECT COUNT(*) FROM item_inventory")).isEqualTo(1);
    }

    @Test
    void changelogSyncRecordsWithoutRunningAnything() throws Exception {
        execute("CREATE TABLE item_inventory (item_inventory_id INT, kept VARCHAR(10))");
        execute("INSERT INTO item_inventory VALUES (42, 'data')");

        run(DeployerMode.CHANGELOG_SYNC);

        assertThat(recorded()).isEqualTo("test-baseline:EXECUTED");
        assertThat(count("SELECT COUNT(*) FROM item_inventory WHERE kept = 'data'")).isEqualTo(1);
        // A later update finds nothing to apply, so the guard never fires.
        run(DeployerMode.UPDATE);
        assertThat(count("SELECT COUNT(*) FROM DATABASECHANGELOG")).isEqualTo(1);
    }

    @Test
    void statusAppliesNothing() throws Exception {
        run(DeployerMode.STATUS);

        assertThat(tableExists("ITEM_INVENTORY")).isFalse();
        assertThat(count("SELECT COUNT(*) FROM DATABASECHANGELOG")).isZero();
    }

    private void run(DeployerMode mode) throws Exception {
        DeployerLiquibase liquibase = new DeployerLiquibase(mode);
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(CHANGE_LOG);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    private String recorded() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT ID, EXECTYPE FROM DATABASECHANGELOG")) {
            assertThat(resultSet.next()).isTrue();
            String row = resultSet.getString(1) + ":" + resultSet.getString(2);
            assertThat(resultSet.next()).isFalse();
            return row;
        }
    }

    private boolean tableExists(String name) throws SQLException {
        return count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '" + name + "'") > 0;
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
