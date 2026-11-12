package thitkho.corebankingservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import thitkho.corebankingservice.model.AccountStatusHistory;

public interface AccountStatusHistoryRepository extends JpaRepository<AccountStatusHistory, Long> {

}