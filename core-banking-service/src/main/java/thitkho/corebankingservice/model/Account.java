package thitkho.corebankingservice.model;

import jakarta.persistence.*;
import lombok.Data;
import thitkho.corebankingservice.model.enums.AccountStatus;
import thitkho.corebankingservice.model.enums.AccountType;
import thitkho.corebankingservice.model.enums.ProductType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Data
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, updatable = false)
    private String accountNumber;

    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, updatable = false)
    private ProductType productType;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private long balance;          // đơn vị nhỏ nhất, không dùng double

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Account() {}         // JPA

    public static Account openCustomerAccount(String accountNumber, UUID customerId,
                                              ProductType productType, String currency) {
        Account a = new Account();
        a.accountNumber = accountNumber;
        a.customerId = customerId;
        a.accountType = AccountType.CUSTOMER;
        a.productType = productType;
        a.currency = currency;
        a.status = AccountStatus.ACTIVE;
        a.balance = 0;
        a.createdAt = a.updatedAt = Instant.now();
        return a;
    }

    public boolean isActive() { return status == AccountStatus.ACTIVE; }
}
