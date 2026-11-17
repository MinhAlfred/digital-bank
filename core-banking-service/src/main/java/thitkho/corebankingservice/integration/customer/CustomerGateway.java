package thitkho.corebankingservice.integration.customer;

import thitkho.corebankingservice.common.dto.external.customer.CustomerInfo;

import java.util.Optional;
import java.util.UUID;

/** Customer lookup port. The HTTP adapter is implemented with the customer integration. */
public interface CustomerGateway {

    /**
     * Looks up a customer using the supplied credential.
     * @return customer information, or empty when the customer does not exist
     */
    Optional<CustomerInfo> findById(UUID customerId, String bearerToken);
}
