-- Apply through scripts.apply_mentor_applications_schema, which also adds/backfills users.account_role.
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
