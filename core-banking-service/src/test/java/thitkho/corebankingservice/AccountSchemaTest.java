package thitkho.corebankingservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import thitkho.corebankingservice.config.JpaAuditingConfiguration;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.enums.AccountStatus;
import thitkho.corebankingservice.model.enums.AccountType;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.repository.AccountRepository;
import thitkho.corebankingservice.support.PostgresTestConfiguration;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfiguration.class, PostgresTestConfiguration.class})
class AccountSchemaTest {
    @Autowired private AccountRepository accountRepository;
    @Autowired private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private final UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void savesAndLoadsCustomerAccount() {
        Account account = accountRepository.saveAndFlush(Account.openCustomerAccount(
                "ACC0000001", customerId, ProductType.CHECKING, "VND"));

        assertThat(accountRepository.findById(account.getId())).isPresent();
        assertThat(account.getBalance()).isZero();
        assertThat(account.getAccountType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getVersion()).isZero();
        assertThat(account.getClosedAt()).isNull();
        assertThat(account.getCreatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isEqualTo(account.getCreatedAt());
    }

    @Test
    void seededSystemAccountExists() {
        assertThat(accountRepository.findByAccountNumber("SYS-CASH-VND")).hasValueSatisfying(account -> {
            assertThat(account.getAccountType()).isEqualTo(AccountType.SYSTEM);
            assertThat(account.getCustomerId()).isNull();
        });
    }

    @Test
    void customerAccountWithoutOwnerIsRejectedByDatabase() {
        assertConstraint(() -> jdbcTemplate.update("""
                insert into accounts (account_number, account_type, currency)
                values ('INVALID-OWNER', 'CUSTOMER', 'VND')
                """), "ck_owner");
    }

    @Test
    void systemAccountWithOwnerIsRejectedByDatabase() {
        assertConstraint(() -> jdbcTemplate.update("""
                insert into accounts (account_number, customer_id, account_type, currency)
                values ('INVALID-SYSTEM', ?, 'SYSTEM', 'VND')
                """, customerId), "ck_owner");
    }

    @Test
    void negativeBalanceOnCustomerAccountIsRejectedByDatabase() {
        Account account = accountRepository.saveAndFlush(Account.openCustomerAccount(
                "NEGATIVE-CUSTOMER", customerId, ProductType.CHECKING, "VND"));

        assertConstraint(() -> jdbcTemplate.update("update accounts set balance = -1 where id = ?", account.getId()),
                "ck_balance_non_negative");
    }

    @Test
    void systemAccount_canHoldNegativeBalance() {
        jdbcTemplate.update("""
                insert into accounts (account_number, account_type, currency, balance)
                values ('SYSTEM-NEGATIVE', 'SYSTEM', 'VND', -100)
                """);

        assertThat(jdbcTemplate.queryForObject("select balance from accounts where account_number = 'SYSTEM-NEGATIVE'",
                Long.class)).isEqualTo(-100L);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,false", "FROZEN,false", "CLOSED,true"})
    void accountStatusWithMatchingClosedTimestamp_isAccepted(String status, boolean hasClosedAt) {
        Timestamp closedAt = hasClosedAt ? Timestamp.from(Instant.parse("2026-11-18T03:00:00Z")) : null;

        int inserted = jdbcTemplate.update("""
                insert into accounts (account_number, customer_id, currency, status, closed_at)
                values ('VALID-STATUS', ?, 'VND', ?, ?)
                """, customerId, status, closedAt);

        assertThat(inserted).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,true", "FROZEN,true", "CLOSED,false"})
    void accountStatusWithMismatchingClosedTimestamp_isRejected(String status, boolean hasClosedAt) {
        Timestamp closedAt = hasClosedAt ? Timestamp.from(Instant.parse("2026-11-18T03:00:00Z")) : null;

        assertConstraint(() -> jdbcTemplate.update("""
                insert into accounts (account_number, customer_id, currency, status, closed_at)
                values ('INVALID-STATUS', ?, 'VND', ?, ?)
                """, customerId, status, closedAt), "ck_closed");
    }

    @Test
    void unsupportedCurrency_isRejectedByDatabase() {
        assertConstraint(() -> jdbcTemplate.update("""
                insert into accounts (account_number, customer_id, currency)
                values ('INVALID-CURRENCY', ?, 'XXX')
                """, customerId), "accounts_currency_fkey");
    }

    @Test
    void duplicateAccountNumber_isRejectedByDatabase() {
        accountRepository.saveAndFlush(Account.openCustomerAccount("DUPLICATE-NUMBER", customerId, ProductType.CHECKING, "VND"));

        assertConstraint(() -> jdbcTemplate.update("""
                insert into accounts (account_number, customer_id, currency)
                values ('DUPLICATE-NUMBER', ?, 'VND')
                """, customerId), "accounts_account_number_key");
    }

    @ParameterizedTest
    @CsvSource({"VND,0", "USD,2"})
    void seededCurrency_hasExpectedMinorUnit(String currency, int minorUnit) {
        assertThat(jdbcTemplate.queryForObject("select minor_unit from currencies where code = ?", Integer.class, currency))
                .isEqualTo(minorUnit);
    }

    private void assertConstraint(Runnable operation, String constraint) {
        assertThatThrownBy(operation::run).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }
}
