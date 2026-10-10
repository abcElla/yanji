-- Run once against the existing application database before deploying this change.
-- Preserve all reports; remove only the old per-student/week uniqueness constraint.
-- Keep a non-unique index for listing and for the student foreign key before dropping uniqueness.
SET @report_week_lookup = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'weekly_reports' AND index_name = 'ix_reports_week');
SET @report_week_ddl = IF(@report_week_lookup = 0,
  'ALTER TABLE weekly_reports ADD INDEX ix_reports_week (student_id,week_start)', 'SELECT 1');
PREPARE report_week_migration FROM @report_week_ddl;
EXECUTE report_week_migration;
DEALLOCATE PREPARE report_week_migration;
SET @report_week_index = (
  SELECT index_name FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'weekly_reports'
    AND non_unique = 0 AND index_name <> 'PRIMARY'
  GROUP BY index_name
  HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'student_id,week_start'
  LIMIT 1
);
SET @report_week_ddl = IF(@report_week_index IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE weekly_reports DROP INDEX `', REPLACE(@report_week_index, '`', '``'), '`'));
PREPARE report_week_migration FROM @report_week_ddl;
EXECUTE report_week_migration;
DEALLOCATE PREPARE report_week_migration;
