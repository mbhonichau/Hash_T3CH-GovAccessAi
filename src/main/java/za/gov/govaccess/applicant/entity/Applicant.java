package za.gov.govaccess.applicant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "applicant")
public class Applicant {
    @Id
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Applicant() {}

    public Applicant(String fullName) {
        this.id = UUID.randomUUID();
        this.fullName = fullName;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public Instant getCreatedAt() { return createdAt; }
}
