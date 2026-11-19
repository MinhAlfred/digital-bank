package thitkho.corebankingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;
import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.config.JpaAuditingConfiguration;
import thitkho.corebankingservice.generator.AccountNumberGenerator;
import thitkho.corebankingservice.integration.customer.CustomerGateway;
import thitkho.corebankingservice.mapper.AccountMapperImpl;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.service.AccountService;
import thitkho.corebankingservice.service.impl.AccountServiceImpl;
import thitkho.corebankingservice.support.PostgresTestConfiguration;

import javax.sql.DataSource;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
@ActiveProfiles("test")
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

    @ParameterizedTest
    @CsvSource({"ACTIVE,ACTIVE", "ACTIVE,FROZEN", "ACTIVE,CLOSED",
            "SUSPENDED,ACTIVE", "SUSPENDED,FROZEN", "SUSPENDED,CLOSED",
            "CLOSED,ACTIVE", "CLOSED,FROZEN", "CLOSED,CLOSED"})
    void getAccountAndBalance_ownerCanReadRegardlessOfCustomerAndAccountState(String customerStatus, String accountStatus) {
        var created = accountService.openAccount(ProductType.CHECKING, "VND", accessToken);
        Instant closedAt = "CLOSED".equals(accountStatus) ? Instant.parse("2026-11-18T03:00:00Z") : null;
        jdbcTemplate.update("update accounts set status = ?, closed_at = ?, balance = ? where id = ?",
                accountStatus, closedAt == null ? null : Timestamp.from(closedAt), 125000L, created.id());
        given(customerGateway.findCurrentCustomer(accessToken)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(new CustomerInfo(customerId, customerStatus));
        });

        var details = accountService.getOwnedAccount(created.id(), accessToken);
        var balance = accountService.getOwnedAccountBalance(created.id(), accessToken);

        assertThat(details.status().name()).isEqualTo(accountStatus);
        assertThat(details.closedAt()).isEqualTo(closedAt);
        assertThat(details.balanceMinor()).isEqualTo("125000");
        assertThat(balance.status().name()).isEqualTo(accountStatus);
        assertThat(balance.balanceMinor()).isEqualTo("125000");
        assertThat(balance.minorUnit()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-customer", "missing-account", "system-account", "other-owner"})
    void getAccountAndBalance_inaccessibleResource_hasSameNotFoundResponse(String scenario) {
        UUID accountId = UUID.fromString("20000000-0000-0000-0000-000000000099");
        if ("system-account".equals(scenario)) {
            accountId = jdbcTemplate.queryForObject("select id from accounts where account_number = 'SYS-CASH-VND'", UUID.class);
        } else if (!"missing-account".equals(scenario)) {
            accountId = accountService.openAccount(ProductType.CHECKING, "VND", accessToken).id();
        }
        if ("missing-customer".equals(scenario)) {
            given(customerGateway.findCurrentCustomer(accessToken)).willReturn(Optional.empty());
        } else if ("other-owner".equals(scenario)) {
            given(customerGateway.findCurrentCustomer(accessToken)).willReturn(Optional.of(new CustomerInfo(
                    UUID.fromString("10000000-0000-0000-0000-000000000002"), "ACTIVE")));
        }

        assertAccountHidden(accountId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "CLOSED"})
    void openAccount_inactiveCustomer_doesNotPersistAccountOrHistory(String status) {
        given(customerGateway.findCurrentCustomer(accessToken)).willReturn(Optional.of(new CustomerInfo(customerId, status)));

        assertThatThrownBy(() -> accountService.openAccount(ProductType.CHECKING, "VND", accessToken))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo("CUSTOMER_NOT_ELIGIBLE"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from accounts where account_type = 'CUSTOMER'", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from account_status_history", Long.class)).isZero();
    }

    private void assertAccountHidden(UUID accountId) {
        assertThatThrownBy(() -> accountService.getOwnedAccount(accountId, accessToken))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
                    assertThat(exception.getStatus().value()).isEqualTo(404);
                });
        assertThatThrownBy(() -> accountService.getOwnedAccountBalance(accountId, accessToken))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
                    assertThat(exception.getStatus().value()).isEqualTo(404);
                });
    }
}
