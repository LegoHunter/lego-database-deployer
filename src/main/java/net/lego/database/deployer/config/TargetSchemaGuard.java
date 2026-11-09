package net.lego.database.deployer.config;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses a run whose connection does not point at the schema the changelog is parameterized for.
 *
 * <p>The changelog qualifies every object with {@code ${environment-schema}}, while Liquibase keeps
 * its tracking tables in the connection's default schema. If the two differ, Liquibase would record
 * changes in one schema and make them in another. The guard also refuses the legacy PROD database,
 * which this deployer never manages, and a schema that belongs to the other {@link DeployerChangelog}.
 */
public final class TargetSchemaGuard {

    static final Set<String> NEVER_MANAGED = Set.of("lego_prod_legacy");

    private TargetSchemaGuard() {
    }

    public static String verify(DataSource dataSource, String environmentSchema) throws SQLException {
        return verify(dataSource, DeployerChangelog.ENVIRONMENT, environmentSchema);
    }

    public static String verify(DataSource dataSource, DeployerChangelog changelog, String targetSchema) throws SQLException {
        if (targetSchema == null || targetSchema.isBlank()) {
            throw new IllegalStateException("spring.liquibase.parameters." + changelog.getSchemaParameter() + " is not set");
        }
        String connected;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT DATABASE()")) {
            connected = resultSet.next() ? resultSet.getString(1) : null;
        }
        return check(changelog, connected, targetSchema);
    }

    static String check(String connectedSchema, String environmentSchema) {
        return check(DeployerChangelog.ENVIRONMENT, connectedSchema, environmentSchema);
    }

    static String check(DeployerChangelog changelog, String connectedSchema, String targetSchema) {
        String parameter = changelog.getSchemaParameter();
        String expected = targetSchema.trim().toLowerCase(Locale.ROOT);
        if (NEVER_MANAGED.contains(expected)) {
            throw new IllegalStateException("ABORTING: the deployer never manages " + targetSchema);
        }
        if (connectedSchema == null) {
            throw new IllegalStateException("ABORTING: the target connection has no default schema; expected "
                    + targetSchema);
        }
        String connected = connectedSchema.trim().toLowerCase(Locale.ROOT);
        if (NEVER_MANAGED.contains(connected)) {
            throw new IllegalStateException("ABORTING: the target connection is " + connectedSchema
                    + ", which the deployer never manages");
        }
        if (!connected.equals(expected)) {
            throw new IllegalStateException("ABORTING: the target connection is schema " + connectedSchema
                    + " but " + parameter + " is " + targetSchema);
        }
        if (!changelog.accepts(connected)) {
            throw new IllegalStateException("ABORTING: the " + changelog + " changelog does not build schema "
                    + connectedSchema);
        }
        return connected;
    }
}
