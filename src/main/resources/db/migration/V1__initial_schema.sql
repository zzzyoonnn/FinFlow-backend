CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(20) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    fullname VARCHAR(40) NOT NULL,
    role VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE=InnoDB;

CREATE TABLE account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    number VARCHAR(10) NOT NULL,
    password BIGINT NOT NULL,
    balance BIGINT NOT NULL,
    version BIGINT,
    is_active BIT NOT NULL,
    user_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_account_number UNIQUE (number),
    CONSTRAINT fk_account_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB;

CREATE TABLE account_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    withdraw_account_id BIGINT,
    deposit_account_id BIGINT,
    amount BIGINT NOT NULL,
    withdraw_account_balance BIGINT,
    deposit_account_balance BIGINT,
    transaction_type VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    sender VARCHAR(255),
    receiver VARCHAR(255),
    tel VARCHAR(255),
    PRIMARY KEY (id),
    INDEX idx_transaction_withdraw (withdraw_account_id),
    INDEX idx_transaction_deposit (deposit_account_id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    transaction_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_record_key UNIQUE (idempotency_key),
    CONSTRAINT uk_idempotency_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_idempotency_transaction FOREIGN KEY (transaction_id)
        REFERENCES account_transaction (id)
) ENGINE=InnoDB;
