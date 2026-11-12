package thitkho.corebankingservice.model;

import jakarta.persistence.*;
import thitkho.corebankingservice.model.enums.AccountStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_status_history")
public class AccountStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "old_status")
    private String oldStatus;
    @Column(name = "new_status", nullable = false)
    private String newStatus;
    private String reason;
    @Column(name = "changed_by")
    private String changedBy;
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
    protected AccountStatusHistory() {}

    public static AccountStatusHistory opened(UUID accountId, String changedBy) {
        var h = new AccountStatusHistory();
        h.accountId = accountId;
        h.newStatus = AccountStatus.ACTIVE.name();
        h.reason = "Account opened";
        h.changedBy = changedBy;
        h.changedAt = Instant.now();
        return h;
    }
}