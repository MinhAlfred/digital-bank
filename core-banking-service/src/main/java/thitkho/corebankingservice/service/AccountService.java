package thitkho.corebankingservice.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import thitkho.corebankingservice.dto.external.CustomerInfo;
import thitkho.corebankingservice.generator.AccountNumberGenerator;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.AccountStatusHistory;
import thitkho.corebankingservice.model.enums.ProductType;
import thitkho.corebankingservice.repository.AccountRepository;
import thitkho.corebankingservice.repository.AccountStatusHistoryRepository;
import thitkho.corebankingservice.repository.CurrencyRepository;

import java.util.UUID;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final AccountStatusHistoryRepository history;
    private final CurrencyRepository currencies;
    private final CustomerGateway customers;
    private final AccountNumberGenerator numberGenerator;
    private final TransactionTemplate tx;

    // constructor injection (bỏ qua cho gọn)

    /** KHÔNG @Transactional: gọi HTTP xong mới mở transaction. */
    public Account openAccount(UUID customerId, ProductType productType,
                               String currency, String bearerToken) {
        if (!currencies.existsById(currency)) throw new UnsupportedCurrencyException(currency);

        CustomerInfo customer = customers.findById(customerId, bearerToken)
                .orElseThrow(() -> new CustomerNotEligibleException("Customer not found"));
        if (!"ACTIVE".equals(customer.status()))
            throw new CustomerNotEligibleException("Customer is not active");

        return tx.execute(status -> {
            Account a = accounts.save(Account.openCustomerAccount(
                    numberGenerator.next(), customerId, productType, currency));
            history.save(AccountStatusHistory.opened(a.getId(), customerId.toString()));
            return a;
        });
    }

    @Transactional(readOnly = true)
    public Account getOwnedAccount(UUID accountId, UUID customerId) {
        return accounts.findById(accountId)
                .filter(a -> customerId.equals(a.getCustomerId()))
                .orElseThrow(AccountNotFoundException::new);
    }
}