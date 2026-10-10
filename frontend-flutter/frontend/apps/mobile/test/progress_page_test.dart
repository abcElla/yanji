import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/progress_page.dart';
import 'improvement_support.dart';

void main() {
  testWidgets('student cannot submit an empty stage for acceptance',
      (tester) async {
    final api = ApiClient('http://localhost');
    var writes = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') writes++;
      resolveData(r, h, {
        'stage': {
          'id': 'stage',
          'title': '开题准备',
          'goal': '完成文献比较',
          'status': 'active',
          'version': 3
        },
        'tasks': [],
        'requirements': []
      });
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const StageDetailPage(stageId: 'stage'));
    await tapText(tester, '提交阶段验收');
    expect(writes, 0);
    expect(find.text('请先完成本阶段的任务，再提交验收。'), findsOneWidget);
  });
  testWidgets(
      'mentor connects guidance to an existing stage with its current version',
      (tester) async {
    final api = ApiClient('http://localhost');
    Map<String, dynamic>? saved;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path.endsWith('/apply')) saved = Map<String, dynamic>.from(r.data);
      resolveData(
          r,
          h,
          r.path == '/guidance'
              ? {
                  'items': [
                    {'id': 'guide', 'title': '开题前检查'}
                  ]
                }
              : {
                  'stage': {
                    'id': 'stage',
                    'title': '开题准备',
                    'goal': '说明研究问题',
                    'status': 'active',
                    'version': 4
                  },
                  'tasks': [],
                  'requirements': []
                });
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(tester, container, const StageDetailPage(stageId: 'stage'));
    await tapText(tester, '设置科研指导');
    await tapText(tester, '开题前检查');
    expect(saved, {'stage_id': 'stage', 'expected_version': 4});
  });
  testWidgets('student sees guidance but cannot change stage requirements',
      (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) => resolveData(r, h, {
              'stage': {
                'id': 'stage',
                'title': '开题准备',
                'goal': '说明研究问题',
                'status': 'active',
                'version': 2
              },
              'requirements': ['文献比较'],
              'tasks': []
            })));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const StageDetailPage(stageId: 'stage'));
    expect(find.text('文献比较'), findsOneWidget);
    expect(find.text('设置科研指导'), findsNothing);
  });
  testWidgets('loaded stage clears immediately after logout', (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) => resolveData(r, h, {
              'stage': {'id': 'stage', 'title': '旧实验室阶段', 'status': 'active'},
              'tasks': []
            })));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const StageDetailPage(stageId: 'stage'));
    expect(find.text('旧实验室阶段'), findsOneWidget);
    (container.read(sessionProvider.notifier) as TestSession).clear();
    await tester.pumpAndSettle();
    expect(find.text('旧实验室阶段'), findsNothing);
    expect(find.text('提交阶段验收'), findsNothing);
  });
  testWidgets(
      'old guidance write cannot reload a stage after student scope changes',
      (tester) async {
    final api = ApiClient('http://localhost');
    final pending = Completer<void>();
    int stageReads = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) async {
      if (r.path.endsWith('/apply')) {
        await pending.future;
        resolveData(r, h, {'id': 'stage'});
      } else if (r.path == '/guidance') {
        resolveData(r, h, {
          'items': [
            {'id': 'guide', 'title': '开题指导'}
          ]
        });
      } else {
        stageReads++;
        resolveData(r, h, {
          'stage': {
            'id': 'stage',
            'title': '学生A阶段',
            'status': 'active',
            'version': 1
          },
          'tasks': []
        });
      }
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(tester, container, const StageDetailPage(stageId: 'stage'));
    await tapText(tester, '设置科研指导');
    await tester.tap(find.text('开题指导'));
    await tester.pumpAndSettle();
    container.read(selectedStudentProvider.notifier).state = 'student-b';
    await tester.pump();
    pending.complete();
    await tester.pumpAndSettle();
    expect(stageReads, 1);
    expect(find.text('学生A阶段'), findsNothing);
  });
}
