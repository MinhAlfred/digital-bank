package thitkho.corebankingservice.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;
import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.common.exception.ErrorCode;
import thitkho.corebankingservice.generator.AccountNumberGenerator;
import thitkho.corebankingservice.integration.customer.CustomerGateway;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.AccountStatusHistory;
import thitkho.corebankingservice.model.enums.AccountType;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.repository.AccountRepository;
import thitkho.corebankingservice.repository.AccountStatusHistoryRepository;
import thitkho.corebankingservice.repository.CurrencyRepository;
import thitkho.corebankingservice.service.AccountService;

import java.util.UUID;

@Service
public class AccountServiceImpl implements AccountService {
    private final AccountRepository accountRepository;
    private final AccountStatusHistoryRepository accountStatusHistoryRepository;
    private final CurrencyRepository currencyRepository;
    private final CustomerGateway customerGateway;
    private final AccountNumberGenerator accountNumberGenerator;
    private final TransactionTemplate transactionTemplate;

    public AccountServiceImpl(AccountRepository accountRepository, AccountStatusHistoryRepository accountStatusHistoryRepository,
                              CurrencyRepository currencyRepository, CustomerGateway customerGateway,
                              AccountNumberGenerator accountNumberGenerator, TransactionTemplate transactionTemplate) {
        this.accountRepository = accountRepository;
        this.accountStatusHistoryRepository = accountStatusHistoryRepository;
        this.currencyRepository = currencyRepository;
        this.customerGateway = customerGateway;
        this.accountNumberGenerator = accountNumberGenerator;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Account openAccount(ProductType productType,
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

        return transactionTemplate.execute(status -> {
            Account account = accountRepository.save(Account.openCustomerAccount(
                    accountNumberGenerator.next(), customer.id(), productType, currency));
            accountStatusHistoryRepository.save(AccountStatusHistory.opened(account.getId(), customer.id().toString()));
            return account;
        });
    }

    @Override
    public Account getOwnedAccount(UUID accountId, String bearerToken) {
        CustomerInfo customer = customerGateway.findCurrentCustomer(bearerToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        return accountRepository.findById(accountId)
                .filter(account -> account.getAccountType() == AccountType.CUSTOMER)
                .filter(account -> customer.id().equals(account.getCustomerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }
}
