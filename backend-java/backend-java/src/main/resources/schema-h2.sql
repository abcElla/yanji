CREATE TABLE IF NOT EXISTS `users` (
  `id` varchar(36) NOT NULL,
  `username` varchar(32) NOT NULL,
  `password_hash` clob NOT NULL,
  `display_name` varchar(100) NOT NULL,
  `account_role` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`username`)
);

CREATE TABLE IF NOT EXISTS `sessions` (
  `id` varchar(36) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  `token_hash` varchar(64) NOT NULL,
  `installation_id` varchar(36) NOT NULL,
  `device_label` varchar(100) NOT NULL,
  `expires_at` timestamp(6) NOT NULL,
  `revoked_at` timestamp(6),
  `last_seen_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`token_hash`)
);

CREATE TABLE IF NOT EXISTS `labs` (
  `id` varchar(36) NOT NULL,
  `name` varchar(100) NOT NULL,
  `status` varchar(20) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `memberships` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  `role` varchar(20) NOT NULL,
  `active` boolean NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`lab_id`,`user_id`,`role`)
);

CREATE TABLE IF NOT EXISTS `mentorships` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `mentor_id` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `active` boolean NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`lab_id`,`mentor_id`,`student_id`)
);

CREATE TABLE IF NOT EXISTS `mentor_applications` (
  `id` varchar(36) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `mentor_id` varchar(255) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `status` varchar(20) NOT NULL,
  `rejection_reason` varchar(500),
  `created_at` timestamp(6) NOT NULL,
  `decided_at` timestamp(6),
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `invitations` (
  `id` varchar(36) NOT NULL,
  `code_hash` varchar(64) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `intended_role` varchar(20) NOT NULL,
  `mentor_id` varchar(255),
  `expires_at` timestamp(6) NOT NULL,
  `used_by` varchar(255),
  PRIMARY KEY (`id`),
  UNIQUE (`code_hash`)
);

CREATE TABLE IF NOT EXISTS `stage_rule_sets` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `rules_json` clob NOT NULL,
  `approved_by` varchar(255) NOT NULL,
  `effective_at` timestamp(6) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `stages` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `title` varchar(200) NOT NULL,
  `goal` clob NOT NULL,
  `due_at` timestamp(6),
  `rule_set_id` varchar(36),
  `status` varchar(20) NOT NULL,
  `version` bigint NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `stage_submissions` (
  `id` varchar(36) NOT NULL,
  `stage_id` varchar(255) NOT NULL,
  `stage_version` bigint NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `evidence_refs` clob NOT NULL,
  `submitted_at` timestamp(6) NOT NULL,
  `reviewed_by` varchar(255),
  `review_decision` varchar(20),
  `review_comment` clob,
  `reviewed_at` timestamp(6),
  PRIMARY KEY (`id`),
  UNIQUE (`stage_id`,`stage_version`)
);

CREATE TABLE IF NOT EXISTS `tasks` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `stage_id` varchar(255),
  `title` varchar(200) NOT NULL,
  `description` clob NOT NULL,
  `type` varchar(20) NOT NULL,
  `due_at` timestamp(6),
  `status` varchar(20) NOT NULL,
  `priority` bigint NOT NULL,
  `acceptance_criteria` clob NOT NULL,
  `dependency_ids` clob NOT NULL,
  `origin` varchar(20) NOT NULL,
  `created_by` varchar(255) NOT NULL,
  `version` bigint NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `task_dependencies` (
  `task_id` varchar(255) NOT NULL,
  `depends_on_task_id` varchar(255) NOT NULL,
  PRIMARY KEY (`task_id`,`depends_on_task_id`)
);

CREATE TABLE IF NOT EXISTS `task_events` (
  `id` varchar(36) NOT NULL,
  `task_id` varchar(255) NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `from_status` varchar(20) NOT NULL,
  `to_status` varchar(20) NOT NULL,
  `evidence_refs` clob NOT NULL,
  `comment` clob NOT NULL,
  `happened_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `sources` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `owner_id` varchar(255) NOT NULL,
  `kind` varchar(30) NOT NULL,
  `entity_id` varchar(36) NOT NULL,
  `visibility` varchar(20) NOT NULL,
  `active_revision` bigint NOT NULL,
  `revoked_at` timestamp(6),
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `source_revisions` (
  `id` varchar(36) NOT NULL,
  `source_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `locator_scheme` varchar(30) NOT NULL,
  `file_id` varchar(255),
  `text_snapshot` clob,
  PRIMARY KEY (`id`),
  UNIQUE (`source_id`,`revision`)
);

CREATE TABLE IF NOT EXISTS `files` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `owner_id` varchar(255) NOT NULL,
  `client_upload_id` varchar(36) NOT NULL,
  `purpose` varchar(20) NOT NULL,
  `object_key` varchar(500) NOT NULL,
  `sha256` varchar(64) NOT NULL,
  `size` bigint NOT NULL,
  `mime` varchar(100) NOT NULL,
  `upload_status` varchar(20) NOT NULL,
  `scan_status` varchar(20) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`owner_id`,`lab_id`,`client_upload_id`),
  UNIQUE (`object_key`)
);

CREATE TABLE IF NOT EXISTS `papers` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `owner_id` varchar(255) NOT NULL,
  `title` varchar(500) NOT NULL,
  `authors` clob,
  `publication_year` bigint,
  `doi` varchar(255),
  `file_id` varchar(255) NOT NULL,
  `visibility` varchar(20) NOT NULL,
  `ingest_status` varchar(20) NOT NULL,
  `ingest_error_code` varchar(80),
  `source_id` varchar(255),
  `version` bigint NOT NULL,
  `revoked_at` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `reading_notes` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `owner_id` varchar(255) NOT NULL,
  `paper_id` varchar(255) NOT NULL,
  `task_id` varchar(255),
  `current_revision` bigint NOT NULL,
  `status` varchar(20) NOT NULL,
  `version` bigint NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `reading_note_revisions` (
  `id` varchar(36) NOT NULL,
  `note_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `claims` clob NOT NULL,
  `questions` clob NOT NULL,
  `source_refs` clob NOT NULL,
  `confirmed_at` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`note_id`,`revision`)
);

