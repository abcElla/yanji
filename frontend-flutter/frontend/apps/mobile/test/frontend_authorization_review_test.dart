import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:yanji_mobile/src/ai_action.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';
import 'package:yanji_mobile/src/paper_reader.dart';
import 'package:yanji_mobile/src/theses.dart';
import 'improvement_support.dart';

class MutableReviewSession extends SessionController {
  MutableReviewSession(super.ref, Session session) {
    state = AsyncData(session);
  }
  void replace(Session session) {
    state = AsyncData(session);
  }
}

Session mentorSession({bool boundA = true}) => Session(
    user: const {'id': 'mentor-real'},
    roles: const ['mentor'],
    collaborationEnabled: true,
    memberships: const [
      {'lab_id': 'lab-real', 'role': 'mentor', 'status': 'active'}
    ],
    mentorships: [
      if (boundA) {'student_id': 'student-real', 'status': 'active'},
      {'student_id': 'student-b', 'status': 'active'}
    ]);
ProviderContainer mutableContainer(ApiClient api) =>
    ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(api),
      sessionProvider
          .overrideWith((ref) => MutableReviewSession(ref, mentorSession())),
      roleProvider.overrideWith((ref) => UserRole.mentor),
      selectedStudentProvider.overrideWith((ref) => 'student-real')
    ]);

