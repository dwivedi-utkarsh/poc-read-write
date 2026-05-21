package tech.vegapay.routingpoc.routing;

import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

/**
 * Wires a ShardingSphere read/write-splitting DataSource on top of the two
 * Testcontainers Postgres instances. The {@code @Primary DataSource} that
 * Hibernate / Spring see at runtime.
 *
 * The YAML is built at runtime because Testcontainer JDBC URLs only exist
 * after the containers start. YAML (rather than ShardingSphere's programmatic
 * builder) is used because the programmatic API has shifted across 5.x
 * minor versions while the YAML schema has stayed comparatively stable.
 *
 * Routing semantics:
 *   - Two static datasources: write_ds (primary container) and read_ds
 *     (replica container, connected as a SELECT-only role).
 *   - !READWRITE_SPLITTING rule: SELECT routes through a ROUND_ROBIN load
 *     balancer across [read_ds]; DML/DDL routes to write_ds.
 *   - transactionalReadQueryStrategy: PRIMARY → every read inside a JDBC
 *     transaction is pinned to write_ds (matches RDB tx semantics; ensures
 *     read-then-write inside a tx sees the just-written data).
 *   - !SINGLE rule with tables "*.*" registers every underlying table so the
 *     SQL binder can resolve them. Without this, pure read/write-splitting
 *     configurations fail every query with TableNotExistsException because
 *     GenericSchemaBuilder only loads metadata for tables declared in a
 *     TableContainedRule (sharding rules declare tables; r/w splitting
 *     doesn't).
 *   - sql-show: true so the test logs show which physical datasource
 *     ShardingSphere picked for each statement.
 */
@TestConfiguration
public class ShardingSphereTestConfig {

    static final String REPLICA_READONLY_USER = "readonly_app";
    static final String REPLICA_READONLY_PASSWORD = "readonly";

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("primary") PostgreSQLContainer<?> primary,
                                 @Qualifier("replica") PostgreSQLContainer<?> replica) throws Exception {
        String yaml = buildYaml(primary, replica);
        return YamlShardingSphereDataSourceFactory.createDataSource(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private String buildYaml(PostgreSQLContainer<?> primary, PostgreSQLContainer<?> replica) {
        return "mode:\n" +
               "  type: Standalone\n" +
               "\n" +
               "dataSources:\n" +
               dsBlock("write_ds", primary.getJdbcUrl(), primary.getUsername(), primary.getPassword()) +
               dsBlock("read_ds",  replica.getJdbcUrl(), REPLICA_READONLY_USER, REPLICA_READONLY_PASSWORD) +
               "\n" +
               "rules:\n" +
               "  - !READWRITE_SPLITTING\n" +
               "    dataSources:\n" +
               "      routing_ds:\n" +
               "        writeDataSourceName: write_ds\n" +
               "        readDataSourceNames:\n" +
               "          - read_ds\n" +
               "        transactionalReadQueryStrategy: PRIMARY\n" +
               "        loadBalancerName: round_robin\n" +
               "    loadBalancers:\n" +
               "      round_robin:\n" +
               "        type: ROUND_ROBIN\n" +
               "  - !SINGLE\n" +
               "    tables:\n" +
               "      - \"*.*\"\n" +
               "\n" +
               "props:\n" +
               "  sql-show: true\n";
    }

    private String dsBlock(String name, String jdbcUrl, String user, String pw) {
        return "  " + name + ":\n" +
               "    dataSourceClassName: com.zaxxer.hikari.HikariDataSource\n" +
               "    driverClassName: org.postgresql.Driver\n" +
               "    jdbcUrl: " + jdbcUrl + "\n" +
               "    username: " + user + "\n" +
               "    password: " + pw + "\n" +
               "    maximumPoolSize: 5\n" +
               "    minimumIdle: 1\n";
    }
}