CREATE TABLE IF NOT EXISTS `experiments` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `owner_id` varchar(255) NOT NULL,
  `task_id` varchar(255),
  `current_revision` bigint NOT NULL,
  `status` varchar(20) NOT NULL,
  `visibility` varchar(20) NOT NULL,
  `version` bigint NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `experiment_revisions` (
  `id` varchar(36) NOT NULL,
  `experiment_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `purpose` clob NOT NULL,
  `parameters_json` clob NOT NULL,
  `steps_json` clob NOT NULL,
  `results_text` clob,
  `reflection` clob,
  `file_ids` clob NOT NULL,
  `confirmed_at` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`experiment_id`,`revision`)
);

CREATE TABLE IF NOT EXISTS `weekly_reports` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `week_start` date NOT NULL,
  `status` varchar(20) NOT NULL,
  `current_revision` bigint NOT NULL,
  `version` bigint NOT NULL,
  `done_items` clob NOT NULL,
  `issues` clob NOT NULL,
  `next_plan` clob NOT NULL,
  `help_needed` clob NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `weekly_report_revisions` (
  `id` varchar(36) NOT NULL,
  `report_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `content` clob NOT NULL,
  `submitted_at` timestamp(6),
  PRIMARY KEY (`id`),
  UNIQUE (`report_id`,`revision`)
);

