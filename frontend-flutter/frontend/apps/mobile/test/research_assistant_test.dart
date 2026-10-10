import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/models.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/research_assistant.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'improvement_support.dart';

void main() {
  testWidgets(
      'history refreshes a completed run when returning from its detail',
      (tester) async {
    final api = ApiClient('http://localhost');
    var finished = false;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      expect(r.method, 'GET');
      resolveData(r, h, {
        'runs': [
          {
            'id': 'saved-id',
            'kind': 'research_qa',
            'status': finished ? 'succeeded' : 'queued',
            'created_at': '2026-10-07T07:00:00Z',
            'input': {}
          }
        ]
      });
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/history', routes: [
      GoRoute(path: '/history', builder: (_, s) => const AgentHistoryScreen()),
      GoRoute(
          path: '/agent',
          builder: (context, s) => Scaffold(
              body: TextButton(
                  onPressed: () {
                    finished = true;
                    context.pop();
                  },
                  child: const Text('完成后返回')))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(ListTile).first);
    await tester.pumpAndSettle();
    await tapText(tester, '完成后返回');
    expect(find.textContaining('分析已完成'), findsOneWidget);
    expect(find.textContaining('等待处理'), findsNothing);
  });
  testWidgets(
      'returning from a run refreshes the recent status without creating another run',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    var finished = false;
    var creates = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        creates++;
        resolveData(r, h, {'run_id': 'saved-id', 'status': 'queued'});
      } else if (r.path == '/agent-runs') {
        resolveData(r, h, {
          'runs': [
            {
              'id': 'saved-id',
              'kind': 'research_qa',
              'status': finished ? 'succeeded' : 'queued',
              'created_at': '2026-10-07T07:00:00Z',
              'input': {'question': 'test'}
            }
          ]
        });
      } else {
        resolveData(r, h, {'stages': []});
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/assistant', routes: [
      GoRoute(
          path: '/assistant',
          builder: (_, s) => const ResearchAssistantScreen()),
      GoRoute(
          path: '/agent',
          builder: (context, s) => Scaffold(
              body: TextButton(
                  onPressed: () {
                    finished = true;
                    context.pop();
                  },
                  child: const Text('完成后返回')))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    await tapText(tester, '分析下一步');
    await tapText(tester, '完成后返回');
    await tester.scrollUntilVisible(find.text('最近运行'), 200,
        scrollable: find.byType(Scrollable).first);
    await tester.pumpAndSettle();
    expect(find.textContaining('分析已完成'), findsOneWidget);
    expect(find.textContaining('等待处理'), findsNothing);
    expect(creates, 1);
  });
  testWidgets('mentor selected stage displays goal and sends exact plan scope',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    Map? posted;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        posted = r.data;
        rejectData(r, h, 503, '暂不可用');
      } else if (r.path == '/stages') {
        expect(r.queryParameters['student_id'], 'student-real');
        resolveData(r, h, {
          'stages': [
            {'id': 'stage-real', 'title': '验证阶段', 'goal': '核验实验结果'}
          ]
        });
      } else if (r.path == '/students') {
        resolveData(r, h, {
          'students': [
            {'id': 'student-real', 'display_name': '学生甲'}
          ]
        });
      } else {
        resolveData(r, h, {'runs': []});
      }
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(
        tester,
        container,
        const ResearchAssistantScreen(
            stageId: 'stage-real', question: '制定下一步'));
    expect(find.text('正在帮助：学生甲'), findsOneWidget);
    expect(find.text('阶段目标：核验实验结果'), findsOneWidget);
    await tapText(tester, '生成阶段任务建议');
    expect(posted, {
      'kind': 'stage_plan',
      'subject': {'student_id': 'student-real', 'stage_id': 'stage-real'},
      'input': {'question': '制定下一步'},
      'client_context': {}
    });
  });
  testWidgets('onboarding prevents both analysis and business reads',
      (tester) async {
    final api = ApiClient('http://localhost');
    var requests = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      requests++;
      resolveData(r, h, {});
    }));
    final container = testContainer(api, enabled: false);
    addTearDown(container.dispose);
    await mountPage(tester, container, const ResearchAssistantScreen());
    expect(requests, 0);
    expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '分析下一步'))
            .onPressed,
        isNull);
    expect(find.text('加入实验室并完成绑定后，可使用科研下一步。'), findsOneWidget);
  });
  testWidgets('student only sees analysis and empty questions never submit',
      (tester) async {
    final api = ApiClient('http://localhost');
    var creates = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') creates++;
      resolveData(
          r, h, r.path == '/agent-runs' ? {'runs': []} : {'stages': []});
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const ResearchAssistantScreen());
    expect(find.text('生成阶段任务建议'), findsNothing);
    await tester.enterText(find.byType(TextField), '   ');
    await tapText(tester, '分析下一步');
    expect(creates, 0);
    expect(find.textContaining('请输入科研问题'), findsOneWidget);
    expect(find.textContaining('Invalid argument'), findsNothing);
  });
  testWidgets('mentor must select student and stage before requesting plan',
      (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) => resolveData(
            r, h, r.path == '/students' ? {'students': []} : {'stages': []})));
    final container = testContainer(api, mentor: true, selected: null);
    addTearDown(container.dispose);
    await mountPage(tester, container, const ResearchAssistantScreen());
    expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '分析下一步'))
            .onPressed,
        isNull);
    expect(
        tester
            .widget<OutlinedButton>(
                find.widgetWithText(OutlinedButton, '生成阶段任务建议'))
            .onPressed,
        isNull);
    container.read(selectedStudentProvider.notifier).state = 'student-real';
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '分析下一步'))
            .onPressed,
        isNotNull);
    expect(
        tester
            .widget<OutlinedButton>(
                find.widgetWithText(OutlinedButton, '生成阶段任务建议'))
            .onPressed,
        isNull);
  });
  testWidgets(
      'one click creates exactly one idempotent run and navigates to saved id',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    var creates = 0;
    RequestOptions? request;
    RequestInterceptorHandler? pending;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path == '/agent-runs' && r.method == 'POST') {
        creates++;
        request = r;
        pending = h;
      } else {
        resolveData(
            r, h, r.path == '/agent-runs' ? {'runs': []} : {'stages': []});
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/assistant', routes: [
      GoRoute(
          path: '/assistant',
          builder: (_, state) => const ResearchAssistantScreen()),
      GoRoute(
          path: '/agent',
          builder: (_, state) => Scaffold(
              body: Text('恢复 ${state.uri.queryParameters['run_id']}'))),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('分析下一步'));
    await tester.tap(find.text('分析下一步'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(creates, 1);
    expect(request!.headers['Idempotency-Key'], isNotEmpty);
    expect(request!.data['subject'], {'student_id': 'student-real'});
    expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '分析下一步'))
            .onPressed,
        isNull);
    resolveData(request!, pending!, {'run_id': 'saved-id', 'status': 'queued'});
    await tester.pumpAndSettle();
    expect(find.text('恢复 saved-id'), findsOneWidget);
    expect(creates, 1);
  });
  testWidgets('failed creation preserves user question', (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        rejectData(r, h, 503, '服务暂不可用');
      } else {
        resolveData(
            r, h, r.path == '/agent-runs' ? {'runs': []} : {'stages': []});
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const ResearchAssistantScreen());
    await tester.enterText(find.byType(TextField), '保留这个科研问题');
    await tapText(tester, '分析下一步');
    expect(find.text('保留这个科研问题'), findsOneWidget);
    expect(find.textContaining('服务暂不可用'), findsOneWidget);
  });
  test('analysis uses actual student, trims question and omits absent stage',
      () {
    final body = assistantRequest(
        role: UserRole.student, studentId: 'actual-user', question: ' 问题 ');
    expect(body['subject'], {'student_id': 'actual-user'});
    expect(body['input'], {'question': '问题'});
    expect(body['kind'], 'research_qa');
    expect(body['client_context'], isEmpty);
  });
  test('stage planning requires mentor, selected student and stage', () {
    expect(
        () => assistantRequest(
            role: UserRole.student,
            studentId: 'u',
            stageId: 's',
            question: 'q',
            plan: true),
        throwsArgumentError);
    expect(() => assistantRequest(role: UserRole.mentor, question: 'q'),
        throwsArgumentError);
    expect(
        () => assistantRequest(
            role: UserRole.mentor, studentId: 'u', question: 'q', plan: true),
        throwsArgumentError);
    expect(
        () => assistantRequest(
            role: UserRole.student, studentId: 'u', question: ' '),
        throwsArgumentError);
    expect(
        assistantRequest(
            role: UserRole.mentor,
            studentId: 'u',
            stageId: 's',
            question: 'q',
            plan: true)['kind'],
        'stage_plan');
  });
}
