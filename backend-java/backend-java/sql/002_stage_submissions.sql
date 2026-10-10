-- 对已执行 001 旧版脚本的 MySQL 库，补充阶段提交与审阅记录表。
USE yanji;
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
