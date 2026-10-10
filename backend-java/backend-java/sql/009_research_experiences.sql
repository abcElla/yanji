-- Apply to existing MySQL databases before starting the new backend.
CREATE TABLE IF NOT EXISTS research_experiences (
  id VARCHAR(36) PRIMARY KEY,
  lab_id VARCHAR(36) NOT NULL,
  student_id VARCHAR(36) NOT NULL,
  recipient_id VARCHAR(36) NOT NULL,
  payload JSON NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version INT NOT NULL DEFAULT 1
);
SET @experience_scope_exists = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'research_experiences'
    AND index_name = 'ix_experience_scope');
SET @experience_scope_ddl = IF(@experience_scope_exists = 0,
  'CREATE INDEX ix_experience_scope ON research_experiences (lab_id, student_id, recipient_id)', 'SELECT 1');
PREPARE experience_scope_migration FROM @experience_scope_ddl;
EXECUTE experience_scope_migration;
DEALLOCATE PREPARE experience_scope_migration;
