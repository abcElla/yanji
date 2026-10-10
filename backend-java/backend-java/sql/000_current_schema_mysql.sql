-- Fresh empty database only. Select your database before running.
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE IF NOT EXISTS `users` (
  `id` varchar(36) NOT NULL,
  `username` varchar(32) NOT NULL,
  `password_hash` LONGTEXT NOT NULL,
  `display_name` varchar(100) NOT NULL,
  `account_role` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  UNIQUE (`token_hash`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `labs` (
  `id` varchar(36) NOT NULL,
  `name` varchar(100) NOT NULL,
  `status` varchar(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `memberships` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  `role` varchar(20) NOT NULL,
  `active` boolean NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`lab_id`,`user_id`,`role`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mentorships` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `mentor_id` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `active` boolean NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`lab_id`,`mentor_id`,`student_id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`student_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mentor_applications` (
  `id` varchar(36) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `mentor_id` varchar(255) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `status` varchar(20) NOT NULL,
  `rejection_reason` varchar(500),
  `created_at` timestamp(6) NOT NULL,
  `decided_at` timestamp(6),
  PRIMARY KEY (`id`),
  FOREIGN KEY (`student_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `invitations` (
  `id` varchar(36) NOT NULL,
  `code_hash` varchar(64) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `intended_role` varchar(20) NOT NULL,
  `mentor_id` varchar(255),
  `expires_at` timestamp(6) NOT NULL,
  `used_by` varchar(255),
  PRIMARY KEY (`id`),
  UNIQUE (`code_hash`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`used_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `stage_rule_sets` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `rules_json` JSON NOT NULL,
  `approved_by` varchar(255) NOT NULL,
  `effective_at` timestamp(6) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`approved_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `stages` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `title` varchar(200) NOT NULL,
  `goal` LONGTEXT NOT NULL,
  `due_at` timestamp(6),
  `rule_set_id` varchar(36),
  `status` varchar(20) NOT NULL,
  `version` bigint NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`student_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `stage_submissions` (
  `id` varchar(36) NOT NULL,
  `stage_id` varchar(255) NOT NULL,
  `stage_version` bigint NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `evidence_refs` JSON NOT NULL,
  `submitted_at` timestamp(6) NOT NULL,
  `reviewed_by` varchar(255),
  `review_decision` varchar(20),
  `review_comment` LONGTEXT,
  `reviewed_at` timestamp(6),
  PRIMARY KEY (`id`),
  UNIQUE (`stage_id`,`stage_version`),
  FOREIGN KEY (`stage_id`) REFERENCES `stages` (`id`),
  FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `tasks` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `stage_id` varchar(255),
  `title` varchar(200) NOT NULL,
  `description` LONGTEXT NOT NULL,
  `type` varchar(20) NOT NULL,
  `due_at` timestamp(6),
  `status` varchar(20) NOT NULL,
  `priority` bigint NOT NULL,
  `acceptance_criteria` JSON NOT NULL,
  `dependency_ids` JSON NOT NULL,
  `origin` varchar(20) NOT NULL,
  `created_by` varchar(255) NOT NULL,
  `version` bigint NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`student_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`stage_id`) REFERENCES `stages` (`id`),
  FOREIGN KEY (`created_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `task_dependencies` (
  `task_id` varchar(255) NOT NULL,
  `depends_on_task_id` varchar(255) NOT NULL,
  PRIMARY KEY (`task_id`,`depends_on_task_id`),
  FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`),
  FOREIGN KEY (`depends_on_task_id`) REFERENCES `tasks` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `task_events` (
  `id` varchar(36) NOT NULL,
  `task_id` varchar(255) NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `from_status` varchar(20) NOT NULL,
  `to_status` varchar(20) NOT NULL,
  `evidence_refs` JSON NOT NULL,
  `comment` LONGTEXT NOT NULL,
  `happened_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`),
  FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `sources` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `owner_id` varchar(255) NOT NULL,
  `kind` varchar(30) NOT NULL,
  `entity_id` varchar(36) NOT NULL,
  `visibility` varchar(20) NOT NULL,
  `active_revision` bigint NOT NULL,
  `revoked_at` timestamp(6),
  PRIMARY KEY (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `source_revisions` (
  `id` varchar(36) NOT NULL,
  `source_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `content_hash` varchar(64) NOT NULL,
  `locator_scheme` varchar(30) NOT NULL,
  `file_id` varchar(255),
  `text_snapshot` LONGTEXT,
  PRIMARY KEY (`id`),
  UNIQUE (`source_id`,`revision`),
  FOREIGN KEY (`source_id`) REFERENCES `sources` (`id`),
  FOREIGN KEY (`file_id`) REFERENCES `files` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  UNIQUE (`object_key`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `papers` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `owner_id` varchar(255) NOT NULL,
  `title` varchar(500) NOT NULL,
  `authors` JSON,
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
  PRIMARY KEY (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`file_id`) REFERENCES `files` (`id`),
  FOREIGN KEY (`source_id`) REFERENCES `sources` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  PRIMARY KEY (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`paper_id`) REFERENCES `papers` (`id`),
  FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `reading_note_revisions` (
  `id` varchar(36) NOT NULL,
  `note_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `claims` JSON NOT NULL,
  `questions` JSON NOT NULL,
  `source_refs` JSON NOT NULL,
  `confirmed_at` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`note_id`,`revision`),
  FOREIGN KEY (`note_id`) REFERENCES `reading_notes` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  PRIMARY KEY (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`),
  FOREIGN KEY (`task_id`) REFERENCES `tasks` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `experiment_revisions` (
  `id` varchar(36) NOT NULL,
  `experiment_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `purpose` LONGTEXT NOT NULL,
  `parameters_json` JSON NOT NULL,
  `steps_json` JSON NOT NULL,
  `results_text` LONGTEXT,
  `reflection` LONGTEXT,
  `file_ids` JSON NOT NULL,
  `confirmed_at` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`experiment_id`,`revision`),
  FOREIGN KEY (`experiment_id`) REFERENCES `experiments` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `weekly_reports` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `week_start` date NOT NULL,
  `status` varchar(20) NOT NULL,
  `current_revision` bigint NOT NULL,
  `version` bigint NOT NULL,
  `done_items` JSON NOT NULL,
  `issues` JSON NOT NULL,
  `next_plan` JSON NOT NULL,
  `help_needed` JSON NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY ix_reports_week (`student_id`,`week_start`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`student_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `weekly_report_revisions` (
  `id` varchar(36) NOT NULL,
  `report_id` varchar(255) NOT NULL,
  `revision` bigint NOT NULL,
  `content` JSON NOT NULL,
  `submitted_at` timestamp(6),
  PRIMARY KEY (`id`),
  UNIQUE (`report_id`,`revision`),
  FOREIGN KEY (`report_id`) REFERENCES `weekly_reports` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mentor_reviews` (
  `id` varchar(36) NOT NULL,
  `report_id` varchar(255) NOT NULL,
  `report_revision` bigint NOT NULL,
  `mentor_id` varchar(255) NOT NULL,
  `decision` varchar(20) NOT NULL,
  `body` LONGTEXT NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  FOREIGN KEY (`report_id`) REFERENCES `weekly_reports` (`id`),
  FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `worker_heartbeats` (
  `id` varchar(36) NOT NULL,
  `kind` varchar(10) NOT NULL,
  `execution_mode` varchar(10) NOT NULL,
  `host` varchar(255) NOT NULL,
  `pid` bigint NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `agent_runs` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `requested_by` varchar(255) NOT NULL,
  `student_id` varchar(255) NOT NULL,
  `kind` varchar(30) NOT NULL,
  `subject` JSON NOT NULL,
  `input` JSON NOT NULL,
  `status` varchar(20) NOT NULL,
  `step` varchar(80),
  `attempt` bigint NOT NULL,
  `lease_expiry` timestamp(6),
  `lease_token_hash` varchar(64),
  `snapshot_id` varchar(36),
  `version` bigint NOT NULL,
  `result` JSON,
  `error` JSON,
  `created_at` timestamp(6) NOT NULL,
  `started_at` timestamp(6),
  `finished_at` timestamp(6),
  PRIMARY KEY (`id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`requested_by`) REFERENCES `users` (`id`),
  FOREIGN KEY (`student_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  UNIQUE (`run_id`,`attempt`,`event_key`),
  FOREIGN KEY (`run_id`) REFERENCES `agent_runs` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `context_snapshots` (
  `id` varchar(36) NOT NULL,
  `run_id` varchar(255) NOT NULL,
  `authorized_scope` JSON NOT NULL,
  `source_versions` JSON NOT NULL,
  `business_versions` JSON NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  FOREIGN KEY (`run_id`) REFERENCES `agent_runs` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `chunks` (
  `id` varchar(36) NOT NULL,
  `source_id` varchar(255) NOT NULL,
  `source_revision` bigint NOT NULL,
  `chunk_id` varchar(80) NOT NULL,
  `text` LONGTEXT NOT NULL,
  `locator` JSON NOT NULL,
  `embedding` JSON,
  `model_id` varchar(100),
  `index_version` varchar(50),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`source_id`,`source_revision`,`chunk_id`),
  FOREIGN KEY (`source_id`) REFERENCES `sources` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `proposals` (
  `id` varchar(36) NOT NULL,
  `run_id` varchar(255) NOT NULL,
  `target_student_id` varchar(255) NOT NULL,
  `action_type` varchar(40) NOT NULL,
  `target` JSON,
  `payload` JSON NOT NULL,
  `reason` LONGTEXT NOT NULL,
  `source_refs` JSON NOT NULL,
  `base_versions` JSON NOT NULL,
  `status` varchar(20) NOT NULL,
  `version` bigint NOT NULL,
  `expires_at` timestamp(6) NOT NULL,
  `execution_receipt` JSON,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  FOREIGN KEY (`run_id`) REFERENCES `agent_runs` (`id`),
  FOREIGN KEY (`target_student_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `proposal_executions` (
  `proposal_id` varchar(255) NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `receipt_json` JSON NOT NULL,
  `applied_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`proposal_id`),
  FOREIGN KEY (`proposal_id`) REFERENCES `proposals` (`id`),
  FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `mentor_review_drafts` (
  `id` varchar(36) NOT NULL,
  `lab_id` varchar(255) NOT NULL,
  `report_id` varchar(255) NOT NULL,
  `report_revision` bigint NOT NULL,
  `mentor_id` varchar(255) NOT NULL,
  `body` LONGTEXT NOT NULL,
  `version` bigint NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`report_id`,`report_revision`,`mentor_id`),
  FOREIGN KEY (`lab_id`) REFERENCES `labs` (`id`),
  FOREIGN KEY (`report_id`) REFERENCES `weekly_reports` (`id`),
  FOREIGN KEY (`mentor_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `notifications` (
  `id` varchar(36) NOT NULL,
  `recipient_id` varchar(255) NOT NULL,
  `business_key` varchar(255) NOT NULL,
  `type` varchar(50) NOT NULL,
  `target` JSON NOT NULL,
  `read_at` timestamp(6),
  `closed_at` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  FOREIGN KEY (`recipient_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `outbox_events` (
  `id` varchar(36) NOT NULL,
  `event_type` varchar(60) NOT NULL,
  `payload` JSON NOT NULL,
  `published_at` timestamp(6),
  `attempt_count` bigint NOT NULL,
  `next_attempt_at` timestamp(6),
  `locked_until` timestamp(6),
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `idempotency_records` (
  `id` varchar(36) NOT NULL,
  `actor_id` varchar(255) NOT NULL,
  `method` varchar(10) NOT NULL,
  `path` varchar(255) NOT NULL,
  `key` varchar(100) NOT NULL,
  `body_hash` varchar(64) NOT NULL,
  `response` JSON NOT NULL,
  `expires_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE (`actor_id`,`method`,`path`,`key`),
  FOREIGN KEY (`actor_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET FOREIGN_KEY_CHECKS=1;

-- Additive, rerunnable migration; does not modify existing papers or files.
CREATE TABLE IF NOT EXISTS theses (
 id varchar(36) NOT NULL, lab_id varchar(255) NOT NULL, student_id varchar(255) NOT NULL,
 title varchar(500) NOT NULL, version bigint NOT NULL, created_at datetime(6) NOT NULL,
 PRIMARY KEY (id), KEY ix_theses_student (student_id,lab_id,created_at),
 CONSTRAINT fk_theses_lab FOREIGN KEY (lab_id) REFERENCES labs(id),
 CONSTRAINT fk_theses_student FOREIGN KEY (student_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS thesis_revisions (
 id varchar(36) NOT NULL, thesis_id varchar(255) NOT NULL, revision_number bigint NOT NULL,
 file_id varchar(255) NOT NULL, paper_id varchar(255) NOT NULL,
 change_summary text NOT NULL, created_at datetime(6) NOT NULL,
 PRIMARY KEY (id), UNIQUE KEY uq_thesis_revision (thesis_id,revision_number),
 UNIQUE KEY uq_thesis_file (file_id), UNIQUE KEY uq_thesis_paper (paper_id),
 CONSTRAINT fk_thesis_revision_parent FOREIGN KEY (thesis_id) REFERENCES theses(id),
 CONSTRAINT fk_thesis_revision_file FOREIGN KEY (file_id) REFERENCES files(id),
 CONSTRAINT fk_thesis_revision_paper FOREIGN KEY (paper_id) REFERENCES papers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS thesis_feedback (
 id varchar(36) NOT NULL, revision_id varchar(255) NOT NULL, mentor_id varchar(255) NOT NULL,
 content text NOT NULL, decision varchar(30) NOT NULL, created_at datetime(6) NOT NULL,
 PRIMARY KEY (id), KEY ix_thesis_feedback_revision (revision_id,created_at),
 CONSTRAINT fk_thesis_feedback_revision FOREIGN KEY (revision_id) REFERENCES thesis_revisions(id),
 CONSTRAINT fk_thesis_feedback_mentor FOREIGN KEY (mentor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
