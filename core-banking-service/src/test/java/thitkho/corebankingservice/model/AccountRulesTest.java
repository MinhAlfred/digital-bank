package thitkho.corebankingservice.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import thitkho.corebankingservice.model.enums.AccountStatus;
import thitkho.corebankingservice.model.enums.AccountType;
import thitkho.corebankingservice.model.enums.ProductType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRulesTest {
    @ParameterizedTest
    @CsvSource({"CHECKING,VND", "CHECKING,USD", "SAVINGS,VND", "SAVINGS,USD"})
    void openCustomerAccount_initializesOwnerProductCurrencyAndZeroBalance(ProductType productType, String currency) {
        UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000001");

        Account account = Account.openCustomerAccount("880000000019", customerId, productType, currency);

        assertThat(account.getCustomerId()).isEqualTo(customerId);
        assertThat(account.getAccountNumber()).isEqualTo("880000000019");
        assertThat(account.getAccountType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.getProductType()).isEqualTo(productType);
        assertThat(account.getCurrency()).isEqualTo(currency);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.isActive()).isTrue();
        assertThat(account.getBalance()).isZero();
        assertThat(account.getClosedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,true", "FROZEN,false", "CLOSED,false"})
    void isActive_identifiesOnlyAccountsEligibleForFutureTransactions(AccountStatus status, boolean expectedActive) {
        Account account = Account.openCustomerAccount("880000000019",
                UUID.fromString("10000000-0000-0000-0000-000000000001"), ProductType.CHECKING, "VND");
        ReflectionTestUtils.setField(account, "status", status);
        if (status == AccountStatus.CLOSED) {
            ReflectionTestUtils.setField(account, "closedAt", Instant.parse("2026-11-18T03:00:00Z"));
        }

        assertThat(account.isActive()).isEqualTo(expectedActive);
    }
}
