package thitkho.corebankingservice.service;

import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.common.dto.account.AccountResponse;
import thitkho.corebankingservice.common.dto.account.AccountBalanceResponse;
import thitkho.corebankingservice.model.enums.ProductType;

import java.util.UUID;

/** Account operations; callers supply a validated raw access token, never a client-selected owner. */
public interface AccountService {

    /**
     * Opens an account after checking currency support and customer eligibility.
     * @param bearerToken validated raw access token forwarded to the customer service
     * @throws BusinessException with UNSUPPORTED_CURRENCY if the currency is not supported,
     *         or CUSTOMER_NOT_ELIGIBLE if the customer is missing or inactive
     */
    AccountResponse openAccount(ProductType productType, String currency, String bearerToken);

    /**
     * Resolves the current customer and returns their customer account, including inactive accounts.
     * @throws BusinessException with ACCOUNT_NOT_FOUND if the account is absent or belongs to another customer
     */
    AccountResponse getOwnedAccount(UUID accountId, String bearerToken);

    /**
     * Returns the verified owner's balance as an exact minor-unit string with currency metadata.
     * @throws BusinessException with ACCOUNT_NOT_FOUND if the customer/account is absent,
     *         the account belongs to another customer, or it is a system account
     */
    AccountBalanceResponse getOwnedAccountBalance(UUID accountId, String bearerToken);
}
