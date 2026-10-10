-- Frontend API demo fixtures for the three existing, authorized student accounts.
-- Safe to rerun: each insert checks its fixture key. No existing rows are updated.
-- Run against the configured development database only.
START TRANSACTION;

INSERT INTO stages (id, lab_id, student_id, title, goal, due_at, rule_set_id, status, version, created_at)
SELECT UUID(), x.lab_id, x.student_id, '演示·文献调研', '梳理研究背景、整理三篇代表性文献。', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 14 DAY), NULL, 'active', 1, UTC_TIMESTAMP()
FROM mentorships x JOIN users u ON u.id = x.student_id
WHERE x.active = 1 AND u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002')
AND NOT EXISTS (SELECT 1 FROM stages s WHERE s.student_id = x.student_id AND s.title = '演示·文献调研');

INSERT INTO stages (id, lab_id, student_id, title, goal, due_at, rule_set_id, status, version, created_at)
SELECT UUID(), x.lab_id, x.student_id, '演示·实验验证', '完成基线实验并提交阶段结果。', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 21 DAY), NULL, 'submitted', 2, DATE_SUB(UTC_TIMESTAMP(), INTERVAL 2 DAY)
FROM mentorships x JOIN users u ON u.id = x.student_id
WHERE x.active = 1 AND u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002')
AND NOT EXISTS (SELECT 1 FROM stages s WHERE s.student_id = x.student_id AND s.title = '演示·实验验证');

INSERT INTO stage_submissions (id, stage_id, stage_version, actor_id, evidence_refs, submitted_at)
SELECT UUID(), s.id, 2, s.student_id, JSON_ARRAY(), DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY)
FROM stages s JOIN users u ON u.id = s.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND s.title = '演示·实验验证'
AND NOT EXISTS (SELECT 1 FROM stage_submissions ss WHERE ss.stage_id = s.id AND ss.stage_version = 2);

INSERT INTO tasks (id, lab_id, student_id, stage_id, title, description, type, due_at, status, priority, acceptance_criteria, dependency_ids, origin, created_by, version, created_at)
SELECT UUID(), s.lab_id, s.student_id, s.id, '演示·阅读三篇相关论文', '整理研究问题和主要方法。', 'reading', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 5 DAY), 'todo', 2, JSON_ARRAY('列出三篇论文', '总结各论文贡献'), JSON_ARRAY(), 'human', x.mentor_id, 1, UTC_TIMESTAMP()
FROM stages s JOIN mentorships x ON x.student_id = s.student_id AND x.lab_id = s.lab_id AND x.active = 1 JOIN users u ON u.id = s.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND s.title = '演示·文献调研'
AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.student_id = s.student_id AND t.title = '演示·阅读三篇相关论文');

INSERT INTO tasks (id, lab_id, student_id, stage_id, title, description, type, due_at, status, priority, acceptance_criteria, dependency_ids, origin, created_by, version, created_at)
SELECT UUID(), s.lab_id, s.student_id, s.id, '演示·整理实验数据', '清洗数据并记录异常值。', 'experiment', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 3 DAY), 'in_progress', 1, JSON_ARRAY('完成数据质量检查'), JSON_ARRAY(), 'human', x.mentor_id, 2, DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY)
FROM stages s JOIN mentorships x ON x.student_id = s.student_id AND x.lab_id = s.lab_id AND x.active = 1 JOIN users u ON u.id = s.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND s.title = '演示·实验验证'
AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.student_id = s.student_id AND t.title = '演示·整理实验数据');

INSERT INTO tasks (id, lab_id, student_id, stage_id, title, description, type, due_at, status, priority, acceptance_criteria, dependency_ids, origin, created_by, version, created_at)
SELECT UUID(), s.lab_id, s.student_id, s.id, '演示·补充对照实验', '逾期任务，用于测试首页风险卡片。', 'experiment', DATE_SUB(UTC_TIMESTAMP(), INTERVAL 2 DAY), 'blocked', 1, JSON_ARRAY('完成对照组结果'), JSON_ARRAY(), 'human', x.mentor_id, 2, DATE_SUB(UTC_TIMESTAMP(), INTERVAL 7 DAY)
FROM stages s JOIN mentorships x ON x.student_id = s.student_id AND x.lab_id = s.lab_id AND x.active = 1 JOIN users u ON u.id = s.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND s.title = '演示·实验验证'
AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.student_id = s.student_id AND t.title = '演示·补充对照实验');

INSERT INTO tasks (id, lab_id, student_id, stage_id, title, description, type, due_at, status, priority, acceptance_criteria, dependency_ids, origin, created_by, version, created_at)
SELECT UUID(), s.lab_id, s.student_id, s.id, '演示·撰写阶段小结', '已提交，等待导师审核。', 'writing', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 7 DAY), 'submitted', 2, JSON_ARRAY('提交阶段小结'), JSON_ARRAY(), 'human', x.mentor_id, 2, DATE_SUB(UTC_TIMESTAMP(), INTERVAL 3 DAY)
FROM stages s JOIN mentorships x ON x.student_id = s.student_id AND x.lab_id = s.lab_id AND x.active = 1 JOIN users u ON u.id = s.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND s.title = '演示·实验验证'
AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.student_id = s.student_id AND t.title = '演示·撰写阶段小结');