Map<String, dynamic> note() => {
      'id': 'note',
      'paper_id': 'paper',
      'status': 'draft',
      'version': 2,
      'claims': ['旧笔记秘密'],
      'questions': ['旧问题秘密'],
      'source_refs': [
        {
          'source_id': 'source',
          'source_revision': 1,
          'locator': {'kind': 'pdf_page', 'page': 1},
          'quote': '旧来源秘密'
        }
      ]
    };

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  test('application-envelope source denial is treated as lost access',
      () async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) =>
            h.resolve(Response(requestOptions: r, statusCode: 200, data: {
              'code': 40300,
              'message': '无权查看原文',
              'data': {'error_code': 'SOURCE_FORBIDDEN'}
            }))));
    await expectLater(
        verifyReadableRunSources(api, {
          'source_id': 'source',
          'source_revision': 1,
          'locator': {'kind': 'pdf_page', 'page': 1}
        }),
        throwsA(isA<ApiFailure>()
            .having((e) => e.code, 'code', 'RUN_SOURCE_FORBIDDEN')));
  });
  testWidgets('AI delayed create cannot open a run after authorization changes',
      (tester) async {
    final pending = Completer<Map<String, dynamic>>();
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) async => resolveData(r, h, await pending.future)));
    final container = mutableContainer(api);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/', routes: [
      GoRoute(
          path: '/',
          builder: (_, s) => const Scaffold(
              body: AiActionPanel(title: '风险简报', kind: 'risk_brief'))),
      GoRoute(
          path: '/agent',
          builder: (_, s) => const Scaffold(body: Text('迟到的旧运行')))
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('开始分析'));
    await tester.pump();
    (container.read(sessionProvider.notifier) as MutableReviewSession)
        .replace(mentorSession(boundA: false));
    await tester.pump();
    pending.complete({'run_id': 'stale-run', 'status': 'queued'});
    await tester.pumpAndSettle();
    expect(find.text('迟到的旧运行'), findsNothing);
    expect(find.text('尚未生成'), findsOneWidget);
  });
  for (final status in [403, 404]) {
    testWidgets('thesis feedback $status clears the already opened draft',
        (tester) async {
      final api = ApiClient('http://localhost');
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        if (r.method == 'POST') {
          rejectData(r, h, status, '无权访问');
        } else {
          resolveData(r, h, {
            'id': 'thesis',
            'student_id': 'student-real',
            'title': '旧稿秘密',
            'version': 1,
            'revisions': [
              {
                'id': 'r1',
                'revision_number': 1,
                'paper_id': 'paper',
                'file_id': 'file',
                'change_summary': '旧修改说明',
                'ingest_status': 'ready',
                'feedback': []
              }
            ]
          });
        }
      }));
      final container = testContainer(api, mentor: true);
      addTearDown(container.dispose);
      await mountPage(
          tester, container, const ThesisDetailScreen(thesisId: 'thesis'));
      await tester.enterText(
          find.byKey(const ValueKey('thesis-feedback')), '待提交反馈');
      await tapText(tester, '提交导师反馈');
      expect(find.text('旧稿秘密'), findsNothing);
      expect(find.text('旧修改说明'), findsNothing);
      expect(find.textContaining('访问范围'), findsOneWidget);
    });
    testWidgets('thesis list refresh $status removes cached archive titles',
        (tester) async {
      var denied = false;
      final api = ApiClient('http://localhost');
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        if (denied) {
          rejectData(r, h, status, '无权访问');
        } else {
          resolveData(r, h, {
            'items': [
              {'id': 'thesis', 'title': '旧档案秘密', 'version': 1}
            ]
          });
        }
      }));
      final container = testContainer(api);
      addTearDown(container.dispose);
      await mountPage(tester, container, const ThesesScreen());
      expect(find.text('旧档案秘密'), findsOneWidget);
      denied = true;
      final refresh = tester
          .state<RefreshIndicatorState>(find.byType(RefreshIndicator))
          .show();
      await tester.pumpAndSettle();
      await refresh;
      expect(find.text('旧档案秘密'), findsNothing);
      expect(find.textContaining('无权访问'), findsOneWidget);
    });
    testWidgets('paper refresh $status clears old title and notes',
        (tester) async {
      var denied = false;
      final api = ApiClient('http://localhost');
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        if (r.method == 'PATCH') {
          rejectData(r, h, 409, '版本已更新');
        } else if (denied) {
          rejectData(r, h, status, '无权访问');
        } else if (r.path == '/notes') {
          resolveData(r, h, {
            'items': [note()]
          });
        } else {
          resolveData(r, h,
              {'id': 'paper', 'title': '旧文献秘密', 'ingest_status': 'ready'});
        }
      }));
      final container = testContainer(api);
      addTearDown(container.dispose);
      await mountPage(
          tester, container, const PaperReaderScreen(paperId: 'paper'));
      expect(find.text('旧文献秘密'), findsOneWidget);
      denied = true;
      await tester.tap(find.byTooltip('刷新资料'));
      await tester.pumpAndSettle();
      expect(find.text('旧文献秘密'), findsNothing);
      expect(find.text('旧笔记秘密'), findsNothing);
    });
    testWidgets(
        'note refresh $status clears controllers and previous source quotes',
        (tester) async {
      var denied = false;
      final api = ApiClient('http://localhost');
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        if (r.method == 'PATCH') {
          rejectData(r, h, 409, '版本已更新');
        } else if (denied) {
          rejectData(r, h, status, '无权访问');
        } else {
          resolveData(r, h, note());
        }
      }));
      final container = testContainer(api);
      addTearDown(container.dispose);
      await mountPage(tester, container,
          const ReadingNoteScreen(paperId: 'paper', noteId: 'note'));
      await tapText(tester, '查看原文依据');
      expect(find.textContaining('旧来源秘密'), findsOneWidget);
      await tapText(tester, '保存阅读笔记');
      denied = true;
      await tapText(tester, '刷新最新笔记');
      expect(find.textContaining('旧来源秘密'), findsNothing);
      expect(find.text('旧笔记秘密'), findsNothing);
      expect(find.byKey(const ValueKey('note-claims')), findsNothing);
    });
  }
  for (final page in ['thesis', 'paper', 'note']) {
    testWidgets(
        '$page delayed response cannot return after same-account A binding revocation',
        (tester) async {
      final pending = Completer<Map<String, dynamic>>();
      final api = ApiClient('http://localhost');
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) async {
        if (r.path == '/notes') {
          resolveData(r, h, {
            'items': [note()]
          });
        } else {
          resolveData(r, h, await pending.future);
        }
      }));
      final container = mutableContainer(api);
      addTearDown(container.dispose);
      final widget = page == 'thesis'
          ? const ThesisDetailScreen(thesisId: 'thesis')
          : page == 'paper'
              ? const PaperReaderScreen(paperId: 'paper')
              : const ReadingNoteScreen(paperId: 'paper', noteId: 'note');
      await tester.pumpWidget(UncontrolledProviderScope(
          container: container, child: MaterialApp(home: widget)));
      await tester.pump();
      (container.read(sessionProvider.notifier) as MutableReviewSession)
          .replace(mentorSession(boundA: false));
      await tester.pump();
      pending.complete(page == 'thesis'
          ? {
              'id': 'thesis',
              'student_id': 'student-real',
              'title': '旧档案秘密',
              'version': 1,
              'revisions': []
            }
          : page == 'paper'
              ? {'id': 'paper', 'title': '旧文献秘密', 'ingest_status': 'ready'}
              : note());
      await tester.pumpAndSettle();
      expect(find.textContaining('秘密'), findsNothing);
      expect(find.textContaining('访问范围已改变'), findsOneWidget);
    });
  }
  testWidgets('dual-role student creates thesis in active student membership',
      (tester) async {
    final writes = <RequestOptions>[];
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        writes.add(r);
        resolveData(r, h, {'id': 'thesis'});
      } else {
        resolveData(r, h, {'items': []});
      }
    }));
    final container = testContainer(api, memberships: const [
      {'lab_id': 'mentor-lab', 'role': 'mentor', 'status': 'active'},
      {'lab_id': 'stale-lab', 'role': 'student', 'status': 'removed'},
      {'lab_id': 'student-lab', 'role': 'student', 'status': 'active'}
    ]);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/', routes: [
      GoRoute(path: '/', builder: (_, s) => const ThesesScreen()),
      GoRoute(
          path: '/theses/:id',
          builder: (_, s) => const Scaffold(body: Text('新档案')))
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '论文题目');
    await tapText(tester, '创建档案');
    expect(writes.single.data, {'title': '论文题目', 'lab_id': 'student-lab'});
  });
  testWidgets(
      'actual me membership shape without status selects the student lab',
      (tester) async {
    final writes = <RequestOptions>[];
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        writes.add(r);
        resolveData(r, h, {'id': 'thesis'});
      } else {
        resolveData(r, h, {'items': []});
      }
    }));
    final container = testContainer(api, memberships: const [
      {'lab_id': 'mentor-lab', 'role': 'mentor'},
      {'lab_id': 'stale-lab', 'role': 'student', 'status': 'removed'},
      {'lab_id': 'student-lab', 'role': 'student'}
    ]);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/', routes: [
      GoRoute(path: '/', builder: (_, s) => const ThesesScreen()),
      GoRoute(
          path: '/theses/:id',
          builder: (_, s) => const Scaffold(body: Text('新档案')))
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '论文题目');
    await tapText(tester, '创建档案');
    expect(writes.single.data, {'title': '论文题目', 'lab_id': 'student-lab'});
  });
  testWidgets(
      'AI refuses old results after source access is revoked during refresh',
      (tester) async {
    var denied = false;
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        resolveData(r, h, {'run_id': 'run-real', 'status': 'queued'});
      } else if (r.path.startsWith('/sources/')) {
        if (denied) {
          rejectData(r, h, 403, '无权查看原文');
        } else {
          resolveData(r, h, {});
        }
      } else {
        resolveData(r, h, {
          'id': 'run-real',
          'status': 'succeeded',
          'result': {
            'summary': '旧AI结论秘密',
            'claims': [
              {
                'text': '旧AI依据秘密',
                'source_refs': [
                  {
                    'source_id': 'source',
                    'source_revision': 1,
                    'locator': {'kind': 'pdf_page', 'page': 1},
                    'quote': '旧AI引用秘密'
                  }
                ]
              }
            ]
          }
        });
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/', routes: [
      GoRoute(
          path: '/',
          builder: (_, s) => const Scaffold(
              body: SingleChildScrollView(
                  child: AiActionPanel(title: '测试分析', kind: 'research_qa')))),
      GoRoute(
          path: '/agent',
          builder: (c, s) => Scaffold(
              body: TextButton(
                  onPressed: () => c.pop(), child: const Text('返回'))))
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    await tapText(tester, '开始分析');
    await tapText(tester, '返回');
    await tapText(tester, '展开分析结果与依据');
    expect(find.text('旧AI结论秘密'), findsOneWidget);
    denied = true;
    await tapText(tester, '查看分析与待确认建议');
    await tapText(tester, '返回');
    expect(find.textContaining('旧AI'), findsNothing);
  });
  testWidgets('switching historical revision resets accepted feedback decision',
      (tester) async {
    final writes = <RequestOptions>[];
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        writes.add(r);
        resolveData(r, h, {'id': 'feedback'});
      } else {
        resolveData(r, h, {
          'id': 'thesis',
          'student_id': 'student-real',
          'title': '论文',
          'version': 3,
          'revisions': [
            {
              'id': 'new',
              'revision_number': 2,
              'paper_id': 'paper2',
              'file_id': 'file2',
              'ingest_status': 'ready',
              'change_summary': '新稿',
              'feedback': []
            },
            {
              'id': 'old',
              'revision_number': 1,
              'paper_id': 'paper1',
              'file_id': 'file1',
              'ingest_status': 'ready',
              'change_summary': '旧稿',
              'feedback': []
            }
          ]
        });
      }
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(
        tester, container, const ThesisDetailScreen(thesisId: 'thesis'));
    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('通过').last);
    await tester.pumpAndSettle();
    await tapText(tester, '第 1 稿');
    await tester.enterText(
        find.byKey(const ValueKey('thesis-feedback')), '旧稿还需修改');
    await tapText(tester, '提交导师反馈');
    expect(writes.single.path, '/theses/thesis/revisions/old/feedback');
    expect(writes.single.data['decision'], 'changes_requested');
  });
}
