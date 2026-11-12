package thitkho.corebankingservice;

import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.repository.AccountRepository;

import java.util.UUID;


@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AccountSchemaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    AccountRepository repo;

    @Test
    void savesAndLoadsCustomerAccount() {
        Account a = repo.saveAndFlush(Account.openCustomerAccount(
                "ACC0000001", UUID.randomUUID(), ProductType.CHECKING, "VND"));
        AssertionsForClassTypes.assertThat(repo.findById(a.getId())).isPresent();
        AssertionsForClassTypes.assertThat(a.getBalance()).isZero();
    }

    @Test
    void seededSystemAccountExists() {
        AssertionsForClassTypes.assertThat(repo.findByAccountNumber("SYS-CASH-VND")).isPresent();
    }

    @Test
    void customerAccountWithoutOwnerIsRejectedByDatabase() {
        // dùng JdbcTemplate hoặc EntityManager native insert với customer_id NULL
        // kỳ vọng DataIntegrityViolationException do ck_owner
    }

    @Test
    void negativeBalanceOnCustomerAccountIsRejectedByDatabase() {
        // native UPDATE accounts SET balance = -1 WHERE account_type = 'CUSTOMER'
        // kỳ vọng DataIntegrityViolationException do ck_balance_non_negative
    }
}