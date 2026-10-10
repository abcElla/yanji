import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'package:yanji_mobile/src/data.dart';
import 'improvement_support.dart';

void main() {
  for (final mentor in [false, true]) {
    testWidgets('work refresh fetches remote changes in both lists ($mentor)',
        (tester) async {
      final api = ApiClient('http://localhost');
      var taskReads = 0, reportReads = 0;
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        if (r.path == '/tasks') {
          taskReads++;
          resolveData(r, h, {
            'tasks': [
              {
                'id': 'task',
                'title': taskReads == 1 ? '旧任务' : '远端新任务',
                'type': 'experiment',
                'status': 'submitted',
                'version': taskReads
              }
            ],
            'next_cursor': null
          });
        } else if (r.path == '/weekly-reports') {
          reportReads++;
          resolveData(r, h, {
            'reports': [
              {
                'id': 'report',
                'week_start': '2026-10-05',
                'status': reportReads == 1 ? 'submitted' : 'reviewed'
              }
            ],
            'next_cursor': null
          });
        } else {
          resolveData(r, h, {
            'students': [
              {'id': 'student-real', 'display_name': '真实学生'}
            ],
            'next_cursor': null
          });
        }
      }));
      final container = testContainer(api, mentor: mentor);
      addTearDown(container.dispose);
      await mountPage(tester, container, const Scaffold(body: WorkScreen()));
      expect(find.text('旧任务'), findsOneWidget);
      await tapText(tester, '周报');
      expect(find.text('待导师审阅'), findsOneWidget);
      await tester.drag(find.byType(ListView).first, const Offset(0, 1100));
      await tester.pumpAndSettle();
      expect(find.text('已阅'), findsOneWidget);
      await tapText(tester, '任务');
      expect(find.text('远端新任务'), findsOneWidget);
      expect(taskReads, greaterThan(1));
      expect(reportReads, greaterThan(1));
    });
  }
}
