-- Optional historical attribution repair. Do not guess creators from student_id.
-- Only a single unambiguous creation actor can repair the stored creator.
-- Status and historical events are preserved; version changes invalidate old business baselines.
START TRANSACTION;
UPDATE tasks t
JOIN (
  SELECT task_id, MIN(actor_id) AS creator_id
  FROM task_events
  WHERE from_status = '' AND to_status = 'todo' AND actor_id IS NOT NULL
  GROUP BY task_id
  HAVING COUNT(DISTINCT actor_id) = 1
) e ON e.task_id = t.id
JOIN users u ON u.id = e.creator_id
SET t.created_by = e.creator_id, t.version = t.version + 1
WHERE t.created_by IS NULL OR t.created_by <> e.creator_id;
COMMIT;