CREATE TABLE IF NOT EXISTS `mentor_reviews` (
  `id` varchar(36) NOT NULL,
  `report_id` varchar(255) NOT NULL,
  `report_revision` bigint NOT NULL,
  `mentor_id` varchar(255) NOT NULL,
  `decision` varchar(20) NOT NULL,
  `body` clob NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `worker_heartbeats` (
  `id` varchar(36) NOT NULL,
  `kind` varchar(10) NOT NULL,
  `execution_mode` varchar(10) NOT NULL,
  `host` varchar(255) NOT NULL,
  `pid` bigint NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `agent_runs` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `requested_by` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `kind` varchar(30) NOT NULL,
  `subject` clob NOT NULL,
  `input` clob NOT NULL,
  `status` varchar(20) NOT NULL,
  `step` varchar(80),
  `attempt` bigint NOT NULL,
  `lease_expiry` timestamp(6),
  `lease_token_hash` varchar(64),
  `snapshot_id` varchar(36),
  `version` bigint NOT NULL,
  `result` clob,
  `error` clob,
  `created_at` timestamp(6) NOT NULL,
  `started_at` timestamp(6),
  `finished_at` timestamp(6),
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `agent_run_events` (
  `id` varchar(36) NOT NULL,
  `run_id` varchar(255) NOT NULL,
  `attempt` bigint NOT NULL,
  `event_key` varchar(100) NOT NULL,
  `seq` bigint NOT NULL,
  `step` varchar(80) NOT NULL,
  `status` varchar(30) NOT NULL,
  `message` varchar(500),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`run_id`,`seq`),
  UNIQUE (`run_id`,`attempt`,`event_key`)
);

CREATE TABLE IF NOT EXISTS `context_snapshots` (
  `id` varchar(36) NOT NULL,
  `run_id` varchar(255) NOT NULL,
  `authorized_scope` clob NOT NULL,
  `source_versions` clob NOT NULL,
  `business_versions` clob NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `chunks` (
  `id` varchar(36) NOT NULL,
  `source_id` varchar(255) NOT NULL,
  `source_revision` bigint NOT NULL,
  `chunk_id` varchar(80) NOT NULL,
  `text` clob NOT NULL,
  `locator` clob NOT NULL,
  `embedding` clob,
  `model_id` varchar(100),
  `index_version` varchar(50),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`source_id`,`source_revision`,`chunk_id`)
);

CREATE TABLE IF NOT EXISTS `proposals` (
  `id` varchar(36) NOT NULL,
  `run_id` varchar(255) NOT NULL,
  `target_student_id` varchar(255) NOT NULL,
  `action_type` varchar(40) NOT NULL,
  `target` clob,
  `payload` clob NOT NULL,
  `reason` clob NOT NULL,
  `source_refs` clob NOT NULL,
  `base_versions` clob NOT NULL,
  `status` varchar(20) NOT NULL,
  `version` bigint NOT NULL,
  `expires_at` timestamp(6) NOT NULL,
  `execution_receipt` clob,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `proposal_executions` (
  `proposal_id` varchar(255) NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `receipt_json` clob NOT NULL,
  `applied_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`proposal_id`)
);

CREATE TABLE IF NOT EXISTS `mentor_review_drafts` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `report_id` varchar(255) NOT NULL,
  `report_revision` bigint NOT NULL,
  `mentor_id` varchar(255) NOT NULL,
  `body` clob NOT NULL,
  `version` bigint NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`report_id`,`report_revision`,`mentor_id`)
);

CREATE TABLE IF NOT EXISTS `notifications` (
  `id` varchar(36) NOT NULL,
  `recipient_id` varchar(255) NOT NULL,
  `business_key` varchar(255) NOT NULL,
  `type` varchar(50) NOT NULL,
  `target` clob NOT NULL,
  `read_at` timestamp(6),
  `closed_at` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `outbox_events` (
  `id` varchar(36) NOT NULL,
  `event_type` varchar(60) NOT NULL,
  `payload` clob NOT NULL,
  `published_at` timestamp(6),
  `attempt_count` bigint NOT NULL,
  `next_attempt_at` timestamp(6),
  `locked_until` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE IF NOT EXISTS `idempotency_records` (
  `id` varchar(36) NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `method` varchar(10) NOT NULL,
  `path` varchar(255) NOT NULL,
  `key` varchar(100) NOT NULL,
  `body_hash` varchar(64) NOT NULL,
  `response` clob NOT NULL,
  `expires_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`actor_id`,`method`,`path`,`key`)
);

ALTER TABLE `sessions` ADD CONSTRAINT IF NOT EXISTS `fk_sessions_0` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `memberships` ADD CONSTRAINT IF NOT EXISTS `fk_memberships_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `memberships` ADD CONSTRAINT IF NOT EXISTS `fk_memberships_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `mentorships` ADD CONSTRAINT IF NOT EXISTS `fk_mentorships_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `mentorships` ADD CONSTRAINT IF NOT EXISTS `fk_mentorships_1` FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`);

