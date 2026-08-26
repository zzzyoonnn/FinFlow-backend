package com.FinFlow.repository;

import com.FinFlow.domain.TransactionAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionAuditLogRepository extends JpaRepository<TransactionAuditLog, Long> {
}
