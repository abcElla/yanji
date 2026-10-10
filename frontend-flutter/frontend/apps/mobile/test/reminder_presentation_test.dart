import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/reminder_presentation.dart';

void main() {
  test('duplicate reminders merge by task and resolve readable titles', () {
    final rows = readableRisks({
      'tasks': [
        {'id': 'task-secret-id', 'title': '完成基线实验'}
      ],
      'risks': [
        {'task_id': 'task-secret-id', 'type': 'task_overdue'},
        {'task_id': 'task-secret-id', 'type': 'task_overdue'}
      ]
    });
    expect(rows, hasLength(1));
    expect(rows.single['title'], '完成基线实验');
  });
  test('unavailable task title never falls back to an internal identifier', () {
    expect(
        readableRisks({
          'risks': [
            {'task_id': 'private-uuid'}
          ]
        }).single['title'],
        isNot(contains('private-uuid')));
  });
}
