package thitkho.corebankingservice.mapper;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.Currency;
import thitkho.corebankingservice.model.enums.AccountStatus;
import thitkho.corebankingservice.model.enums.AccountType;
import thitkho.corebankingservice.model.enums.ProductType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMapperTest {
    private final AccountMapper accountMapper = Mappers.getMapper(AccountMapper.class);

    @ParameterizedTest
    @CsvSource({"VND,0,0", "USD,2,12345", "VND,0,9007199254740993", "USD,2,9223372036854775807"})
    void mapsAccountAndBalance_preservesFieldsAndExactMinorUnits(String code, int minorUnit, long balance) {
        UUID accountId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID customerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        Instant createdAt = Instant.parse("2026-11-18T03:00:00Z");
        Instant updatedAt = createdAt.plusSeconds(60);
        Account account = Account.openCustomerAccount("880000000019", customerId, ProductType.SAVINGS, code);
        account.setId(accountId);
        account.setStatus(AccountStatus.CLOSED);
        account.setClosedAt(updatedAt);
        account.setBalance(balance);
        ReflectionTestUtils.setField(account, "createdAt", createdAt);
        ReflectionTestUtils.setField(account, "updatedAt", updatedAt);
        Currency currency = BeanUtils.instantiateClass(Currency.class);
        ReflectionTestUtils.setField(currency, "code", code);
        ReflectionTestUtils.setField(currency, "minorUnit", (short) minorUnit);

        var details = accountMapper.toResponse(account, currency);
        var amount = accountMapper.toBalanceResponse(account, currency);

        assertThat(details.id()).isEqualTo(accountId);
        assertThat(details.accountNumber()).isEqualTo("880000000019");
        assertThat(details.customerId()).isEqualTo(customerId);
        assertThat(details.accountType()).isEqualTo(AccountType.CUSTOMER);
        assertThat(details.productType()).isEqualTo(ProductType.SAVINGS);
        assertThat(details.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(details.currency()).isEqualTo(code);
        assertThat(details.balanceMinor()).isEqualTo(Long.toString(balance));
        assertThat(details.minorUnit()).isEqualTo(minorUnit);
        assertThat(details.createdAt()).isEqualTo(createdAt);
        assertThat(details.updatedAt()).isEqualTo(updatedAt);
        assertThat(details.closedAt()).isEqualTo(updatedAt);
        assertThat(amount.accountId()).isEqualTo(accountId);
        assertThat(amount.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(amount.currency()).isEqualTo(code);
        assertThat(amount.balanceMinor()).isEqualTo(Long.toString(balance));
        assertThat(amount.minorUnit()).isEqualTo(minorUnit);
    }
}
