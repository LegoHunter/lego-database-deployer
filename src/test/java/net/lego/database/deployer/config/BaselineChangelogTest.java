package net.lego.database.deployer.config;

import liquibase.Liquibase;
import liquibase.change.Change;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the real changelog: the starting changeset, its guard, and how Liquibase splits its SQL;
 * then the reference data (HOMELAB-57). The SQL itself is MySQL-only and is proven by building lego_dev (HOMELAB-46).
 */
class BaselineChangelogTest {

    private static final String MASTER = "db/changelog/db.changelog-master.yaml";

    @Test
    void changelogHoldsTheBaselineThenTheReferenceDataThenTheMarketSnapshotReferenceThenTheGuardThenTheDropsThenTheAdditions() throws Exception {
        assertThat(changeSets()).extracting(ChangeSet::getId)
                .containsExactly("2.0.0-baseline", "2.1.0-reference-data", "2.2.0-market-snapshot-reference",
                        "2.3.0-pricing-decision-guard", "2.4.0-drop-pricing-apply-readiness",
                        "2.4.0-drop-legacy-pricing-crawl", "2.5.0-photo-keywords", "2.6.0-lot-image-sync",
                        "2.7.0-extended-description-sync", "2.8.0-photo-upload-tokens");
    }

    @Test
    void sqlSplitsIntoWholeStatementsWithNoDropsAndNoHardCodedSchema() throws Exception {
        RawSQLChange change = (RawSQLChange) baselineChanges().get(0);
        String sql = change.getSql();

        assertThat(sql).doesNotContain("lego_dev", "lego_sandbox", "lego_prod");
        assertThat(sql).doesNotContainPattern("(?i)\\bDROP\\s+(TABLE|SCHEMA|DATABASE)\\b");
        assertThat(change.isSplitStatements()).isTrue();

        List<String> statements = Arrays.asList(StringUtil.processMultiLineSQL(sql, false, true, null));
        List<String> creates = statements.stream().filter(s -> s.contains("CREATE TABLE IF NOT EXISTS")).toList();
        assertThat(creates).hasSize(52);
        // A semicolon inside a quoted COMMENT must not split a statement: each CREATE TABLE's
        // parentheses balance once quoted strings are ignored.
        for (String create : creates) {
            assertThat(parenthesisDepth(create)).as(firstLine(create)).isZero();
        }
        assertThat(String.join("\n", creates))
                .contains("for a LEGO item; this describes what an external system says the item is");
    }

    @Test
    void everyDefaultIsALiteralMySqlAccepts() throws Exception {
        // Workbench writes a string default exactly as typed, so DEFAULT AVAILABLE (no quotes) reaches
        // MySQL as an identifier and the CREATE TABLE fails (found building lego_dev, HOMELAB-46).
        String sql = ((RawSQLChange) baselineChanges().get(0)).getSql();
        Matcher matcher = Pattern.compile("\\bDEFAULT\\s+([^\\s,)]+)").matcher(sql);
        List<String> bad = new java.util.ArrayList<>();
        while (matcher.find()) {
            String value = matcher.group(1);
            boolean literal = value.startsWith("'") || value.startsWith("(") || value.matches("-?[0-9.]+")
                    || value.matches("(?i)NULL|TRUE|FALSE|CURRENT_TIMESTAMP(\\(\\d*\\))?|CHARACTER");
            if (!literal) {
                bad.add(value);
            }
        }
        assertThat(bad).as("unquoted DEFAULT values").isEmpty();
    }

    @Test
    void createsTheTablesTheModelHas() throws Exception {
        String sql = ((RawSQLChange) baselineChanges().get(0)).getSql();
        // getSql() returns the SQL with ${environment-schema} already replaced (lego_test here).
        Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS `lego_test`\\.`([a-z_0-9]+)`").matcher(sql);
        List<String> tables = new java.util.ArrayList<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        assertThat(tables).hasSize(52).doesNotHaveDuplicates()
                .contains("item_inventory", "item_inventory_seed_source", "marketplace_listing_sync_request",
                        "pricing_snapshot", "party_external_identity", "transaction_item_revenue");
    }

