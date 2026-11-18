package thitkho.customerservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import thitkho.customerservice.common.persistence.AuditableEntity;
import lombok.Getter;

import java.util.UUID;

@Entity
@Table(name = "customers")
@Getter
public class Customer extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "auth_subject", nullable = false, unique = true, length = 100, updatable = false)
    private String authSubject;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status;

    @Version
    private long version;

    protected Customer() {
    }

    public static Customer create(String authSubject, String fullName, String email, String phone) {
        Customer customer = new Customer();
        customer.authSubject = authSubject;
        customer.fullName = fullName;
        customer.email = email;
        customer.phone = phone;
        customer.status = CustomerStatus.ACTIVE;
        return customer;
    }
}
