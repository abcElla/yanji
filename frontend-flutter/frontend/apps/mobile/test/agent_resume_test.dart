import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'package:yanji_mobile/src/research_assistant.dart';
import 'improvement_support.dart';

void main() {
  testWidgets('resume while initial read is pending still fetches fresh detail',
      (tester) async {
    final api = ApiClient('http://localhost');
    var reads = 0;
    RequestOptions? old;
    RequestInterceptorHandler? oldHandler;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path == '/agent-runs/live') {
        reads++;
        if (reads == 1) {
          old = r;
          oldHandler = h;
          return;
        }
        resolveData(r, h, {
          'id': 'live',
          'status': 'succeeded',
          'result': {'summary': '新状态'}
        });
      } else {
        resolveData(
            r,
            h,
            r.path.endsWith('/events')
                ? {'events': [], 'next_seq': 0}
                : {'proposals': []});
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: AgentRunScreen(runId: 'live'))));
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.text('正在加载运行'), findsOneWidget);
    expect(find.text('请返回对应功能重新分析。'), findsNothing);
    expect(find.text('运行状态：状态暂不支持'), findsNothing);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump(const Duration(milliseconds: 50));
    resolveData(old!, oldHandler!, {'id': 'live', 'status': 'queued'});
    await tester.pumpAndSettle();
    expect(reads, 2);
    expect(find.text('新状态'), findsOneWidget);
  });
  testWidgets('history fetches one page at a time using cursor',
      (tester) async {
    final api = ApiClient('http://localhost');
    final cursors = <dynamic>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      expect(r.method, 'GET');
      expect(r.path, '/agent-runs');
      expect(r.queryParameters['limit'], 20);
      expect(r.queryParameters['student_id'], 'student-real');
      cursors.add(r.queryParameters['cursor']);
      resolveData(r, h, {
        'runs': [
          {
            'id': '${cursors.length}',
            'kind': 'research_qa',
            'status': 'succeeded',
            'input': {'question': '第${cursors.length}页'}
          }
        ],
        'next_cursor': cursors.length == 1 ? 'page2' : null
      });
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const AgentHistoryScreen());
    expect(cursors, [null]);
    await tapText(tester, '加载更多');
    expect(cursors, [null, 'page2']);
    expect(find.textContaining('第1页'), findsOneWidget);
    expect(find.textContaining('第2页'), findsOneWidget);
  });
  testWidgets('changed selected student invalidates old history request',
      (tester) async {
    final api = ApiClient('http://localhost');
    RequestOptions? old;
    RequestInterceptorHandler? oldHandler;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.queryParameters['student_id'] == 'old-student') {
        old = r;
        oldHandler = h;
      } else {
        resolveData(r, h, {
          'runs': [
            {
              'id': 'new',
              'input': {'question': '新学生结果'}
            }
          ]
        });
      }
    }));
    final container = testContainer(api, mentor: true, selected: 'old-student');
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: AgentHistoryScreen())));
    await tester.pump(const Duration(milliseconds: 50));
    container.read(selectedStudentProvider.notifier).state = 'new-student';
    await tester.pump(const Duration(milliseconds: 50));
    resolveData(old!, oldHandler!, {
      'runs': [
        {
          'id': 'old',
          'input': {'question': '旧学生秘密'}
        }
      ]
    });
    await tester.pumpAndSettle();
    expect(find.textContaining('旧学生秘密'), findsNothing);
    expect(find.textContaining('新学生结果'), findsOneWidget);
  });
  testWidgets('background pauses polling; resume fetches detail again',
      (tester) async {
    final api = ApiClient('http://localhost');
    var reads = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path == '/agent-runs/live') {
        reads++;
        resolveData(r, h, {
          'id': 'live',
          'status': reads < 3 ? 'queued' : 'succeeded',
          'version': 1
        });
      } else {
        resolveData(
            r,
            h,
            r.path.endsWith('/events')
                ? {'events': [], 'next_seq': 0}
                : {'proposals': []});
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const AgentRunScreen(runId: 'live'));
    final initial = reads;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 8));
    expect(reads, initial);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump(const Duration(milliseconds: 100));
    expect(reads, initial + 1);
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 4));
    expect(reads, initial + 1);
  });
  testWidgets('logout invalidates pending detail without leaking old result',
      (tester) async {
    final api = ApiClient('http://localhost');
    RequestOptions? pending;
    RequestInterceptorHandler? handler;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      pending = r;
      handler = h;
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: const MaterialApp(home: AgentRunScreen(runId: 'saved'))));
    await tester.pump(const Duration(milliseconds: 50));
    (container.read(sessionProvider.notifier) as TestSession).clear();
    resolveData(pending!, handler!, {
      'id': 'saved',
      'status': 'succeeded',
      'result': {'summary': '旧账号秘密'}
    });
    await tester.pumpAndSettle();
    expect(find.text('旧账号秘密'), findsNothing);
    expect(find.textContaining('登录或访问范围已改变'), findsOneWidget);
  });
  testWidgets('404 shows inaccessible message and never creates run',
      (tester) async {
    final api = ApiClient('http://localhost');
    var posts = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') posts++;
      rejectData(r, h, 404, 'NOT_FOUND');
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const AgentRunScreen(runId: 'gone'));
    expect(find.textContaining('运行不存在或无权访问'), findsOneWidget);
    expect(posts, 0);
    await tapText(tester, '重试');
    expect(posts, 0);
  });
  testWidgets('opening saved run never creates another run', (tester) async {
    final api = ApiClient('http://localhost');
    final calls = <String>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      calls.add('${r.method} ${r.path}');
      final data = switch (r.path) {
        '/agent-runs/saved' => {
            'id': 'saved',
            'status': 'succeeded',
            'result': {'summary': '历史结果', 'outcome': 'ok'}
          },
        '/agent-runs/saved/events' => {'events': [], 'next_seq': 0},
        '/proposals' => {'proposals': []},
        _ => throw StateError('Unexpected ${r.path}'),
      };
      h.resolve(Response(
          requestOptions: r, statusCode: 200, data: {'code': 0, 'data': data}));
    }));
    await tester.pumpWidget(ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(api)],
        child: const MaterialApp(home: AgentRunScreen(runId: 'saved'))));
    await tester.pumpAndSettle();
    expect(find.text('历史结果'), findsOneWidget);
    expect(calls.any((c) => c.startsWith('POST')), false);
    expect(calls.first, 'GET /agent-runs/saved');
  });
}
