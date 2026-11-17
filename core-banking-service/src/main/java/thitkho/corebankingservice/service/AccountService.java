package thitkho.corebankingservice.service;

import thitkho.corebankingservice.common.exception.BusinessException;
import thitkho.corebankingservice.model.Account;
import thitkho.corebankingservice.model.enums.ProductType;

import java.util.UUID;

/** Account operations; callers must supply a customer identity verified against the authenticated user. */
public interface AccountService {

    /**
     * Opens an account after checking currency support and customer eligibility.
     * @param bearerToken credential forwarded to the customer service
     * @throws BusinessException with UNSUPPORTED_CURRENCY if the currency is not supported,
     *         or CUSTOMER_NOT_ELIGIBLE if the customer is missing or inactive
     */
    Account openAccount(UUID customerId, ProductType productType, String currency, String bearerToken);

    /**
     * Returns an account belonging to the verified customer.
     * @throws BusinessException with ACCOUNT_NOT_FOUND if the account is absent or belongs to another customer
     */
    Account getOwnedAccount(UUID accountId, UUID customerId);
}
