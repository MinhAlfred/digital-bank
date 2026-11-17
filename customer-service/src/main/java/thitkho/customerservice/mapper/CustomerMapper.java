package thitkho.customerservice.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import thitkho.customerservice.common.dto.customer.CustomerResponse;
import thitkho.customerservice.domain.Customer;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerMapper {
    CustomerResponse toResponse(Customer customer);
}
