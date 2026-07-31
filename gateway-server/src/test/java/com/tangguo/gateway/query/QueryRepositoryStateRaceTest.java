package com.tangguo.gateway.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.model.QueryStatus;
import com.tangguo.gateway.query.QueryRepository.CancelOutcome;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

class QueryRepositoryStateRaceTest {
    private QueryRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        String migration = new String(
                getClass()
                        .getResourceAsStream("/db/migration/V1__initial_schema.sql")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        for (String statement : migration.split(";")) {
            if (!statement.isBlank()) {
                jdbcTemplate.execute(statement);
            }
        }
        repository = new QueryRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void cancelledExecutionCannotBeOverwrittenByLateCompletion() {
        repository.insert(executingQuery("q-cancel-wins"));
        Instant cancelledAt = Instant.parse("2026-07-30T08:00:00Z");

        assertThat(repository.cancelActive("q-cancel-wins", cancelledAt)).isEqualTo(CancelOutcome.EXECUTING);
        assertThat(repository.completeExecuted("q-cancel-wins")).isFalse();

        StoredQuery stored = repository.require("q-cancel-wins");
        assertThat(stored.status()).isEqualTo(QueryStatus.CANCELLED);
        assertThat(stored.errorCode()).isEqualTo("QUERY_CANCELLED");
        assertThat(stored.updatedAt()).isEqualTo(cancelledAt);
    }

    @Test
    void completedExecutionIsAlreadyTerminalForLateCancellation() {
        repository.insert(executingQuery("q-execution-wins"));

        assertThat(repository.completeExecuted("q-execution-wins")).isTrue();
        assertThat(repository.cancelActive("q-execution-wins", Instant.now())).isEqualTo(CancelOutcome.NONE);

        StoredQuery stored = repository.require("q-execution-wins");
        assertThat(stored.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(stored.errorCode()).isNull();
    }

    /**
     * 两条带状态前置条件的 UPDATE 是竞态线性化点；按两个可能顺序执行即可稳定覆盖终态竞争。
     */
    private StoredQuery executingQuery(String id) {
        Instant now = Instant.parse("2026-07-30T07:59:00Z");
        return new StoredQuery(
                id,
                "token-actor",
                ActorType.API_TOKEN,
                "ds-1",
                "encrypted-sql",
                "encrypted-parameters",
                "fingerprint",
                "核对生产订单",
                100,
                100,
                QueryStatus.EXECUTING,
                List.of(),
                null,
                now.minusSeconds(5),
                now.minusSeconds(1),
                null,
                now.minusSeconds(10),
                now);
    }
}
