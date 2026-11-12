package thitkho.corebankingservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "currencies")
public class Currency {
    @Id
    private String code;
    @Column(name = "minor_unit", nullable = false) private short minorUnit;
    protected Currency() {}
}
