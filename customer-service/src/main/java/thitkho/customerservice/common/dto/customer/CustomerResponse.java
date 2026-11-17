package thitkho.customerservice.common.dto.customer;

import thitkho.customerservice.domain.CustomerStatus;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(UUID id, String fullName, String email, String phone,
                               CustomerStatus status, Instant createdAt, Instant updatedAt) {
}
