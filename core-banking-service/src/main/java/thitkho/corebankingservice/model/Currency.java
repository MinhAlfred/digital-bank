package thitkho.corebankingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Entity
@Table(name = "currencies")
@Getter
public class Currency {
    @Id
    private String code;
    @Column(name = "minor_unit", nullable = false) private short minorUnit;
    protected Currency() {}
}
