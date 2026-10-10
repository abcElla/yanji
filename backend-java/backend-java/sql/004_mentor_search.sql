-- Existing databases: use python -m scripts.apply_mentor_search_schema for an idempotent migration.
CREATE INDEX ix_users_role_status_name ON users (account_role,status,display_name);
