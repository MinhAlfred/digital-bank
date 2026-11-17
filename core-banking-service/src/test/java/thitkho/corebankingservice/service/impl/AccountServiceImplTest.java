package thitkho.corebankingservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;
import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.generator.AccountNumberGenerator;
import thitkho.corebankingservice.integration.customer.CustomerGateway;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.AccountStatusHistory;
import thitkho.corebankingservice.model.enums.AccountStatus;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.repository.AccountRepository;
import thitkho.corebankingservice.repository.AccountStatusHistoryRepository;
import thitkho.corebankingservice.repository.CurrencyRepository;
import thitkho.corebankingservice.service.AccountService;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {
    private static final UUID CUSTOMER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String TOKEN = "test-credential";

    @Mock private AccountRepository accountRepository;
    @Mock private AccountStatusHistoryRepository accountStatusHistoryRepository;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private CustomerGateway customerGateway;
    @Mock private AccountNumberGenerator accountNumberGenerator;
    @Mock private PlatformTransactionManager platformTransactionManager;
    @Mock private TransactionStatus transactionStatus;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountServiceImpl(accountRepository, accountStatusHistoryRepository, currencyRepository, customerGateway,
                accountNumberGenerator, new TransactionTemplate(platformTransactionManager));
    }

    @Test
    void openAccount_whenEligible_createsActiveZeroBalanceAccountAndHistory() {
        given(currencyRepository.existsById("VND")).willReturn(true);
        given(customerGateway.findById(CUSTOMER_ID, TOKEN))
                .willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(accountNumberGenerator.next()).willReturn("880000000019");
        given(accountRepository.save(any(Account.class))).willAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(ACCOUNT_ID);
            return account;
        });

        Account account = accountService.openAccount(CUSTOMER_ID, ProductType.CHECKING, "VND", TOKEN);

        assertThat(account.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(account.getAccountNumber()).isEqualTo("880000000019");
        assertThat(account.getProductType()).isEqualTo(ProductType.CHECKING);
        assertThat(account.getCurrency()).isEqualTo("VND");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getBalance()).isZero();
        ArgumentCaptor<AccountStatusHistory> audit = ArgumentCaptor.forClass(AccountStatusHistory.class);
        then(accountStatusHistoryRepository).should().save(audit.capture());
        assertThat(audit.getValue()).extracting("accountId", "newStatus", "changedBy")
                .containsExactly(ACCOUNT_ID, "ACTIVE", CUSTOMER_ID.toString());
        var order = inOrder(customerGateway, platformTransactionManager, accountRepository, accountStatusHistoryRepository);
        order.verify(customerGateway).findById(CUSTOMER_ID, TOKEN);
        order.verify(platformTransactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(accountRepository).save(any(Account.class));
        order.verify(accountStatusHistoryRepository).save(any(AccountStatusHistory.class));
        order.verify(platformTransactionManager).commit(transactionStatus);
    }

    @Test
    void openAccount_whenCurrencyUnsupported_rejectsBeforeRemoteLookupOrWrite() {
        assertThatThrownBy(() -> accountService.openAccount(CUSTOMER_ID, ProductType.CHECKING, "XXX", TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("UNSUPPORTED_CURRENCY");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("Unsupported currency: XXX");
                });

        then(customerGateway).shouldHaveNoInteractions();
        then(accountRepository).shouldHaveNoInteractions();
        then(platformTransactionManager).shouldHaveNoInteractions();
    }

    @Test
    void openAccount_whenCustomerMissing_rejectsWithoutWriting() {
        given(currencyRepository.existsById("VND")).willReturn(true);
        given(customerGateway.findById(CUSTOMER_ID, TOKEN)).willReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.openAccount(CUSTOMER_ID, ProductType.CHECKING, "VND", TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("CUSTOMER_NOT_ELIGIBLE");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo("Customer not found");
                });

        then(accountRepository).shouldHaveNoInteractions();
        then(accountStatusHistoryRepository).shouldHaveNoInteractions();
        then(platformTransactionManager).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "CLOSED"})
    void openAccount_whenCustomerInactive_rejectsWithoutWriting(String status) {
        given(currencyRepository.existsById("VND")).willReturn(true);
        given(customerGateway.findById(CUSTOMER_ID, TOKEN))
                .willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, status)));

        assertThatThrownBy(() -> accountService.openAccount(CUSTOMER_ID, ProductType.CHECKING, "VND", TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("CUSTOMER_NOT_ELIGIBLE");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo("Customer is not active");
                });

        then(accountRepository).shouldHaveNoInteractions();
        then(accountStatusHistoryRepository).shouldHaveNoInteractions();
        then(platformTransactionManager).shouldHaveNoInteractions();
    }

    @Test
    void getOwnedAccount_whenOwnerMatches_returnsAccount() {
        Account account = Account.openCustomerAccount("880000000019", CUSTOMER_ID, ProductType.CHECKING, "VND");
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        assertThat(accountService.getOwnedAccount(ACCOUNT_ID, CUSTOMER_ID)).isSameAs(account);
    }

    @Test
    void getOwnedAccount_whenOwnerDiffers_hidesAccount() {
        UUID otherCustomerId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        Account account = Account.openCustomerAccount("880000000019", otherCustomerId, ProductType.CHECKING, "VND");
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        assertAccountNotFound();
    }

    @Test
    void getOwnedAccount_whenMissing_reportsNotFound() {
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        assertAccountNotFound();
    }

    private void assertAccountNotFound() {
        assertThatThrownBy(() -> accountService.getOwnedAccount(ACCOUNT_ID, CUSTOMER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("Account not found");
                });
    }
}