ALTER TABLE `mentorships` ADD CONSTRAINT IF NOT EXISTS `fk_mentorships_2` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`);

ALTER TABLE `mentor_applications` ADD CONSTRAINT IF NOT EXISTS `fk_mentor_applications_0` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`);

ALTER TABLE `mentor_applications` ADD CONSTRAINT IF NOT EXISTS `fk_mentor_applications_1` FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`);

ALTER TABLE `mentor_applications` ADD CONSTRAINT IF NOT EXISTS `fk_mentor_applications_2` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `invitations` ADD CONSTRAINT IF NOT EXISTS `fk_invitations_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `invitations` ADD CONSTRAINT IF NOT EXISTS `fk_invitations_1` FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`);

ALTER TABLE `invitations` ADD CONSTRAINT IF NOT EXISTS `fk_invitations_2` FOREIGN KEY (`used_by`) REFERENCES `users` (`id`);

ALTER TABLE `stage_rule_sets` ADD CONSTRAINT IF NOT EXISTS `fk_stage_rule_sets_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `stage_rule_sets` ADD CONSTRAINT IF NOT EXISTS `fk_stage_rule_sets_1` FOREIGN KEY (`approved_by`) REFERENCES `users` (`id`);

ALTER TABLE `stages` ADD CONSTRAINT IF NOT EXISTS `fk_stages_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `stages` ADD CONSTRAINT IF NOT EXISTS `fk_stages_1` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`);

ALTER TABLE `stage_submissions` ADD CONSTRAINT IF NOT EXISTS `fk_stage_submissions_0` FOREIGN KEY (`stage_id`) REFERENCES `stages` (`id`);

ALTER TABLE `stage_submissions` ADD CONSTRAINT IF NOT EXISTS `fk_stage_submissions_1` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`);

ALTER TABLE `stage_submissions` ADD CONSTRAINT IF NOT EXISTS `fk_stage_submissions_2` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`);

ALTER TABLE `tasks` ADD CONSTRAINT IF NOT EXISTS `fk_tasks_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `tasks` ADD CONSTRAINT IF NOT EXISTS `fk_tasks_1` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`);

ALTER TABLE `tasks` ADD CONSTRAINT IF NOT EXISTS `fk_tasks_2` FOREIGN KEY (`stage_id`) REFERENCES `stages` (`id`);

ALTER TABLE `tasks` ADD CONSTRAINT IF NOT EXISTS `fk_tasks_3` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`);

ALTER TABLE `task_dependencies` ADD CONSTRAINT IF NOT EXISTS `fk_task_dependencies_0` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`);

ALTER TABLE `task_dependencies` ADD CONSTRAINT IF NOT EXISTS `fk_task_dependencies_1` FOREIGN KEY (`depends_on_task_id`) REFERENCES `tasks` (`id`);

ALTER TABLE `task_events` ADD CONSTRAINT IF NOT EXISTS `fk_task_events_0` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`);

ALTER TABLE `task_events` ADD CONSTRAINT IF NOT EXISTS `fk_task_events_1` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`);

ALTER TABLE `sources` ADD CONSTRAINT IF NOT EXISTS `fk_sources_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `sources` ADD CONSTRAINT IF NOT EXISTS `fk_sources_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`);

ALTER TABLE `source_revisions` ADD CONSTRAINT IF NOT EXISTS `fk_source_revisions_0` FOREIGN KEY (`source_id`) REFERENCES `sources` (`id`);

ALTER TABLE `source_revisions` ADD CONSTRAINT IF NOT EXISTS `fk_source_revisions_1` FOREIGN KEY (`file_id`) REFERENCES `files` (`id`);

ALTER TABLE `files` ADD CONSTRAINT IF NOT EXISTS `fk_files_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `files` ADD CONSTRAINT IF NOT EXISTS `fk_files_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`);

