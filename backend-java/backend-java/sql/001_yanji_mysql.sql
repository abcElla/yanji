-- 研迹业务后端 MySQL 8.0+/9.x 初始建库脚本
-- 安全重跑：只创建不存在的库和表；不会清空现有数据。
-- UUID 由应用层生成；时间一律存 UTC，业务周按 Asia/Shanghai 计算。
CREATE DATABASE IF NOT EXISTS yanji
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE yanji;

CREATE TABLE IF NOT EXISTS users (
  id CHAR(36) NOT NULL PRIMARY KEY,
  username VARCHAR(32) NOT NULL,
  password_hash VARCHAR(255) NOT NULL COMMENT '当前后端登录使用 Argon2id；后续可迁至 password_credentials',
  display_name VARCHAR(100) NOT NULL,
  account_role VARCHAR(20) NOT NULL DEFAULT 'student',
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_users_username (username),
  KEY ix_users_role_status_name (account_role,status,display_name),
  CONSTRAINT ck_users_status CHECK (status IN ('active','disabled')),
  CONSTRAINT ck_users_account_role CHECK (account_role IN ('student','mentor'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS password_credentials (
  user_id CHAR(36) NOT NULL PRIMARY KEY,
  username_normalized VARCHAR(32) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  password_changed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_credentials_username (username_normalized),
  CONSTRAINT fk_credentials_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS labs (
  id CHAR(36) NOT NULL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_labs_status CHECK (status IN ('active','disabled'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS memberships (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  role VARCHAR(20) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_membership (lab_id,user_id,role),
  KEY ix_memberships_user (user_id,active),
  CONSTRAINT fk_memberships_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT ck_memberships_role CHECK (role IN ('student','mentor'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS mentorships (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  mentor_id CHAR(36) NOT NULL,
  student_id CHAR(36) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_mentorship (lab_id,mentor_id,student_id),
  KEY ix_mentorship_student (student_id,active),
  CONSTRAINT fk_mentorship_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_mentorship_mentor FOREIGN KEY (mentor_id) REFERENCES users(id),
  CONSTRAINT fk_mentorship_student FOREIGN KEY (student_id) REFERENCES users(id),
  CONSTRAINT ck_mentorship_distinct CHECK (mentor_id <> student_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS mentor_applications (
  id CHAR(36) NOT NULL PRIMARY KEY,
  student_id CHAR(36) NOT NULL,
  mentor_id CHAR(36) NOT NULL,
  lab_id CHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  rejection_reason VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  decided_at DATETIME(6) NULL,
  KEY ix_mentor_applications_student (student_id,status,created_at),
  KEY ix_mentor_applications_mentor (mentor_id,lab_id,status),
  CONSTRAINT fk_mentor_applications_student FOREIGN KEY (student_id) REFERENCES users(id),
  CONSTRAINT fk_mentor_applications_mentor FOREIGN KEY (mentor_id) REFERENCES users(id),
  CONSTRAINT fk_mentor_applications_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT ck_mentor_applications_status CHECK (status IN ('pending','approved','rejected'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS invitations (
  id CHAR(36) NOT NULL PRIMARY KEY,
  code_hash CHAR(64) NOT NULL,
  lab_id CHAR(36) NOT NULL,
  intended_role VARCHAR(20) NOT NULL,
  mentor_id CHAR(36) NULL,
  expires_at DATETIME(6) NOT NULL,
  used_by CHAR(36) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_invitations_code (code_hash),
  KEY ix_invitations_expiry (expires_at,used_by),
  CONSTRAINT fk_invitations_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_invitations_mentor FOREIGN KEY (mentor_id) REFERENCES users(id),
  CONSTRAINT fk_invitations_used_by FOREIGN KEY (used_by) REFERENCES users(id),
  CONSTRAINT ck_invitations_role CHECK (intended_role IN ('student','mentor'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sessions (
  id CHAR(36) NOT NULL PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  installation_id CHAR(36) NOT NULL,
  device_label VARCHAR(100) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_sessions_token (token_hash),
  KEY ix_sessions_user (user_id,revoked_at,expires_at),
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stage_rule_sets (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  revision INT NOT NULL,
  rules_json JSON NOT NULL,
  approved_by CHAR(36) NOT NULL,
  effective_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_rules_revision (lab_id,id,revision),
  CONSTRAINT fk_rules_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_rules_approver FOREIGN KEY (approved_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stages (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  student_id CHAR(36) NOT NULL,
  title VARCHAR(200) NOT NULL,
  goal TEXT NOT NULL,
  due_at DATETIME(6) NULL,
  rule_set_id CHAR(36) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'active',
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_stages_student (lab_id,student_id,status,due_at),
  CONSTRAINT fk_stages_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_stages_student FOREIGN KEY (student_id) REFERENCES users(id),
  CONSTRAINT fk_stages_rules FOREIGN KEY (rule_set_id) REFERENCES stage_rule_sets(id),
  CONSTRAINT ck_stages_status CHECK (status IN ('active','submitted','accepted')),
  CONSTRAINT ck_stages_version CHECK (version >= 1)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stage_submissions (
  id CHAR(36) NOT NULL PRIMARY KEY,
  stage_id CHAR(36) NOT NULL,
  stage_version INT NOT NULL,
  actor_id CHAR(36) NOT NULL,
  evidence_refs JSON NOT NULL,
  submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  reviewed_by CHAR(36) NULL,
  review_decision VARCHAR(20) NULL,
  review_comment TEXT NULL,
  reviewed_at DATETIME(6) NULL,
  UNIQUE KEY uq_stage_submission_version (stage_id,stage_version),
  KEY ix_stage_submissions_stage (stage_id,submitted_at),
  CONSTRAINT fk_stage_submissions_stage FOREIGN KEY (stage_id) REFERENCES stages(id),
  CONSTRAINT fk_stage_submissions_actor FOREIGN KEY (actor_id) REFERENCES users(id),
  CONSTRAINT fk_stage_submissions_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
  CONSTRAINT ck_stage_review_decision CHECK (review_decision IS NULL OR review_decision IN ('accept','return'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS tasks (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  student_id CHAR(36) NOT NULL,
  stage_id CHAR(36) NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  type VARCHAR(20) NOT NULL DEFAULT 'other',
  due_at DATETIME(6) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'todo',
  priority TINYINT NOT NULL DEFAULT 2,
  acceptance_criteria JSON NOT NULL,
  dependency_ids JSON NOT NULL COMMENT '当前应用兼容列；正式依赖关系以 task_dependencies 为准',
  origin VARCHAR(20) NOT NULL DEFAULT 'human',
  created_by CHAR(36) NOT NULL,
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_tasks_scope (lab_id,student_id,status,due_at),
  KEY ix_tasks_stage (stage_id),
  CONSTRAINT fk_tasks_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_tasks_student FOREIGN KEY (student_id) REFERENCES users(id),
  CONSTRAINT fk_tasks_stage FOREIGN KEY (stage_id) REFERENCES stages(id),
  CONSTRAINT fk_tasks_creator FOREIGN KEY (created_by) REFERENCES users(id),
  CONSTRAINT ck_tasks_type CHECK (type IN ('reading','experiment','writing','other')),
  CONSTRAINT ck_tasks_status CHECK (status IN ('todo','in_progress','blocked','submitted','completed')),
  CONSTRAINT ck_tasks_priority CHECK (priority BETWEEN 1 AND 3),
  CONSTRAINT ck_tasks_origin CHECK (origin IN ('human','agent_approved')),
  CONSTRAINT ck_tasks_version CHECK (version >= 1)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_dependencies (
  task_id CHAR(36) NOT NULL,
  depends_on_task_id CHAR(36) NOT NULL,
  PRIMARY KEY (task_id,depends_on_task_id),
  KEY ix_dependency_target (depends_on_task_id),
  CONSTRAINT fk_dependency_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_dependency_parent FOREIGN KEY (depends_on_task_id) REFERENCES tasks(id),
  CONSTRAINT ck_dependency_distinct CHECK (task_id <> depends_on_task_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_events (
  id CHAR(36) NOT NULL PRIMARY KEY,
  task_id CHAR(36) NOT NULL,
  actor_id CHAR(36) NOT NULL,
  from_status VARCHAR(20) NOT NULL,
  to_status VARCHAR(20) NOT NULL,
  evidence_refs JSON NOT NULL,
  comment TEXT NOT NULL,
  happened_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_task_events_order (task_id,happened_at,id),
  CONSTRAINT fk_task_events_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT fk_task_events_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS files (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  owner_id CHAR(36) NOT NULL,
  client_upload_id CHAR(36) NOT NULL,
  purpose VARCHAR(20) NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  size BIGINT NOT NULL,
  mime VARCHAR(100) NOT NULL,
  upload_status VARCHAR(20) NOT NULL,
  scan_status VARCHAR(20) NOT NULL DEFAULT 'skipped',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_file_upload (owner_id,lab_id,client_upload_id),
  UNIQUE KEY uq_file_object (object_key),
  KEY ix_files_scope (lab_id,owner_id),
  CONSTRAINT fk_files_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_files_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT ck_files_purpose CHECK (purpose IN ('paper','experiment','report')),
  CONSTRAINT ck_files_size CHECK (size >= 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sources (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  owner_id CHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  entity_id CHAR(36) NOT NULL,
  visibility VARCHAR(20) NOT NULL,
  active_revision INT NOT NULL DEFAULT 1,
  revoked_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_sources_acl (lab_id,owner_id,revoked_at),
  KEY ix_sources_entity (kind,entity_id),
  CONSTRAINT fk_sources_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_sources_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT ck_sources_visibility CHECK (visibility IN ('private','lab','mentors'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS source_revisions (
  id CHAR(36) NOT NULL PRIMARY KEY,
  source_id CHAR(36) NOT NULL,
  revision INT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  locator_scheme VARCHAR(30) NOT NULL,
  file_id CHAR(36) NULL,
  text_snapshot LONGTEXT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_source_revision (source_id,revision),
  CONSTRAINT fk_source_revisions_source FOREIGN KEY (source_id) REFERENCES sources(id),
  CONSTRAINT fk_source_revisions_file FOREIGN KEY (file_id) REFERENCES files(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS chunks (
  id CHAR(36) NOT NULL PRIMARY KEY,
  source_id CHAR(36) NOT NULL,
  source_revision INT NOT NULL,
  chunk_id VARCHAR(80) NOT NULL,
  text LONGTEXT NOT NULL,
  locator JSON NOT NULL,
  embedding JSON NULL COMMENT 'MySQL 阶段存向量数组；检索可接独立向量服务或适配器',
  model_id VARCHAR(100) NULL,
  index_version VARCHAR(50) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_chunk_revision (source_id,source_revision,chunk_id),
  KEY ix_chunk_model (model_id,index_version),
  CONSTRAINT fk_chunks_source_revision FOREIGN KEY (source_id,source_revision)
    REFERENCES source_revisions(source_id,revision)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS papers (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  owner_id CHAR(36) NOT NULL,
  title VARCHAR(500) NOT NULL,
  authors JSON NULL,
  publication_year SMALLINT NULL,
  doi VARCHAR(255) NULL,
  file_id CHAR(36) NOT NULL,
  visibility VARCHAR(20) NOT NULL,
  ingest_status VARCHAR(20) NOT NULL DEFAULT 'queued',
  ingest_error_code VARCHAR(80) NULL,
  source_id CHAR(36) NULL,
  version INT NOT NULL DEFAULT 1,
  revoked_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_papers_scope (lab_id,owner_id,visibility,revoked_at),
  CONSTRAINT fk_papers_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_papers_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT fk_papers_file FOREIGN KEY (file_id) REFERENCES files(id),
  CONSTRAINT fk_papers_source FOREIGN KEY (source_id) REFERENCES sources(id),
  CONSTRAINT ck_papers_visibility CHECK (visibility IN ('private','lab')),
  CONSTRAINT ck_papers_ingest CHECK (ingest_status IN ('queued','parsing','indexing','ready','failed'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS experiments (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  owner_id CHAR(36) NOT NULL,
  task_id CHAR(36) NULL,
  current_revision INT NOT NULL DEFAULT 1,
  status VARCHAR(20) NOT NULL DEFAULT 'draft',
  visibility VARCHAR(20) NOT NULL DEFAULT 'private',
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_experiments_owner (lab_id,owner_id,status),
  CONSTRAINT fk_experiments_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_experiments_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT fk_experiments_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT ck_experiments_status CHECK (status IN ('draft','confirmed'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS experiment_revisions (
  id CHAR(36) NOT NULL PRIMARY KEY,
  experiment_id CHAR(36) NOT NULL,
  revision INT NOT NULL,
  purpose TEXT NOT NULL,
  parameters_json JSON NOT NULL,
  steps_json JSON NOT NULL,
  results_text LONGTEXT NULL,
  reflection TEXT NULL,
  file_ids JSON NOT NULL,
  confirmed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_experiment_revision (experiment_id,revision),
  CONSTRAINT fk_exp_rev_experiment FOREIGN KEY (experiment_id) REFERENCES experiments(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reading_notes (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  owner_id CHAR(36) NOT NULL,
  paper_id CHAR(36) NOT NULL,
  task_id CHAR(36) NULL,
  current_revision INT NOT NULL DEFAULT 1,
  status VARCHAR(20) NOT NULL DEFAULT 'draft',
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_notes_owner (lab_id,owner_id,status),
  CONSTRAINT fk_notes_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_notes_owner FOREIGN KEY (owner_id) REFERENCES users(id),
  CONSTRAINT fk_notes_paper FOREIGN KEY (paper_id) REFERENCES papers(id),
  CONSTRAINT fk_notes_task FOREIGN KEY (task_id) REFERENCES tasks(id),
  CONSTRAINT ck_notes_status CHECK (status IN ('draft','confirmed'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reading_note_revisions (
  id CHAR(36) NOT NULL PRIMARY KEY,
  note_id CHAR(36) NOT NULL,
  revision INT NOT NULL,
  claims JSON NOT NULL,
  questions JSON NOT NULL,
  source_refs JSON NOT NULL,
  confirmed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_note_revision (note_id,revision),
  CONSTRAINT fk_note_revisions_note FOREIGN KEY (note_id) REFERENCES reading_notes(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS weekly_reports (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  student_id CHAR(36) NOT NULL,
  week_start DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'draft',
  current_revision INT NOT NULL DEFAULT 1,
  version INT NOT NULL DEFAULT 1,
  done_items JSON NOT NULL,
  issues JSON NOT NULL,
  next_plan JSON NOT NULL,
  help_needed JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_reports_week (student_id,week_start),
  KEY ix_reports_scope (lab_id,student_id,status,week_start),
  CONSTRAINT fk_reports_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_reports_student FOREIGN KEY (student_id) REFERENCES users(id),
  CONSTRAINT ck_reports_status CHECK (status IN ('draft','submitted','reviewed','changes_requested'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS weekly_report_revisions (
  id CHAR(36) NOT NULL PRIMARY KEY,
  report_id CHAR(36) NOT NULL,
  revision INT NOT NULL,
  content JSON NOT NULL,
  submitted_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_report_revision (report_id,revision),
  CONSTRAINT fk_report_rev_report FOREIGN KEY (report_id) REFERENCES weekly_reports(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS mentor_reviews (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NULL,
  report_id CHAR(36) NOT NULL,
  report_revision INT NOT NULL,
  mentor_id CHAR(36) NOT NULL,
  body TEXT NOT NULL,
  decision VARCHAR(30) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_reviews_report (report_id,report_revision),
  CONSTRAINT fk_reviews_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_reviews_report_revision FOREIGN KEY (report_id,report_revision)
    REFERENCES weekly_report_revisions(report_id,revision),
  CONSTRAINT fk_reviews_mentor FOREIGN KEY (mentor_id) REFERENCES users(id),
  CONSTRAINT ck_reviews_decision CHECK (decision IN ('reviewed','changes_requested'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS mentor_review_drafts (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  report_id CHAR(36) NOT NULL,
  report_revision INT NOT NULL,
  mentor_id CHAR(36) NOT NULL,
  body TEXT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_review_draft (report_id,report_revision,mentor_id),
  CONSTRAINT fk_review_drafts_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_review_drafts_report FOREIGN KEY (report_id,report_revision)
    REFERENCES weekly_report_revisions(report_id,revision),
  CONSTRAINT fk_review_drafts_mentor FOREIGN KEY (mentor_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS agent_runs (
  id CHAR(36) NOT NULL PRIMARY KEY,
  lab_id CHAR(36) NOT NULL,
  requested_by CHAR(36) NOT NULL,
  student_id CHAR(36) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  subject JSON NOT NULL,
  input JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'queued',
  step VARCHAR(80) NULL,
  attempt INT NOT NULL DEFAULT 0,
  lease_expiry DATETIME(6) NULL,
  lease_token_hash CHAR(64) NULL,
  snapshot_id CHAR(36) NULL,
  version INT NOT NULL DEFAULT 1,
  result JSON NULL,
  error JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  KEY ix_runs_lease (status,lease_expiry),
  KEY ix_runs_scope (lab_id,student_id,created_at),
  CONSTRAINT fk_runs_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
  CONSTRAINT fk_runs_requester FOREIGN KEY (requested_by) REFERENCES users(id),
  CONSTRAINT fk_runs_student FOREIGN KEY (student_id) REFERENCES users(id),
  CONSTRAINT ck_runs_status CHECK (status IN ('queued','running','succeeded','failed','cancelled')),
  CONSTRAINT ck_runs_kind CHECK (kind IN ('stage_plan','paper_guide','weekly_report','research_qa','risk_brief'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS context_snapshots (
  id CHAR(36) NOT NULL PRIMARY KEY,
  run_id CHAR(36) NOT NULL,
  authorized_scope JSON NOT NULL,
  source_versions JSON NOT NULL,
  business_versions JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_snapshot_run (run_id,id),
  CONSTRAINT fk_snapshot_run FOREIGN KEY (run_id) REFERENCES agent_runs(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS agent_run_events (
  id CHAR(36) NOT NULL PRIMARY KEY,
  run_id CHAR(36) NOT NULL,
  attempt INT NOT NULL,
  event_key VARCHAR(100) NOT NULL,
  seq INT NOT NULL,
  step VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL,
  message VARCHAR(500) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_run_event_seq (run_id,seq),
  UNIQUE KEY uq_run_event_key (run_id,attempt,event_key),
  CONSTRAINT fk_run_events_run FOREIGN KEY (run_id) REFERENCES agent_runs(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS proposals (
  id CHAR(36) NOT NULL PRIMARY KEY,
  run_id CHAR(36) NOT NULL,
  action_type VARCHAR(40) NOT NULL,
  target_student_id CHAR(36) NOT NULL,
  target JSON NULL,
  payload JSON NOT NULL,
  reason TEXT NOT NULL,
  source_refs JSON NOT NULL,
  base_versions JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending',
  version INT NOT NULL DEFAULT 1,
  expires_at DATETIME(6) NOT NULL,
  execution_receipt JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY ix_proposals_run (run_id,status),
  KEY ix_proposals_student (target_student_id,status),
  CONSTRAINT fk_proposals_run FOREIGN KEY (run_id) REFERENCES agent_runs(id),
  CONSTRAINT fk_proposals_student FOREIGN KEY (target_student_id) REFERENCES users(id),
  CONSTRAINT ck_proposals_status CHECK (status IN ('pending','applied','rejected','expired')),
  CONSTRAINT ck_proposals_action CHECK (action_type IN
    ('task.create_batch','task.reschedule_batch','weekly_report.draft','reading_note.draft','mentor_feedback.draft'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS proposal_executions (
  proposal_id CHAR(36) NOT NULL PRIMARY KEY,
  actor_id CHAR(36) NOT NULL,
  receipt_json JSON NOT NULL,
  applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_executions_proposal FOREIGN KEY (proposal_id) REFERENCES proposals(id),
  CONSTRAINT fk_executions_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS notifications (
  id CHAR(36) NOT NULL PRIMARY KEY,
  recipient_id CHAR(36) NOT NULL,
  business_key VARCHAR(255) NOT NULL,
  type VARCHAR(50) NOT NULL,
  target JSON NOT NULL,
  read_at DATETIME(6) NULL,
  closed_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_notification_business (recipient_id,business_key),
  KEY ix_notifications_unread (recipient_id,read_at,closed_at),
  CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS outbox_events (
  id CHAR(36) NOT NULL PRIMARY KEY,
  event_type VARCHAR(60) NOT NULL,
  payload JSON NOT NULL,
  published_at DATETIME(6) NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NULL,
  locked_until DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_outbox_pending (published_at,next_attempt_at),
  CONSTRAINT ck_outbox_attempts CHECK (attempt_count >= 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS idempotency_records (
  id CHAR(36) NOT NULL PRIMARY KEY,
  actor_id CHAR(36) NOT NULL,
  method VARCHAR(10) NOT NULL,
  path VARCHAR(255) NOT NULL,
  `key` VARCHAR(100) NOT NULL,
  body_hash CHAR(64) NOT NULL,
  response JSON NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uq_idempotency (actor_id,method,path,`key`),
  KEY ix_idempotency_expiry (expires_at),
  CONSTRAINT fk_idempotency_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS worker_heartbeats (
  id CHAR(36) NOT NULL PRIMARY KEY,
  kind VARCHAR(10) NOT NULL,
  execution_mode VARCHAR(10) NOT NULL,
  host VARCHAR(255) NOT NULL,
  pid INT NOT NULL,
  updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS audit_events (
  id CHAR(36) NOT NULL PRIMARY KEY,
  actor_id CHAR(36) NULL,
  actor_type VARCHAR(20) NOT NULL,
  object_type VARCHAR(50) NOT NULL,
  object_id CHAR(36) NOT NULL,
  action VARCHAR(80) NOT NULL,
  request_id CHAR(36) NOT NULL,
  before_version INT NULL,
  after_version INT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_audit_object (object_type,object_id,created_at),
  KEY ix_audit_actor (actor_id,created_at),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB;
