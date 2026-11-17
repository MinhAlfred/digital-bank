package thitkho.customerservice.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import thitkho.customerservice.common.dto.customer.CreateCustomerRequest;
import thitkho.customerservice.common.dto.customer.CustomerResponse;
import thitkho.customerservice.common.exception.BusinessException;
import thitkho.customerservice.common.exception.ErrorCode;
import thitkho.customerservice.domain.Customer;
import thitkho.customerservice.domain.CustomerStatus;
import thitkho.customerservice.mapper.CustomerMapper;
import thitkho.customerservice.repository.CustomerRepository;
import thitkho.customerservice.service.CustomerService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {
    private static final String SUBJECT = "provider|subject-a";
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final CreateCustomerRequest REQUEST = new CreateCustomerRequest(
            "  Nguyen Van A  ", "  CUSTOMER@EXAMPLE.COM  ", "   ");

    @Mock private CustomerRepository customerRepository;
    @Mock private PlatformTransactionManager platformTransactionManager;
    @Mock private TransactionStatus transactionStatus;
    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerServiceImpl(customerRepository, Mappers.getMapper(CustomerMapper.class),
                new TransactionTemplate(platformTransactionManager), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_validRequest_usesVerifiedSubjectAndNormalizedFields() {
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(customerRepository.saveAndFlush(any(Customer.class))).willAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            assertThat(customer.getAuthSubject()).isEqualTo(SUBJECT);
            return customer;
        });

        CustomerResponse response = customerService.create(SUBJECT, REQUEST);

        assertThat(response.fullName()).isEqualTo("Nguyen Van A");
        assertThat(response.email()).isEqualTo("customer@example.com");
        assertThat(response.phone()).isNull();
        assertThat(response.status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        then(platformTransactionManager).should().commit(transactionStatus);
    }

    @Test
    void create_subjectAlreadyExists_prioritizesSubjectConflict() {
        given(customerRepository.existsByAuthSubject(SUBJECT)).willReturn(true);

        assertBusinessCode(() -> customerService.create(SUBJECT, REQUEST), ErrorCode.CUSTOMER_ALREADY_EXISTS);

        then(customerRepository).should().existsByAuthSubject(SUBJECT);
        then(customerRepository).shouldHaveNoMoreInteractions();
        then(platformTransactionManager).shouldHaveNoInteractions();
    }

    @Test
    void create_emailAlreadyExists_rejectsBeforeWrite() {
        given(customerRepository.existsByEmailIgnoreCase("customer@example.com")).willReturn(true);

        assertBusinessCode(() -> customerService.create(SUBJECT, REQUEST), ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);

        then(platformTransactionManager).shouldHaveNoInteractions();
    }

    @Test
    void create_concurrentSubjectWinner_checksAfterRollback() {
        given(customerRepository.existsByAuthSubject(SUBJECT)).willReturn(false, true);
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(customerRepository.saveAndFlush(any(Customer.class))).willThrow(new DataIntegrityViolationException("conflict"));

        assertBusinessCode(() -> customerService.create(SUBJECT, REQUEST), ErrorCode.CUSTOMER_ALREADY_EXISTS);

        var order = inOrder(customerRepository, platformTransactionManager);
        order.verify(customerRepository).existsByAuthSubject(SUBJECT);
        order.verify(customerRepository).existsByEmailIgnoreCase("customer@example.com");
        order.verify(platformTransactionManager).getTransaction(any(TransactionDefinition.class));
        order.verify(customerRepository).saveAndFlush(any(Customer.class));
        order.verify(platformTransactionManager).rollback(transactionStatus);
        order.verify(customerRepository).existsByAuthSubject(SUBJECT);
    }

    @Test
    void create_concurrentEmailWinner_reportsEmailConflict() {
        given(customerRepository.existsByEmailIgnoreCase("customer@example.com")).willReturn(false, true);
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        given(customerRepository.saveAndFlush(any(Customer.class))).willThrow(new DataIntegrityViolationException("conflict"));

        assertBusinessCode(() -> customerService.create(SUBJECT, REQUEST), ErrorCode.CUSTOMER_EMAIL_ALREADY_EXISTS);

        then(platformTransactionManager).should().rollback(transactionStatus);
    }

    @Test
    void create_unrelatedConstraintFailure_preservesTechnicalException() {
        given(platformTransactionManager.getTransaction(any(TransactionDefinition.class))).willReturn(transactionStatus);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        given(customerRepository.saveAndFlush(any(Customer.class))).willThrow(failure);

        assertThatThrownBy(() -> customerService.create(SUBJECT, REQUEST)).isSameAs(failure);
    }

    @ParameterizedTest
    @EnumSource(CustomerStatus.class)
    void getCurrent_eachStatus_returnsOwnProfile(CustomerStatus status) {
        Customer customer = Customer.create(SUBJECT, "A", "a@example.com", null, NOW);
        ReflectionTestUtils.setField(customer, "status", status);
        given(customerRepository.findByAuthSubject(SUBJECT)).willReturn(Optional.of(customer));

        assertThat(customerService.getCurrent(SUBJECT).status()).isEqualTo(status);
    }

    @Test
    void getCurrent_missingProfile_reportsNotFound() {
        given(customerRepository.findByAuthSubject(SUBJECT)).willReturn(Optional.empty());

        assertBusinessCode(() -> customerService.getCurrent(SUBJECT), ErrorCode.CUSTOMER_NOT_FOUND);
    }

    @Test
    void getCurrent_invalidSubject_doesNotQueryDatabase() {
        assertBusinessCode(() -> customerService.getCurrent(" "), ErrorCode.UNAUTHENTICATED);
        then(customerRepository).shouldHaveNoInteractions();
    }

    private void assertBusinessCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