ALTER TABLE `papers` ADD CONSTRAINT IF NOT EXISTS `fk_papers_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `papers` ADD CONSTRAINT IF NOT EXISTS `fk_papers_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`);

ALTER TABLE `papers` ADD CONSTRAINT IF NOT EXISTS `fk_papers_2` FOREIGN KEY (`file_id`) REFERENCES `files` (`id`);

ALTER TABLE `papers` ADD CONSTRAINT IF NOT EXISTS `fk_papers_3` FOREIGN KEY (`source_id`) REFERENCES `sources` (`id`);

ALTER TABLE `reading_notes` ADD CONSTRAINT IF NOT EXISTS `fk_reading_notes_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `reading_notes` ADD CONSTRAINT IF NOT EXISTS `fk_reading_notes_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`);

ALTER TABLE `reading_notes` ADD CONSTRAINT IF NOT EXISTS `fk_reading_notes_2` FOREIGN KEY (`paper_id`) REFERENCES `papers` (`id`);

ALTER TABLE `reading_notes` ADD CONSTRAINT IF NOT EXISTS `fk_reading_notes_3` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`);

ALTER TABLE `reading_note_revisions` ADD CONSTRAINT IF NOT EXISTS `fk_reading_note_revisions_0` FOREIGN KEY (`note_id`) REFERENCES `reading_notes` (`id`);

ALTER TABLE `experiments` ADD CONSTRAINT IF NOT EXISTS `fk_experiments_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `experiments` ADD CONSTRAINT IF NOT EXISTS `fk_experiments_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`);

ALTER TABLE `experiments` ADD CONSTRAINT IF NOT EXISTS `fk_experiments_2` FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`);

ALTER TABLE `experiment_revisions` ADD CONSTRAINT IF NOT EXISTS `fk_experiment_revisions_0` FOREIGN KEY (`experiment_id`) REFERENCES `experiments` (`id`);

ALTER TABLE `weekly_reports` ADD CONSTRAINT IF NOT EXISTS `fk_weekly_reports_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `weekly_reports` ADD CONSTRAINT IF NOT EXISTS `fk_weekly_reports_1` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`);

ALTER TABLE `weekly_report_revisions` ADD CONSTRAINT IF NOT EXISTS `fk_weekly_report_revisions_0` FOREIGN KEY (`report_id`) REFERENCES `weekly_reports` (`id`);

ALTER TABLE `mentor_reviews` ADD CONSTRAINT IF NOT EXISTS `fk_mentor_reviews_0` FOREIGN KEY (`report_id`) REFERENCES `weekly_reports` (`id`);

ALTER TABLE `mentor_reviews` ADD CONSTRAINT IF NOT EXISTS `fk_mentor_reviews_1` FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`);

ALTER TABLE `agent_runs` ADD CONSTRAINT IF NOT EXISTS `fk_agent_runs_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `agent_runs` ADD CONSTRAINT IF NOT EXISTS `fk_agent_runs_1` FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`);

ALTER TABLE `agent_runs` ADD CONSTRAINT IF NOT EXISTS `fk_agent_runs_2` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`);

ALTER TABLE `agent_run_events` ADD CONSTRAINT IF NOT EXISTS `fk_agent_run_events_0` FOREIGN KEY (`run_id`) REFERENCES `agent_runs` (`id`);

ALTER TABLE `context_snapshots` ADD CONSTRAINT IF NOT EXISTS `fk_context_snapshots_0` FOREIGN KEY (`run_id`) REFERENCES `agent_runs` (`id`);

ALTER TABLE `chunks` ADD CONSTRAINT IF NOT EXISTS `fk_chunks_0` FOREIGN KEY (`source_id`) REFERENCES `sources` (`id`);

ALTER TABLE `proposals` ADD CONSTRAINT IF NOT EXISTS `fk_proposals_0` FOREIGN KEY (`run_id`) REFERENCES `agent_runs` (`id`);

ALTER TABLE `proposals` ADD CONSTRAINT IF NOT EXISTS `fk_proposals_1` FOREIGN KEY (`target_student_id`) REFERENCES `users` (`id`);

ALTER TABLE `proposal_executions` ADD CONSTRAINT IF NOT EXISTS `fk_proposal_executions_0` FOREIGN KEY (`proposal_id`) REFERENCES `proposals` (`id`);

ALTER TABLE `proposal_executions` ADD CONSTRAINT IF NOT EXISTS `fk_proposal_executions_1` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`);

