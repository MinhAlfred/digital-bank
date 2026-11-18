package thitkho.corebankingservice.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import thitkho.corebankingservice.common.dto.account.AccountResponse;
import thitkho.corebankingservice.common.dto.account.AccountBalanceResponse;
import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;
import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.common.exception.ErrorCode;
import thitkho.corebankingservice.generator.AccountNumberGenerator;
import thitkho.corebankingservice.integration.customer.CustomerGateway;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.AccountStatusHistory;
import thitkho.corebankingservice.model.Currency;
import thitkho.corebankingservice.mapper.AccountMapper;
import thitkho.corebankingservice.model.enums.AccountType;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.repository.AccountRepository;
import thitkho.corebankingservice.repository.AccountStatusHistoryRepository;
import thitkho.corebankingservice.repository.CurrencyRepository;
import thitkho.corebankingservice.service.AccountService;

import java.util.UUID;
import java.util.Objects;
import java.util.function.BiFunction;

@Service
public class AccountServiceImpl implements AccountService {
    private final AccountRepository accountRepository;
    private final AccountStatusHistoryRepository accountStatusHistoryRepository;
    private final CurrencyRepository currencyRepository;
    private final CustomerGateway customerGateway;
    private final AccountNumberGenerator accountNumberGenerator;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readTransactionTemplate;
    private final AccountMapper accountMapper;

    public AccountServiceImpl(AccountRepository accountRepository, AccountStatusHistoryRepository accountStatusHistoryRepository,
                              CurrencyRepository currencyRepository, CustomerGateway customerGateway,
                              AccountNumberGenerator accountNumberGenerator, TransactionTemplate transactionTemplate,
                              AccountMapper accountMapper) {
        this.accountRepository = accountRepository;
        this.accountStatusHistoryRepository = accountStatusHistoryRepository;
        this.currencyRepository = currencyRepository;
        this.customerGateway = customerGateway;
        this.accountNumberGenerator = accountNumberGenerator;
        this.transactionTemplate = transactionTemplate;
        this.readTransactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionTemplate.getTransactionManager()));
        this.readTransactionTemplate.setReadOnly(true);
        this.accountMapper = accountMapper;
    }

    @Override
    public AccountResponse openAccount(ProductType productType,
                               String currency, String bearerToken) {
        if (!currencyRepository.existsById(currency)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_CURRENCY, "Unsupported currency: " + currency);
        }

        // Complete remote validation before starting the local write transaction.
        CustomerInfo customer = customerGateway.findCurrentCustomer(bearerToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_ELIGIBLE, "Customer not found"));
        if (!"ACTIVE".equals(customer.status())) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ELIGIBLE);
        }

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Currency currencyMetadata = getCurrency(currency);
            Account account = accountRepository.saveAndFlush(Account.openCustomerAccount(
                    accountNumberGenerator.next(), customer.id(), productType, currency));
            accountStatusHistoryRepository.save(AccountStatusHistory.opened(account.getId(), customer.id().toString()));
            return accountMapper.toResponse(account, currencyMetadata);
        }));
    }

    @Override
    public AccountResponse getOwnedAccount(UUID accountId, String bearerToken) {
        return readOwnedAccount(accountId, bearerToken, accountMapper::toResponse);
    }

    @Override
    public AccountBalanceResponse getOwnedAccountBalance(UUID accountId, String bearerToken) {
        return readOwnedAccount(accountId, bearerToken, accountMapper::toBalanceResponse);
    }

    private <T> T readOwnedAccount(UUID accountId, String bearerToken, BiFunction<Account, Currency, T> mapper) {
        CustomerInfo customer = customerGateway.findCurrentCustomer(bearerToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        return Objects.requireNonNull(readTransactionTemplate.execute(status -> {
            Account ownedAccount = accountRepository.findById(accountId)
                .filter(account -> account.getAccountType() == AccountType.CUSTOMER)
                .filter(account -> customer.id().equals(account.getCustomerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
            return mapper.apply(ownedAccount, getCurrency(ownedAccount.getCurrency()));
        }));
    }

    private Currency getCurrency(String currency) {
        return currencyRepository.findById(currency)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_CURRENCY, "Unsupported currency: " + currency));
    }
}
