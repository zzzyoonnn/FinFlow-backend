# ⚙️ Table Structure
### 🧩 Key Terms
| Term            | Meaning                                      |
| --------------- | -------------------------------------------- |
| **Deposit**     | Increase in account balance                  |
| **Withdraw**    | Decrease in account balance                  |
| **Transfer**    | Move funds between two accounts              |
| **Transaction** | Record of a deposit, withdrawal, or transfer |
| **Balance**     | Current account balance                      |
| **Account**     | User’s bank account                          |
| **User**        | Account owner                                |

### 🧑‍💼 user
| Column       | Type        | Description    |
| ------------ | ----------- | -------------- |
| `id`         | BIGINT (PK) | Unique user ID |
| `username`   | VARCHAR     | User login ID  |
| `password`   | VARCHAR     | User password  |
| `full_name`  | VARCHAR     | Full name      |
| `created_at` | TIMESTAMP   | Created time   |
| `updated_at` | TIMESTAMP   | Updated time   |

### 💳 account
| Column       | Type        | Description       |
| ------------ | ----------- | ----------------- |
| `id`         | BIGINT (PK) | Unique account ID |
| `number`     | VARCHAR     | Account number    |
| `balance`    | BIGINT      | Current balance   |
| `user_id`    | BIGINT (FK) | Owner (`user.id`) |
| `created_at` | TIMESTAMP   | Created time      |
| `updated_at` | TIMESTAMP   | Updated time      |

### 💸 account_transaction
| Column                     | Type        | Description                                          |
| -------------------------- | ----------- | ---------------------------------------------------- |
| `id`                       | BIGINT (PK) | Unique transaction ID                                |
| `amount`                   | BIGINT      | Transaction amount                                   |
| `transaction_type`         | VARCHAR     | Transaction type (`DEPOSIT`, `WITHDRAW`, `TRANSFER`) |
| `withdraw_account_balance` | BIGINT      | Balance of withdraw account after transaction        |
| `deposit_account_balance`  | BIGINT      | Balance of deposit account after transaction         |
| `withdraw_account_id`      | BIGINT (FK) | Withdraw account ID                                  |
| `deposit_account_id`       | BIGINT (FK) | Deposit account ID                                   |
| `created_at`               | TIMESTAMP   | Transaction time                                     |
| `updated_at`               | TIMESTAMP   | Updated time                                         |

### 🧾 transaction_audit_log

| Column             | Type        | Description                                  |
| ------------------ | ----------- | -------------------------------------------- |
| `id`               | BIGINT (PK) | Unique audit log ID                          |
| `event_id`         | VARCHAR(64) | Source event ID (unique)                     |
| `transaction_id`   | BIGINT      | Audited transaction ID (unique)              |
| `transaction_type` | VARCHAR(20) | Transaction type                             |
| `sender`           | VARCHAR(20) | Sender account number                        |
| `receiver`         | VARCHAR(20) | Receiver account number                      |
| `amount`           | BIGINT      | Transaction amount                           |
| `occurred_at`      | TIMESTAMP   | Time the transaction occurred                |
| `recorded_at`      | TIMESTAMP   | Time the Consumer stored the audit log       |

### ✅ processed_event

| Column         | Type        | Description                                      |
| -------------- | ----------- | ------------------------------------------------ |
| `event_id`     | VARCHAR(36) | Processed event ID and idempotency key (PK)      |
| `processed_at` | TIMESTAMP   | Processing completion time used for retention    |

## 🗂 ERD (Entity Relationship Diagram)

![ERD](/docs/architecture/erd.png)
