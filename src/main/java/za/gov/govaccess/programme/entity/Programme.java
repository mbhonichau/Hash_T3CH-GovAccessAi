package za.gov.govaccess.programme.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "programme")
public class Programme {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean demoData;

    protected Programme() {}

    public Programme(UUID id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    public static Programme demo(UUID id, String code, String name) {
        var programme = new Programme(id, code, name);
        programme.demoData = true;
        return programme;
    }

    public String getCode() { return code; }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public boolean isDemoData() { return demoData; }
}
