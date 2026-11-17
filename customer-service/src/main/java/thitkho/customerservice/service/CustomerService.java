package thitkho.customerservice.service;

import thitkho.customerservice.common.dto.customer.CreateCustomerRequest;
import thitkho.customerservice.common.dto.customer.CustomerResponse;
import thitkho.customerservice.common.exception.BusinessException;

public interface CustomerService {
    /**
     * Creates the authenticated subject's own profile; subject must come from validated authentication.
     * @throws BusinessException with CUSTOMER_ALREADY_EXISTS or CUSTOMER_EMAIL_ALREADY_EXISTS on duplicates,
     *         or UNAUTHENTICATED when the supplied identity is invalid
     */
    CustomerResponse create(String authenticatedSubject, CreateCustomerRequest request);

    /**
     * Resolves the authenticated subject without treating it as a customer UUID.
     * @throws BusinessException with CUSTOMER_NOT_FOUND if no profile exists, or UNAUTHENTICATED for invalid identity
     */
    CustomerResponse getCurrent(String authenticatedSubject);
}
