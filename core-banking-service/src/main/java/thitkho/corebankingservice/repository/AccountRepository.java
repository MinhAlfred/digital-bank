package thitkho.corebankingservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import thitkho.corebankingservice.model.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByAccountNumber(String accountNumber);
    List<Account> findByCustomerId(UUID customerId);
}
