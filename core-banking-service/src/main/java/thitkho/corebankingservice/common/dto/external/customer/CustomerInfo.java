package thitkho.corebankingservice.common.dto.external.customer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerInfo(UUID id, String status) {}
