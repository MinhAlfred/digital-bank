package thitkho.corebankingservice.integration.customer;

import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;

import java.util.Optional;

/** Customer lookup using the authenticated caller's credential. */
public interface CustomerGateway {

    /**
     * Looks up the current customer using a validated raw access token (without the Bearer prefix).
     * @return customer information, or empty when the customer does not exist
     * @throws thitkho.corebankingservice.common.exception.BusinessException when authentication,
     *         availability, timeout or response validation fails
     */
    Optional<CustomerInfo> findCurrentCustomer(String bearerToken);
}
