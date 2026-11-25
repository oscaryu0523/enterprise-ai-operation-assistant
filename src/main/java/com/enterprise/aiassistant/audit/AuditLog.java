package com.enterprise.aiassistant.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_log")
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 100)
  private AuditEvent event;

  @Column(name = "user_id")
  private Long userId;

  @Column(name = "user_email", length = 255)
  private String userEmail;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column
  private String details;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private OffsetDateTime occurredAt;

  protected AuditLog() {}

  public AuditLog(
      AuditEvent event, Long userId, String userEmail, String ipAddress, String details) {
    this.event = event;
    this.userId = userId;
    this.userEmail = userEmail;
    this.ipAddress = ipAddress;
    this.details = details;
  }

  @PrePersist
  void prePersist() {
    if (occurredAt == null) {
      occurredAt = OffsetDateTime.now();
    }
  }

  public Long getId() {
    return id;
  }

  public AuditEvent getEvent() {
    return event;
  }

  public Long getUserId() {
    return userId;
  }

  public String getUserEmail() {
    return userEmail;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public String getDetails() {
    return details;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }
}
