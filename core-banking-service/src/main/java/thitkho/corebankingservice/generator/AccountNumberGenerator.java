package thitkho.corebankingservice.generator;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

@Component
public class AccountNumberGenerator {
    private final EntityManager em;
    public AccountNumberGenerator(EntityManager em) { this.em = em; }

    /** Phải gọi trong transaction. */
    public String next() {
        long seq = ((Number) em.createNativeQuery("select nextval('account_number_seq')")
                .getSingleResult()).longValue();
        String payload = "88" + String.format("%09d", seq);
        return payload + luhnCheckDigit(payload);
    }

    static int luhnCheckDigit(String payload) {
        int sum = 0;
        boolean dbl = true;
        for (int i = payload.length() - 1; i >= 0; i--) {
            int d = payload.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }
}
