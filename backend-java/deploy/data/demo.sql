-- Public demonstration data, not a dump of real users or research materials.
-- Both accounts use YanjiDemo2026!; change the passwords before regular use.
SET time_zone = '+00:00';
START TRANSACTION;
INSERT IGNORE INTO users(id,username,password_hash,display_name,account_role,status,created_at) VALUES
('d1000000-0000-0000-0000-000000000001','demo_mentor','$argon2id$v=19$m=65536,t=3,p=4$lufImI4za++9Sbq2xNh6iQ$T/SqSeY/2KKz8+k5MeVCpEmPqgAeFp9t8HPruMzx+Qk','演示导师','mentor','active',UTC_TIMESTAMP()),
('d1000000-0000-0000-0000-000000000002','demo_student','$argon2id$v=19$m=65536,t=3,p=4$lufImI4za++9Sbq2xNh6iQ$T/SqSeY/2KKz8+k5MeVCpEmPqgAeFp9t8HPruMzx+Qk','演示学生','student','active',UTC_TIMESTAMP());
INSERT IGNORE INTO labs(id,name,status) VALUES ('d2000000-0000-0000-0000-000000000001','研迹演示课题组','active');
INSERT IGNORE INTO memberships(id,lab_id,user_id,role,active) VALUES
('d3000000-0000-0000-0000-000000000001','d2000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000001','mentor',1),
('d3000000-0000-0000-0000-000000000002','d2000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000002','student',1);
INSERT IGNORE INTO mentorships(id,lab_id,mentor_id,student_id,active) VALUES
('d4000000-0000-0000-0000-000000000001','d2000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000002',1);
INSERT IGNORE INTO stages(id,lab_id,student_id,title,goal,due_at,rule_set_id,status,version,created_at) VALUES
('d5000000-0000-0000-0000-000000000001','d2000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000002','演示 文献调研','整理研究背景与三篇代表性论文',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 14 DAY),NULL,'active',1,UTC_TIMESTAMP());
INSERT IGNORE INTO tasks(id,lab_id,student_id,stage_id,title,description,type,due_at,status,priority,acceptance_criteria,dependency_ids,origin,created_by,version,created_at) VALUES
('d6000000-0000-0000-0000-000000000001','d2000000-0000-0000-0000-000000000001','d1000000-0000-0000-0000-000000000002','d5000000-0000-0000-0000-000000000001','演示 阅读相关论文','阅读三篇论文并整理方法与实验设置','reading',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 DAY),'todo',2,JSON_ARRAY('列出论文题目','总结主要方法'),JSON_ARRAY(),'human','d1000000-0000-0000-0000-000000000001',1,UTC_TIMESTAMP());
COMMIT;
