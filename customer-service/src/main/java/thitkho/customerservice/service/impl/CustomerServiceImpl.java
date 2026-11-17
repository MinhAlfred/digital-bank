package thitkho.customerservice.service.impl;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import thitkho.customerservice.common.dto.customer.CreateCustomerRequest;
import thitkho.customerservice.common.dto.customer.CustomerResponse;
import thitkho.customerservice.common.exception.BusinessException;
import thitkho.customerservice.common.exception.ErrorCode;
import thitkho.customerservice.domain.Customer;
import thitkho.customerservice.mapper.CustomerMapper;
import thitkho.customerservice.repository.CustomerRepository;
import thitkho.customerservice.service.CustomerService;

import java.time.Clock;
import java.util.Objects;

@Service
public class CustomerServiceImpl implements CustomerService {
    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CustomerServiceImpl(CustomerRepository customerRepository, CustomerMapper customerMapper,
                               TransactionTemplate transactionTemplate, Clock clock) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public CustomerResponse create(String authenticatedSubject, CreateCustomerRequest request) {
        validateSubject(authenticatedSubject);
        rejectDuplicates(authenticatedSubject, request.email());
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> customerMapper.toResponse(
                    customerRepository.saveAndFlush(Customer.create(authenticatedSubject, request.fullName(),
                            request.email(), request.phone(), clock.instant())))));
        } catch (DataIntegrityViolationException exception) {
            // The failed write transaction has rolled back before checking concurrent winners.
            rejectDuplicates(authenticatedSubject, request.email());
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getCurrent(String authenticatedSubject) {
        validateSubject(authenticatedSubject);
        return customerRepository.findByAuthSubject(authenticatedSubject)
                .map(customerMapper::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    private void rejectDuplicates(String authenticatedSubject, String email) {
        if (customerRepository.existsByAuthSubject(authenticatedSubject)) {
            throw new BusinessException(ErrorCode.CUSTOMER_ALREADY_EXISTS);
        }
        if (customerRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);
        }
    }

    private void validateSubject(String authenticatedSubject) {
        if (!StringUtils.hasText(authenticatedSubject) || authenticatedSubject.length() > 100) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
