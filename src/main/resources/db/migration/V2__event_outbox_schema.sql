CREATE TABLE IF NOT EXISTS outbox_event (
    event_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    failed_at DATETIME(6),
    last_error VARCHAR(1000),
    PRIMARY KEY (event_id),
    INDEX idx_outbox_publishable (status, next_attempt_at, created_at),
    INDEX idx_outbox_published_cleanup (status, published_at),
    INDEX idx_outbox_failed_cleanup (status, failed_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS transaction_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    transaction_id BIGINT NOT NULL,
    transaction_type VARCHAR(20) NOT NULL,
    sender VARCHAR(20) NOT NULL,
    receiver VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_transaction_audit_event UNIQUE (event_id),
    CONSTRAINT uk_transaction_audit_transaction UNIQUE (transaction_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS processed_event (
    event_id VARCHAR(36) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_processed_event_cleanup (processed_at)
) ENGINE=InnoDB;
