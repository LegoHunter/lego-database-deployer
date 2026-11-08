package net.lego.database.deployer.config;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real changelog's 2.1.0-reference-data changeset on H2 (HOMELAB-57). The baseline SQL is
 * MySQL-only, so H2 records it without running it; the lookup tables are created here with the
 * columns the baseline gives them. Checks that an empty database gets every row, that a re-run
 * changes nothing, and that a database already holding the rows (lego_sandbox) is left as it is.
 */
class ReferenceDataChangelogTest {

    private static final String MASTER = "db/changelog/db.changelog-master.yaml";

    /** Rows per table, as exported from lego_sandbox on 2026-11-08. */
    private static final Map<String, Integer> EXPECTED_ROWS = new LinkedHashMap<>();

    static {
        EXPECTED_ROWS.put("external_service_type", 5);
        EXPECTED_ROWS.put("external_service", 10);
        EXPECTED_ROWS.put("external_service_capability", 8);
        EXPECTED_ROWS.put("condition", 11);
        EXPECTED_ROWS.put("cost_type", 6);
        EXPECTED_ROWS.put("transaction_type", 10);
        EXPECTED_ROWS.put("transaction_platform", 5);
        EXPECTED_ROWS.put("payment_platform", 2);
        EXPECTED_ROWS.put("carrier", 4);
        EXPECTED_ROWS.put("item_inventory_sale_intent", 3);
        EXPECTED_ROWS.put("item_inventory_state", 5);
    }

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:reference" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        execute("""
                CREATE TABLE external_service_type (external_service_type_id INT PRIMARY KEY,
                  external_service_type_name VARCHAR(45), external_service_type_description VARCHAR(255));
                CREATE TABLE external_service (external_service_id INT PRIMARY KEY, service_code VARCHAR(64),
                  display_name VARCHAR(128), service_url VARCHAR(255), external_service_type_id INT);
                CREATE TABLE external_service_capability (external_service_id INT, capability_code VARCHAR(64),
                  PRIMARY KEY (external_service_id, capability_code));
                CREATE TABLE condition (condition_id INT PRIMARY KEY, condition_code VARCHAR(4),
                  condition_description VARCHAR(45), condition_text VARCHAR(255));
                CREATE TABLE cost_type (cost_type_code VARCHAR(16) PRIMARY KEY, cost_type_name VARCHAR(45),
                  cost_type_description VARCHAR(255));
                CREATE TABLE transaction_type (transaction_type_code VARCHAR(2) PRIMARY KEY,
                  transaction_type_description VARCHAR(45), conversion_factor INT);
                CREATE TABLE transaction_platform (transaction_platform_id INT PRIMARY KEY,
                  transaction_platform_name VARCHAR(45));
                CREATE TABLE payment_platform (payment_platform_id INT PRIMARY KEY,
                  payment_platform_name VARCHAR(45), payment_platform_url VARCHAR(255));
                CREATE TABLE carrier (carrier_code VARCHAR(32) PRIMARY KEY, carrier_name VARCHAR(64),
                  tracking_url_pattern VARCHAR(255));
                CREATE TABLE item_inventory_sale_intent (sale_intent_code VARCHAR(32) PRIMARY KEY,
                  sale_intent_name VARCHAR(64), sale_intent_description VARCHAR(255), active BOOLEAN, sort_order INT);
                CREATE TABLE item_inventory_state (inventory_state_code VARCHAR(32) PRIMARY KEY,
                  inventory_state_name VARCHAR(64), inventory_state_description VARCHAR(255), active BOOLEAN, sort_order INT);
                """);
    }

    @Test
    void anEmptyDatabaseGetsEveryLookupRowAndARerunChangesNothing() throws Exception {
        update();
        assertRowCounts();
        assertThat(value("SELECT service_code FROM external_service WHERE external_service_id = 2")).isEqualTo("BRICKLINK");
        assertThat(value("SELECT condition_code FROM condition WHERE condition_id = 11")).isEqualTo("SL");
        assertThat(value("SELECT condition_text FROM condition WHERE condition_id = 1")).isNull();
        assertThat(value("SELECT conversion_factor FROM transaction_type WHERE transaction_type_code = 'S'")).isEqualTo("-1");
        assertThat(value("SELECT ID FROM DATABASECHANGELOG WHERE ID = '2.1.0-reference-data'")).isEqualTo("2.1.0-reference-data");

        update();
        assertRowCounts();
    }

    @Test
    void aDatabaseThatAlreadyHoldsTheRowsIsLeftAsItIs() throws Exception {
        // lego_sandbox: identical rows already present, plus one row the changeset does not know.
        execute("""
                INSERT INTO external_service_type VALUES (2, 'MARKETPLACE', 'Marketplace');
                INSERT INTO external_service VALUES (2, 'BRICKLINK', 'BrickLink', 'https://www.bricklink.com', 2);
                INSERT INTO carrier VALUES ('local_courier', 'Local courier', NULL);
                """);

        update();

        assertThat(count("external_service")).isEqualTo(10);
        assertThat(count("external_service_type")).isEqualTo(5);
        assertThat(count("carrier")).isEqualTo(5);
        assertThat(value("SELECT carrier_name FROM carrier WHERE carrier_code = 'local_courier'")).isEqualTo("Local courier");
    }

    private void assertRowCounts() throws SQLException {
        for (Map.Entry<String, Integer> expected : EXPECTED_ROWS.entrySet()) {
            assertThat(count(expected.getKey())).as(expected.getKey()).isEqualTo(expected.getValue());
        }
    }

    private void update() throws Exception {
        DeployerLiquibase liquibase = new DeployerLiquibase(DeployerMode.UPDATE);
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/" + MASTER);
        liquibase.setChangeLogParameters(Map.of("environment-schema", "PUBLIC"));
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    private int count(String table) throws SQLException {
        return Integer.parseInt(value("SELECT COUNT(*) FROM " + table));
    }

    private String value(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String part : sql.split(";")) {
                if (!part.isBlank()) {
                    statement.execute(part);
                }
            }
        }
    }
}
