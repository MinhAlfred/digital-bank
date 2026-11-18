package thitkho.corebankingservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;
import thitkho.corebankingservice.common.dto.account.AccountResponse;
import thitkho.corebankingservice.mapper.AccountMapper;
import thitkho.corebankingservice.model.Currency;
import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.common.exception.ErrorCode;
import thitkho.corebankingservice.generator.AccountNumberGenerator;
import thitkho.corebankingservice.integration.customer.CustomerGateway;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.AccountStatusHistory;
import thitkho.corebankingservice.model.enums.AccountStatus;
import thitkho.corebankingservice.model.enums.AccountType;
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
    private final AccountMapper accountMapper = Mappers.getMapper(AccountMapper.class);

    @BeforeEach
    void setUp() {
        accountService = new AccountServiceImpl(accountRepository, accountStatusHistoryRepository, currencyRepository, customerGateway,
                accountNumberGenerator, new TransactionTemplate(platformTransactionManager), accountMapper);
    }

    @Test
    void openAccount_whenEligible_createsActiveZeroBalanceAccountAndHistory() {
        given(currencyRepository.findById("VND")).willReturn(Optional.of(currency("VND", 0)));
        given(currencyRepository.existsById("VND")).willReturn(true);
        given(customerGateway.findCurrentCustomer(TOKEN))
                .willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(accountNumberGenerator.next()).willReturn("880000000019");
        given(accountRepository.saveAndFlush(any(Account.class))).willAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(ACCOUNT_ID);
            return account;
        });

        AccountResponse account = accountService.openAccount(ProductType.CHECKING, "VND", TOKEN);

        assertThat(account.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(account.accountNumber()).isEqualTo("880000000019");
        assertThat(account.productType()).isEqualTo(ProductType.CHECKING);
        assertThat(account.currency()).isEqualTo("VND");
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.balanceMinor()).isEqualTo("0");
        assertThat(account.minorUnit()).isZero();
        ArgumentCaptor<AccountStatusHistory> audit = ArgumentCaptor.forClass(AccountStatusHistory.class);
        then(accountStatusHistoryRepository).should().save(audit.capture());
        assertThat(audit.getValue()).extracting("accountId", "newStatus", "changedBy")
                .containsExactly(ACCOUNT_ID, "ACTIVE", CUSTOMER_ID.toString());
        var order = inOrder(customerGateway, platformTransactionManager, accountRepository, accountStatusHistoryRepository);
        order.verify(customerGateway).findCurrentCustomer(TOKEN);
        order.verify(platformTransactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(accountRepository).saveAndFlush(any(Account.class));
        order.verify(accountStatusHistoryRepository).save(any(AccountStatusHistory.class));
        order.verify(platformTransactionManager).commit(transactionStatus);
    }

    @Test
    void openAccount_whenCurrencyUnsupported_rejectsBeforeRemoteLookupOrWrite() {
        assertThatThrownBy(() -> accountService.openAccount(ProductType.CHECKING, "XXX", TOKEN))
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
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.openAccount(ProductType.CHECKING, "VND", TOKEN))
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
        given(customerGateway.findCurrentCustomer(TOKEN))
                .willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, status)));

        assertThatThrownBy(() -> accountService.openAccount(ProductType.CHECKING, "VND", TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("CUSTOMER_NOT_ELIGIBLE");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo("Customer is not active");
                });

        then(accountRepository).shouldHaveNoInteractions();
        then(accountStatusHistoryRepository).shouldHaveNoInteractions();
        then(platformTransactionManager).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SUSPENDED", "CLOSED"})
    void getOwnedAccount_whenOwnerMatches_returnsAccountRegardlessOfCustomerStatus(String status) {
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        Currency currency = currency("VND", 0);
        given(currencyRepository.findById("VND")).willReturn(Optional.of(currency));
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, status)));
        Account account = Account.openCustomerAccount("880000000019", CUSTOMER_ID, ProductType.CHECKING, "VND");
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        assertThat(accountService.getOwnedAccount(ACCOUNT_ID, TOKEN)).isEqualTo(accountMapper.toResponse(account, currency));
        var order = inOrder(customerGateway, platformTransactionManager, accountRepository);
        order.verify(customerGateway).findCurrentCustomer(TOKEN);
        order.verify(platformTransactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(accountRepository).findById(ACCOUNT_ID);
        order.verify(platformTransactionManager).commit(transactionStatus);
    }

    @Test
    void getOwnedAccount_whenOwnerDiffers_hidesAccount() {
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        UUID otherCustomerId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        Account account = Account.openCustomerAccount("880000000019", otherCustomerId, ProductType.CHECKING, "VND");
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        assertAccountNotFound();
    }

    @Test
    void getOwnedAccount_whenMissing_reportsNotFound() {
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.empty());

        assertAccountNotFound();
    }

    private void assertAccountNotFound() {
        assertThatThrownBy(() -> accountService.getOwnedAccount(ACCOUNT_ID, TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("Account not found");
                });
    }

    @Test
    void getOwnedAccount_whenCustomerMissing_hidesAccountWithoutReadingRepository() {
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.empty());

        assertAccountNotFound();

        then(accountRepository).shouldHaveNoInteractions();
    }

    @Test
    void getOwnedAccount_whenSystemAccount_hidesAccountEvenIfOwnerMatches() {
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        Account account = Account.openCustomerAccount("SYS-CASH-VND", CUSTOMER_ID, ProductType.CHECKING, "VND");
        account.setAccountType(AccountType.SYSTEM);
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        assertAccountNotFound();
    }

    @ParameterizedTest
    @EnumSource(AccountStatus.class)
    void getOwnedAccount_eachAccountStatus_remainsReadable(AccountStatus status) {
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        Currency currency = currency("VND", 0);
        given(currencyRepository.findById("VND")).willReturn(Optional.of(currency));
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        Account account = Account.openCustomerAccount("880000000019", CUSTOMER_ID, ProductType.CHECKING, "VND");
        account.setStatus(status);
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        assertThat(accountService.getOwnedAccount(ACCOUNT_ID, TOKEN)).isEqualTo(accountMapper.toResponse(account, currency));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UPSTREAM_TIMEOUT", "CUSTOMER_SERVICE_UNAVAILABLE",
            "INVALID_CUSTOMER_RESPONSE", "UNAUTHENTICATED", "ACCESS_DENIED"})
    void openAccount_whenLookupFails_doesNotStartTransactionOrWrite(ErrorCode errorCode) {
        given(currencyRepository.existsById("VND")).willReturn(true);
        BusinessException failure = new BusinessException(errorCode);
        given(customerGateway.findCurrentCustomer(TOKEN)).willThrow(failure);

        assertThatThrownBy(() -> accountService.openAccount(ProductType.CHECKING, "VND", TOKEN)).isSameAs(failure);

        then(platformTransactionManager).shouldHaveNoInteractions();
        then(accountRepository).shouldHaveNoInteractions();
        then(accountStatusHistoryRepository).shouldHaveNoInteractions();
        then(accountNumberGenerator).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"UPSTREAM_TIMEOUT", "CUSTOMER_SERVICE_UNAVAILABLE",
            "INVALID_CUSTOMER_RESPONSE", "UNAUTHENTICATED", "ACCESS_DENIED"})
    void getOwnedAccount_whenLookupFails_doesNotReadAccount(ErrorCode errorCode) {
        BusinessException failure = new BusinessException(errorCode);
        given(customerGateway.findCurrentCustomer(TOKEN)).willThrow(failure);

        assertThatThrownBy(() -> accountService.getOwnedAccount(ACCOUNT_ID, TOKEN)).isSameAs(failure);

        then(accountRepository).shouldHaveNoInteractions();
    }

    @Test
    void openAccount_whenHistoryWriteFails_rollsBackLocalTransaction() {
        given(currencyRepository.findById("VND")).willReturn(Optional.of(currency("VND", 0)));
        given(currencyRepository.existsById("VND")).willReturn(true);
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(accountNumberGenerator.next()).willReturn("880000000019");
        given(accountRepository.saveAndFlush(any(Account.class))).willAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(ACCOUNT_ID);
            return account;
        });
        RuntimeException failure = new org.springframework.dao.DataIntegrityViolationException("history write failed");
        given(accountStatusHistoryRepository.save(any(AccountStatusHistory.class))).willThrow(failure);

        assertThatThrownBy(() -> accountService.openAccount(ProductType.CHECKING, "VND", TOKEN)).isSameAs(failure);

        then(platformTransactionManager).should().rollback(transactionStatus);
    }

    @Test
    void getOwnedAccountBalance_largeUsdBalance_keepsExactMinorUnitsWithinReadOnlyTransaction() {
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(currencyRepository.findById("USD")).willReturn(Optional.of(currency("USD", 2)));
        Account account = Account.openCustomerAccount("880000000019", CUSTOMER_ID, ProductType.SAVINGS, "USD");
        account.setId(ACCOUNT_ID);
        account.setBalance(Long.MAX_VALUE);
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        var response = accountService.getOwnedAccountBalance(ACCOUNT_ID, TOKEN);

        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(response.balanceMinor()).isEqualTo("9223372036854775807");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.minorUnit()).isEqualTo(2);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        then(platformTransactionManager).should().getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        then(platformTransactionManager).should().commit(transactionStatus);
    }

    @Test
    void getOwnedAccountBalance_otherOwner_hidesBalance() {
        given(customerGateway.findCurrentCustomer(TOKEN)).willReturn(Optional.of(new CustomerInfo(CUSTOMER_ID, "ACTIVE")));
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        Account account = Account.openCustomerAccount("880000000019", UUID.randomUUID(), ProductType.CHECKING, "VND");
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.getOwnedAccountBalance(ACCOUNT_ID, TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo("ACCOUNT_NOT_FOUND"));
        then(currencyRepository).shouldHaveNoInteractions();
    }

    @Test
    void getOwnedAccountBalance_customerUnavailable_doesNotReadAccountOrStartTransaction() {
        BusinessException failure = new BusinessException(ErrorCode.CUSTOMER_SERVICE_UNAVAILABLE);
        given(customerGateway.findCurrentCustomer(TOKEN)).willThrow(failure);

        assertThatThrownBy(() -> accountService.getOwnedAccountBalance(ACCOUNT_ID, TOKEN)).isSameAs(failure);

        then(platformTransactionManager).shouldHaveNoInteractions();
        then(accountRepository).shouldHaveNoInteractions();
    }

    private Currency currency(String code, int minorUnit) {
        Currency currency = BeanUtils.instantiateClass(Currency.class);
        ReflectionTestUtils.setField(currency, "code", code);
        ReflectionTestUtils.setField(currency, "minorUnit", (short) minorUnit);
        return currency;
    }
}
