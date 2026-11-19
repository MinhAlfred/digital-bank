package thitkho.corebankingservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import thitkho.corebankingservice.support.PostgresTestConfiguration;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CoreBankingServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
