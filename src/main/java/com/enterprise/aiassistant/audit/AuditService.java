package com.enterprise.aiassistant.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

  private final AuditLogRepository auditLogRepository;

  public AuditService(AuditLogRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
  }

  @Transactional
  public void record(
      AuditEvent event, Long userId, String userEmail, String ipAddress, String details) {
    auditLogRepository.save(new AuditLog(event, userId, userEmail, ipAddress, details));
  }
}
