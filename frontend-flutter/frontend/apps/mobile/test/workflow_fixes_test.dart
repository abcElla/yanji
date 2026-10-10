import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'package:yanji_mobile/src/data.dart';
import 'improvement_support.dart';

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  testWidgets(
      'updating task materials returns to analysis before explicit reanalysis',
      (tester) async {
    tester.view.physicalSize = const Size(900, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = ApiClient('http://localhost');
    var confirmed = false;
    var writes = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path == '/agent-runs/run') {
        resolveData(r, h, {
          'id': 'run',
          'kind': 'research_qa',
          'status': 'succeeded',
          'subject': {'student_id': 'student-real', 'task_id': 'task-real'},
          'input': {'question': '分析任务安排'},
          'result': {'outcome': 'needs_input', 'summary': '请补充记录', 'claims': []}
        });
      } else if (r.path == '/tasks/task-real') {
        resolveData(r, h, {
          'id': 'task-real',
          'title': '整理计算',
          'type': 'other',
          'status': 'todo',
          'version': 1
        });
      } else if (r.path == '/experiments' && r.method == 'POST') {
        writes++;
        resolveData(r, h, {'id': 'record', 'version': 1});
      } else if (r.path == '/experiments/record/confirm') {
        confirmed = true;
        resolveData(r, h, {'id': 'record', 'version': 2});
      } else if (r.path == '/experiments/record') {
        resolveData(r, h, {
          'id': 'record',
          'task_id': 'task-real',
          'purpose': '核对计算',
          'results': '已核对参数',
          'status': confirmed ? 'confirmed' : 'draft',
          'version': confirmed ? 2 : 1,
          'visibility': 'private'
        });
      } else {
        resolveData(r, h, {
          'items': [],
          'events': [],
          'proposals': [],
          'experiments': [],
          'tasks': []
        });
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, s) => const AgentRunScreen(runId: 'run')),
      GoRoute(
          path: '/assistant',
          builder: (_, s) => const Scaffold(body: Text('一起理清科研下一步'))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    expect(find.text('更新材料'), findsOneWidget);
    expect(find.text('重新分析'), findsOneWidget);
    await tapText(tester, '更新材料');
    await tapText(tester, '修改或添加材料');
    await tester.enterText(find.byType(TextField).at(0), '核对计算');
    await tester.enterText(find.byType(TextField).at(3), '已核对参数');
    await tapText(tester, '保存并确认材料');
    expect(writes, 1);
    expect(confirmed, isTrue);
    expect(find.text('继续更新'), findsOneWidget);
    expect(find.text('一起理清科研下一步'), findsNothing);
    await tapText(tester, '继续更新');
    expect(find.text('任务详情'), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tapText(tester, '重新分析');
    expect(find.text('一起理清科研下一步'), findsOneWidget);
  });
}