INSERT INTO task_events (id, task_id, actor_id, from_status, to_status, evidence_refs, comment, happened_at)
SELECT UUID(), t.id, IF(t.status = 'todo', t.created_by, t.student_id), '', t.status, JSON_ARRAY(), '演示数据初始化', t.created_at
FROM tasks t JOIN users u ON u.id = t.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND t.title LIKE '演示·%'
AND NOT EXISTS (SELECT 1 FROM task_events e WHERE e.task_id = t.id);

INSERT INTO weekly_reports (id, lab_id, student_id, week_start, status, current_revision, version, done_items, issues, next_plan, help_needed, created_at)
SELECT UUID(), x.lab_id, x.student_id, '2026-09-21', 'submitted', 1, 2,
JSON_ARRAY(JSON_OBJECT('id', UUID(), 'text', '完成基线实验数据整理', 'origin', 'human', 'source_refs', JSON_ARRAY())),
JSON_ARRAY(JSON_OBJECT('id', UUID(), 'text', '对照组结果仍需复核', 'origin', 'human', 'source_refs', JSON_ARRAY())),
JSON_ARRAY(JSON_OBJECT('id', UUID(), 'text', '下周完成文献综述', 'origin', 'human', 'source_refs', JSON_ARRAY())),
JSON_ARRAY(), DATE_SUB(UTC_TIMESTAMP(), INTERVAL 8 DAY)
FROM mentorships x JOIN users u ON u.id = x.student_id
WHERE x.active = 1 AND u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002')
AND NOT EXISTS (SELECT 1 FROM weekly_reports r WHERE r.student_id = x.student_id AND r.week_start = '2026-09-21');

INSERT INTO weekly_report_revisions (id, report_id, revision, content, submitted_at)
SELECT UUID(), r.id, 1, JSON_OBJECT('done_items', r.done_items, 'issues', r.issues, 'next_plan', r.next_plan, 'help_needed', r.help_needed), DATE_SUB(UTC_TIMESTAMP(), INTERVAL 7 DAY)
FROM weekly_reports r JOIN users u ON u.id = r.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND r.week_start = '2026-09-21' AND r.status = 'submitted'
AND NOT EXISTS (SELECT 1 FROM weekly_report_revisions rr WHERE rr.report_id = r.id AND rr.revision = 1);

INSERT INTO experiments (id, lab_id, owner_id, task_id, current_revision, status, visibility, version, created_at)
SELECT UUID(), t.lab_id, t.student_id, t.id, 1, 'draft', 'private', 1, UTC_TIMESTAMP()
FROM tasks t JOIN users u ON u.id = t.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND t.title = '演示·整理实验数据'
AND NOT EXISTS (SELECT 1 FROM experiments e JOIN experiment_revisions er ON er.experiment_id = e.id WHERE e.owner_id = t.student_id AND er.purpose = '演示·基线实验记录');

INSERT INTO experiment_revisions (id, experiment_id, revision, purpose, parameters_json, steps_json, results_text, reflection, file_ids, created_at)
SELECT UUID(), e.id, 1, '演示·基线实验记录', JSON_OBJECT('dataset', 'demo', 'seed', 42), JSON_ARRAY('整理数据', '运行基线模型', '记录指标'), '准确率待补充', '需要检查异常样本。', JSON_ARRAY(), UTC_TIMESTAMP()
FROM experiments e JOIN tasks t ON t.id = e.task_id JOIN users u ON u.id = e.owner_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND t.title = '演示·整理实验数据'
AND NOT EXISTS (SELECT 1 FROM experiment_revisions er WHERE er.experiment_id = e.id);

INSERT INTO notifications (id, recipient_id, business_key, type, target, read_at, closed_at, created_at)
SELECT UUID(), s.student_id, CONCAT('frontend-demo:stage:', s.id), 'stage_review', JSON_OBJECT('stage_id', s.id), NULL, NULL, UTC_TIMESTAMP()
FROM stages s JOIN users u ON u.id = s.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND s.title = '演示·实验验证'
AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.recipient_id = s.student_id AND n.business_key = CONCAT('frontend-demo:stage:', s.id));

INSERT INTO notifications (id, recipient_id, business_key, type, target, read_at, closed_at, created_at)
SELECT UUID(), t.student_id, CONCAT('frontend-demo:task:', t.id), 'task_reminder', JSON_OBJECT('task_id', t.id), DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 DAY), NULL, DATE_SUB(UTC_TIMESTAMP(), INTERVAL 3 DAY)
FROM tasks t JOIN users u ON u.id = t.student_id
WHERE u.username IN ('student_new', 'qa_student_1002', 'qa_ui_1002') AND t.title = '演示·补充对照实验'
AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.recipient_id = t.student_id AND n.business_key = CONCAT('frontend-demo:task:', t.id));

COMMIT;
