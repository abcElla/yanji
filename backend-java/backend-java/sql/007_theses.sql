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
