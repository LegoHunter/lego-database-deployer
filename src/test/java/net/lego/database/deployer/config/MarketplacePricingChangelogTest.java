package net.lego.database.deployer.config;

import liquibase.Liquibase;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.util.StringUtil;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the shared marketplace_pricing changelog (HOMELAB-31): its changesets, how Liquibase splits
 * the baseline SQL, and the marketplace reference data. The baseline SQL is MySQL-only and is proven
 * by building marketplace_pricing_scratch.
 */
class MarketplacePricingChangelogTest {

    private static final String MASTER = "db/marketplace-pricing/db.changelog-master.yaml";

    @Test
    void changelogHoldsTheBaselineThenTheReferenceData() throws Exception {
        assertThat(changeSets()).extracting(ChangeSet::getId).containsExactly("1.0.0-baseline", "1.1.0-reference-data");
    }

    @Test
    void baselineCreatesTheEightTablesInWholeStatementsWithNoDropsAndNoHardCodedSchema() throws Exception {
        RawSQLChange change = (RawSQLChange) changeSets().get(0).getChanges().get(0);
        String sql = change.getSql();

        assertThat(sql).doesNotContain("lego_dev", "lego_sandbox", "lego_prod", "marketplace_pricing`");
        assertThat(sql).doesNotContainPattern("(?i)\\bDROP\\b");
        assertThat(change.isSplitStatements()).isTrue();

        List<String> statements = Arrays.asList(StringUtil.processMultiLineSQL(sql, false, true, null));
        List<String> creates = statements.stream().filter(s -> s.contains("CREATE TABLE IF NOT EXISTS")).toList();
        assertThat(creates).hasSize(8);
        for (String create : creates) {
            assertThat(BaselineChangelogTest.parenthesisDepth(create)).as(BaselineChangelogTest.firstLine(create)).isZero();
        }

        Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS `pricing_test`\\.`([a-z_]+)`").matcher(sql);
        List<String> tables = new ArrayList<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        assertThat(tables).containsExactly("marketplace", "canonical_item", "marketplace_item", "want_list_item",
                "crawl_target", "market_snapshot", "market_listing", "crawl_attempt");
    }

    @Test
    void theQueueHasOneRowPerMarketplaceItemAndQueryCondition() throws Exception {
        String sql = ((RawSQLChange) changeSets().get(0).getChanges().get(0)).getSql();

        assertThat(sql).contains("UNIQUE INDEX `uq_crawl_target_marketplace_item_condition` "
                + "(`marketplace_code` ASC, `canonical_item_id` ASC, `query_condition_code` ASC)");
        assertThat(sql).contains("PRIMARY KEY (`environment_code`, `canonical_item_id`, `condition_code`, `completeness_code`)");
        assertThat(sql).contains("UNIQUE INDEX `uq_canonical_item_type_number` (`item_type_code` ASC, `item_number` ASC)");
    }

    @Test
    void referenceDataLoadsTheMarketplacesAndARerunChangesNothing() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pricing" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        // The baseline SQL is MySQL-only, so H2 records it without running it: create the one table
        // the reference data needs, with the columns the baseline gives it.
        execute(dataSource, """
                CREATE TABLE marketplace (marketplace_code VARCHAR(32) PRIMARY KEY, display_name VARCHAR(100) NOT NULL,
                  base_url VARCHAR(500), default_currency_code VARCHAR(8) NOT NULL,
                  crawl_enabled TINYINT NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)""");

        run(dataSource, DeployerMode.UPDATE);
        run(dataSource, DeployerMode.UPDATE);

        assertThat(value(dataSource, "SELECT COUNT(*) FROM marketplace")).isEqualTo("2");
        assertThat(value(dataSource, "SELECT crawl_enabled FROM marketplace WHERE marketplace_code = 'BRICKLINK'")).isEqualTo("1");
        assertThat(value(dataSource, "SELECT crawl_enabled FROM marketplace WHERE marketplace_code = 'EBAY'")).isEqualTo("0");
        assertThat(value(dataSource, "SELECT COUNT(*) FROM DATABASECHANGELOG")).isEqualTo("2");
    }

    @Test
    void baselineRefusesASchemaThatIsAlreadyBuilt() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pricingbuilt" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        execute(dataSource, "CREATE TABLE market_snapshot (market_snapshot_id BIGINT)");

        assertThatThrownBy(() -> run(dataSource, DeployerMode.UPDATE)).hasStackTraceContaining("CHANGELOG_SYNC");
    }

    private static void run(JdbcDataSource dataSource, DeployerMode mode) throws Exception {
        DeployerLiquibase liquibase = new DeployerLiquibase(mode);
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(DeployerChangelog.MARKETPLACE_PRICING.getChangeLog());
        liquibase.setChangeLogParameters(Map.of("marketplace-pricing-schema", "PUBLIC"));
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    private static List<ChangeSet> changeSets() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pricingparse" + System.nanoTime());
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase(MASTER, new ClassLoaderResourceAccessor(), database);
            liquibase.getChangeLogParameters().set("marketplace-pricing-schema", "pricing_test");
            return liquibase.getDatabaseChangeLog().getChangeSets();
        }
    }

    private static String value(JdbcDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static void execute(JdbcDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
