package thitkho.customerservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import thitkho.customerservice.domain.Customer;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByAuthSubject(String authSubject);

    boolean existsByAuthSubject(String authSubject);

    boolean existsByEmailIgnoreCase(String email);
}