    @Test
    void marketSnapshotReferenceOnlyAddsNullableColumnsAndCanRollBack() throws Exception {
        ChangeSet changeSet = changeSets().get(2);
        String sql = ((RawSQLChange) changeSet.getChanges().get(0)).getSql();

        assertThat(sql).doesNotContain("lego_dev", "lego_sandbox", "lego_prod");
        assertThat(sql).doesNotContainPattern("(?i)\\bDROP\\b");
        assertThat(sql).contains("`lego_test`.`pricing_decision`", "`lego_test`.`pricing_apply_readiness`");
        assertThat(Pattern.compile("ADD COLUMN `market_snapshot_(id|captured_at)` [A-Z]+ NULL DEFAULT NULL").matcher(sql).results())
                .hasSize(4);
        assertThat(changeSet.getRollback().getChanges()).hasSize(1);
    }

    @Test
    void pricingDecisionGuardOnlyAddsNullableColumnsAndCanRollBack() throws Exception {
        ChangeSet changeSet = changeSets().get(3);
        String sql = ((RawSQLChange) changeSet.getChanges().get(0)).getSql();

        assertThat(sql).doesNotContain("lego_dev", "lego_sandbox", "lego_prod");
        assertThat(sql).doesNotContainPattern("(?i)\\bDROP\\b");
        assertThat(sql).contains("`lego_test`.`pricing_decision`");
        assertThat(sql).contains("ADD COLUMN `guard_status_code` VARCHAR(32) NULL DEFAULT NULL",
                "ADD COLUMN `guard_reason_code` VARCHAR(64) NULL DEFAULT NULL");
        assertThat(changeSet.getRollback().getChanges()).hasSize(1);
    }

    @Test
    void theDropsRemoveExactlyTheOldPricingTablesAndLinksAndCanRollBack() throws Exception {
        // HOMELAB-66 phase B and HOMELAB-67: nothing else may be dropped.
        ChangeSet readiness = changeSets().get(4);
        ChangeSet crawl = changeSets().get(5);
        String readinessSql = ((RawSQLChange) readiness.getChanges().get(0)).getSql();
        String crawlSql = ((RawSQLChange) crawl.getChanges().get(0)).getSql();
        String both = readinessSql + "\n" + crawlSql;

        assertThat(both).doesNotContain("lego_dev", "lego_sandbox", "lego_prod", "marketplace_pricing");
        assertThat(dropped(both, "DROP\\s+TABLE\\s+`lego_test`\\.`([a-z_]+)`"))
                .containsExactly("pricing_apply_readiness", "pricing_snapshot_listing", "pricing_snapshot", "pricing_crawl_work_item");
        assertThat(dropped(both, "DROP\\s+COLUMN\\s+`([a-z_]+)`"))
                .containsExactly("pricing_apply_readiness_id", "pricing_snapshot_id");
        assertThat(dropped(both, "DROP\\s+FOREIGN\\s+KEY\\s+`([a-z_0-9]+)`"))
                .containsExactly("fk_marketplace_listing_sync_request_apply_readiness1", "fk_pricing_decision_snapshot1");
        // The readiness table goes first: it references pricing_snapshot.
        assertThat(readinessSql).contains("DROP TABLE `lego_test`.`pricing_apply_readiness`");
        assertThat(readiness.getRollback().getChanges()).hasSize(1);
        assertThat(crawl.getRollback().getChanges()).hasSize(1);
    }

