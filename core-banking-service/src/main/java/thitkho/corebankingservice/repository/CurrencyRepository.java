package thitkho.corebankingservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import thitkho.corebankingservice.model.Currency;
@Repository
public interface CurrencyRepository extends JpaRepository<Currency, String> {

}