package thitkho.corebankingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;
import thitkho.corebankingservice.config.JpaAuditingConfiguration;
import thitkho.corebankingservice.generator.AccountNumberGenerator;
import thitkho.corebankingservice.integration.customer.CustomerGateway;
import thitkho.corebankingservice.mapper.AccountMapperImpl;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.service.AccountService;
import thitkho.corebankingservice.service.impl.AccountServiceImpl;
import thitkho.corebankingservice.support.PostgresTestConfiguration;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AccountServiceImpl.class, AccountMapperImpl.class, AccountNumberGenerator.class,
        JpaAuditingConfiguration.class, PostgresTestConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountApiPersistenceTest {
    @Autowired private AccountService accountService;
    @Autowired private DataSource dataSource;
    @MockitoBean private CustomerGateway customerGateway;
    private JdbcTemplate jdbcTemplate;
    private final UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final String accessToken = "test-access-token";

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("delete from account_status_history");
        jdbcTemplate.update("delete from accounts where account_type = 'CUSTOMER'");
        given(customerGateway.findCurrentCustomer(accessToken)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(new CustomerInfo(customerId, "ACTIVE"));
        });
    }

    @Test
    void createThenRead_persistsAccountHistoryAndAuditedTimestamps() {
        var created = accountService.openAccount(ProductType.CHECKING, "VND", accessToken);
        var details = accountService.getOwnedAccount(created.id(), accessToken);
        var balance = accountService.getOwnedAccountBalance(created.id(), accessToken);

        assertThat(created.customerId()).isEqualTo(customerId);
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isEqualTo(created.createdAt());
        assertThat(details.id()).isEqualTo(created.id());
        assertThat(details.createdAt()).isNotNull();
        assertThat(details.closedAt()).isNull();
        assertThat(balance.balanceMinor()).isEqualTo("0");
        assertThat(balance.minorUnit()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from account_status_history where account_id = ?",
                Long.class, created.id())).isEqualTo(1L);
    }

    @Test
    void getBalance_largeUsdAmount_preservesExactStoredInteger() {
        var created = accountService.openAccount(ProductType.SAVINGS, "USD", accessToken);
        jdbcTemplate.update("update accounts set balance = ? where id = ?", Long.MAX_VALUE, created.id());

        var balance = accountService.getOwnedAccountBalance(created.id(), accessToken);

        assertThat(balance.currency()).isEqualTo("USD");
        assertThat(balance.minorUnit()).isEqualTo(2);
        assertThat(balance.balanceMinor()).isEqualTo("9223372036854775807");
    }

    @Test
    void create_historyConstraintFailure_rollsBackAccountAndHistory() {
        jdbcTemplate.execute("alter table account_status_history add constraint reject_test_opening check (new_status <> 'ACTIVE')");
        try {
            assertThatThrownBy(() -> accountService.openAccount(ProductType.CHECKING, "VND", accessToken))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(jdbcTemplate.queryForObject("select count(*) from accounts where account_type = 'CUSTOMER'", Long.class))
                    .isZero();
            assertThat(jdbcTemplate.queryForObject("select count(*) from account_status_history", Long.class)).isZero();
        } finally {
            jdbcTemplate.execute("alter table account_status_history drop constraint reject_test_opening");
        }
    }
}