ALTER TABLE `mentor_review_drafts` ADD CONSTRAINT IF NOT EXISTS `fk_mentor_review_drafts_0` FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`);

ALTER TABLE `mentor_review_drafts` ADD CONSTRAINT IF NOT EXISTS `fk_mentor_review_drafts_1` FOREIGN KEY (`report_id`) REFERENCES `weekly_reports` (`id`);

ALTER TABLE `mentor_review_drafts` ADD CONSTRAINT IF NOT EXISTS `fk_mentor_review_drafts_2` FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`);

ALTER TABLE `notifications` ADD CONSTRAINT IF NOT EXISTS `fk_notifications_0` FOREIGN KEY (`recipient_id`) REFERENCES `users` (`id`);

ALTER TABLE `idempotency_records` ADD CONSTRAINT IF NOT EXISTS `fk_idempotency_records_0` FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`);
-- Additive, rerunnable migration; does not modify existing papers or files.
CREATE TABLE IF NOT EXISTS theses (
 id varchar(36) NOT NULL, lab_id varchar(255) NOT NULL, student_id varchar(255) NOT NULL,
 title varchar(500) NOT NULL, version bigint NOT NULL, created_at timestamp(6) NOT NULL,
 PRIMARY KEY (id), INDEX ix_theses_student (student_id,lab_id,created_at),
 CONSTRAINT fk_theses_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
 CONSTRAINT fk_theses_student FOREIGN KEY (student_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS thesis_revisions (
 id varchar(36) NOT NULL, thesis_id varchar(255) NOT NULL, revision_number bigint NOT NULL,
 file_id varchar(255) NOT NULL, paper_id varchar(255) NOT NULL,
 change_summary clob NOT NULL, created_at timestamp(6) NOT NULL,
 PRIMARY KEY (id), UNIQUE INDEX uq_thesis_revision (thesis_id,revision_number),
 UNIQUE INDEX uq_thesis_file (file_id), UNIQUE INDEX uq_thesis_paper (paper_id),
 CONSTRAINT fk_thesis_revision_parent FOREIGN KEY (thesis_id) REFERENCES theses(id),
 CONSTRAINT fk_thesis_revision_file FOREIGN KEY (file_id) REFERENCES files(id),
 CONSTRAINT fk_thesis_revision_paper FOREIGN KEY (paper_id) REFERENCES papers(id)
);
CREATE TABLE IF NOT EXISTS thesis_feedback (
 id varchar(36) NOT NULL, revision_id varchar(255) NOT NULL, mentor_id varchar(255) NOT NULL,
 content clob NOT NULL, decision varchar(30) NOT NULL, created_at timestamp(6) NOT NULL,
 PRIMARY KEY (id), INDEX ix_thesis_feedback_revision (revision_id,created_at),
 CONSTRAINT fk_thesis_feedback_revision FOREIGN KEY (revision_id) REFERENCES thesis_revisions(id),
 CONSTRAINT fk_thesis_feedback_mentor FOREIGN KEY (mentor_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS research_followups (
 id VARCHAR(36) PRIMARY KEY, lab_id VARCHAR(36) NOT NULL, student_id VARCHAR(36) NOT NULL,
 recipient_id VARCHAR(36) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'waiting',
 changes CLOB NOT NULL, run_id VARCHAR(36), run_created_at TIMESTAMP,
 task_ids CLOB NOT NULL, applied_items CLOB NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 version INT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS research_followup_events (
 id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) NOT NULL, recipient_id VARCHAR(36) NOT NULL,
 followup_id VARCHAR(36) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(event_id,recipient_id)
);
CREATE TABLE IF NOT EXISTS research_followup_settings (
 id VARCHAR(36) PRIMARY KEY, enabled_since TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO research_followup_settings(id) SELECT 'global' WHERE NOT EXISTS (SELECT 1 FROM research_followup_settings WHERE id='global');

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