    private static List<String> dropped(String sql, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(sql);
        List<String> names = new java.util.ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    void thePhotoKeywordColumnsAreNullableAndOnlyAdded() throws Exception {
        // HOMELAB-96: NULL means "the photo has no such keyword", so none of the three may have a default value.
        ChangeSet photoKeywords = changeSets().get(6);
        String sql = ((RawSQLChange) photoKeywords.getChanges().get(0)).getSql();

        assertThat(sql).doesNotContain("lego_dev", "lego_sandbox", "lego_prod", "DROP ", "NOT NULL");
        assertThat(sql).contains("`lego_test`.`item_inventory_photo`");
        assertThat(dropped(sql, "ADD\\s+COLUMN\\s+`([a-z_]+)`\\s+\\w+\\s+NULL\\s+DEFAULT\\s+NULL"))
                .containsExactly("sealed_keyword", "built_once_keyword", "keywords");
        assertThat(photoKeywords.getRollback().getChanges()).hasSize(1);
    }

    @Test
    void theLotImageColumnsAreNullableAndOnlyAdded() throws Exception {
        // HOMELAB-92: NULL means "LegoHunter has stored no image on the lot", which is true for every existing listing.
        ChangeSet lotImage = changeSets().get(7);
        String sql = ((RawSQLChange) lotImage.getChanges().get(0)).getSql();

        assertThat(sql).doesNotContain("lego_dev", "lego_sandbox", "lego_prod", "DROP ", "NOT NULL");
        assertThat(sql).contains("`lego_test`.`bricklink_marketplace_listing`");
        assertThat(dropped(sql, "ADD\\s+COLUMN\\s+`([a-z0-9_]+)`\\s+[\\w()]+\\s+NULL\\s+DEFAULT\\s+NULL"))
                .containsExactly("lot_image_md5", "lot_image_synced_at");
        assertThat(lotImage.getRollback().getChanges()).hasSize(1);
    }

    @Test
    void theExtendedDescriptionColumnsAreNullableAndOnlyAdded() throws Exception {
        // HOMELAB-82: NULL means "LegoHunter has stored no Extended Description on the lot", true for every existing listing.
        ChangeSet extendedDescription = changeSets().get(8);
        String sql = ((RawSQLChange) extendedDescription.getChanges().get(0)).getSql();

        assertThat(sql).doesNotContain("lego_dev", "lego_sandbox", "lego_prod", "DROP ", "NOT NULL");
        assertThat(sql).contains("`lego_test`.`bricklink_marketplace_listing`");
        assertThat(dropped(sql, "ADD\\s+COLUMN\\s+`([a-z0-9_]+)`\\s+[\\w()]+\\s+NULL\\s+DEFAULT\\s+NULL"))
                .containsExactly("extended_description_hash", "extended_description_synced_at");
        assertThat(extendedDescription.getRollback().getChanges()).hasSize(1);
    }

    @Test
    void realChangelogRefusesUpdateOnABuiltSchemaButSyncs() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:baseline" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE item_inventory (item_inventory_id INT)");
        }

        assertThatThrownBy(() -> run(dataSource, DeployerMode.UPDATE)).hasStackTraceContaining("CHANGELOG_SYNC");

        run(dataSource, DeployerMode.CHANGELOG_SYNC);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT ID, EXECTYPE FROM DATABASECHANGELOG")) {
            // CHANGELOG_SYNC records every changeset without running it, the reference data included.
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.0.0-baseline:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.1.0-reference-data:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.2.0-market-snapshot-reference:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.3.0-pricing-decision-guard:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.4.0-drop-pricing-apply-readiness:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.4.0-drop-legacy-pricing-crawl:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.5.0-photo-keywords:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.6.0-lot-image-sync:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.7.0-extended-description-sync:EXECUTED");
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1) + ":" + resultSet.getString(2)).isEqualTo("2.8.0-photo-upload-tokens:EXECUTED");
            assertThat(resultSet.next()).isFalse();
        }
    }

    private static void run(JdbcDataSource dataSource, DeployerMode mode) throws Exception {
        DeployerLiquibase liquibase = new DeployerLiquibase(mode);
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:/" + MASTER);
        liquibase.setChangeLogParameters(java.util.Map.of("environment-schema", "PUBLIC"));
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    private static List<ChangeSet> changeSets() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:parse" + System.nanoTime());
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase(MASTER, new ClassLoaderResourceAccessor(), database);
            liquibase.getChangeLogParameters().set("environment-schema", "lego_test");
            DatabaseChangeLog changeLog = liquibase.getDatabaseChangeLog();
            return changeLog.getChangeSets();
        }
    }

    private static List<Change> baselineChanges() throws Exception {
        return changeSets().get(0).getChanges();
    }

    static int parenthesisDepth(String statement) {
        int depth = 0;
        Character quote = null;
        for (char c : statement.toCharArray()) {
            if (quote != null) {
                if (c == quote) {
                    quote = null;
                }
            } else if (c == '\'' || c == '`' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth;
    }

    static String firstLine(String statement) {
        return statement.strip().lines().filter(line -> line.contains("CREATE TABLE")).findFirst().orElse(statement);
    }
}
