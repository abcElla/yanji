import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'package:yanji_mobile/src/widgets.dart';

void main() {
  test(
      'notification list supports the deployed notifications envelope across pages',
      () async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      final second = r.queryParameters['cursor'] == 'page-2';
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 0,
        'data': {
          'notifications': [
            {'id': second ? 'n2' : 'n1'}
          ],
          'next_cursor': second ? null : 'page-2'
        },
        'message': 'success'
      }));
    }));
    expect(
        (await api.list('/notifications')).map((n) => n['id']), ['n1', 'n2']);
  });
  testWidgets('reschedule preview identifies affected task and new due date',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: ProposalContent({
      'payload': {
        'items': [
          {'task_id': 'task-to-change', 'due_at': '2026-10-09T12:00:00+08:00'}
        ]
      },
      'source_refs': [],
    }, taskTitles: {
      'task-to-change': '比较垃圾分类识别方法'
    }))));
    expect(find.text('比较垃圾分类识别方法'), findsOneWidget);
    expect(find.text('task-to-change'), findsNothing);
    expect(find.textContaining('2026-10-09 12:00'), findsOneWidget);
  });
  testWidgets('failed AI retry creates a new run and keeps original input',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    var retried = false;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      final data = switch (r.path) {
        '/agent-runs' => {'run_id': 'old', 'status': 'queued'},
        '/agent-runs/old' => {
            'id': 'old',
            'status': 'failed',
            'error': {'message': '模型超时'}
          },
        '/agent-runs/old/retry' => {
            'run_id': 'new',
            'status': 'queued',
            'retry_of': 'old'
          },
        '/agent-runs/new' => {'id': 'new', 'status': 'succeeded'},
        '/agent-runs/old/events' || '/agent-runs/new/events' => {
            'events': [],
            'next_seq': 0
          },
        '/proposals' => {'proposals': []},
        _ => throw StateError(r.path),
      };
      if (r.path.endsWith('/retry')) retried = true;
      h.resolve(Response(requestOptions: r, data: {'code': 0, 'data': data}));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      apiClientProvider.overrideWithValue(api),
      agentInputProvider.overrideWith((ref) => {
            'kind': 'weekly_report',
            'subject': {'student_id': 'u'},
            'input': {'week_start': '2026-10-05'}
          }),
    ], child: const MaterialApp(home: AgentRunScreen())));
    await tester.pumpAndSettle();
    expect(find.text('重新运行'), findsOneWidget);
    await tester.tap(find.text('重新运行'));
    await tester.pumpAndSettle();
    expect(retried, true);
    expect(find.text('运行状态：分析已完成'), findsOneWidget);
  });
  testWidgets(
      'student corrects existing AI report item without losing evidence',
      (tester) async {
    final report = <String, dynamic>{
      'id': 'r',
      'version': 3,
      'status': 'changes_requested',
      'week_start': '2026-10-05',
      'done_items': [
        {
          'id': 'i',
          'text': '错误结论',
          'origin': 'agent',
          'source_refs': [
            {'source_id': 's', 'source_revision': 1}
          ]
        }
      ],
      'issues': [
        {'id': 'other', 'text': '保持问题', 'origin': 'human', 'source_refs': []}
      ],
      'next_plan': [],
      'help_needed': [],
    };
    Map<String, dynamic>? saved;
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      saved = Map<String, dynamic>.from(r.data as Map);
      h.resolve(Response(requestOptions: r, data: {'code': 0, 'data': report}));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      selectedReportProvider.overrideWith((ref) => 'r'),
      roleProvider.overrideWith((ref) => UserRole.student),
      apiClientProvider.overrideWithValue(api),
      reportProvider.overrideWith((ref, id) async => report),
    ], child: const MaterialApp(home: WeeklyReportScreen())));
    await tester.pumpAndSettle();
    expect(find.byTooltip('修改条目'), findsWidgets);
    await tester.tap(find.byTooltip('修改条目').first);
    await tester.pumpAndSettle();
    expect(tester.widget<TextField>(find.byType(TextField)).controller!.text,
        '错误结论');
    await tester.enterText(find.byType(TextField), '核对后的结论');
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect(saved!['expected_version'], 3);
    expect(saved!['issues'], report['issues']);
    final item = (saved!['done_items'] as List).single as Map;
    expect(item['text'], '核对后的结论');
    expect(item['id'], 'i');
    expect(item['origin'], 'human_edited');
    expect(item['source_refs'],
        (report['done_items'] as List).single['source_refs']);
    await tester.pump(const Duration(seconds: 1));
  });

  testWidgets('AI approval displays proposed content and supporting quotes',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      final data = switch (r.path) {
        '/agent-runs' => {'run_id': 'run', 'status': 'succeeded'},
        '/agent-runs/run' => {'id': 'run', 'status': 'succeeded'},
        '/agent-runs/run/events' => {'events': [], 'next_seq': 0},
        '/proposals' => {
            'proposals': [
              {'id': 'p', 'status': 'pending'}
            ]
          },
        '/proposals/p' => {
            'can_approve': true,
            'proposal': {
              'id': 'p',
              'status': 'pending',
              'version': 1,
              'action_type': 'reading_note.draft',
              'reason': '导读',
              'payload': {
                'claims': ['实际将保存的笔记内容'],
                'questions': ['需要验证的问题']
              },
              'source_refs': [
                {
                  'source_id': 's',
                  'source_revision': 1,
                  'quote': '支持结论的原文',
                  'locator': {'kind': 'pdf_page', 'page': 2}
                }
              ],
            }
          },
        '/sources/s' => {'id': 's', 'revision': 1, 'excerpt': '支持结论的原文'},
        _ => throw StateError(r.path),
      };
      h.resolve(Response(requestOptions: r, data: {'code': 0, 'data': data}));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      apiClientProvider.overrideWithValue(api),
      agentInputProvider.overrideWith((ref) => {
            'kind': 'paper_guide',
            'subject': {'student_id': 'u', 'paper_id': 'paper'},
            'input': {}
          }),
    ], child: const MaterialApp(home: AgentRunScreen())));
    await tester.pumpAndSettle();
    expect(find.textContaining('实际将保存的笔记内容'), findsWidgets);
    await tester.ensureVisible(find.text('查看原文依据'));
    await tester.tap(find.text('查看原文依据'));
    await tester.pumpAndSettle();
    expect(find.textContaining('支持结论的原文'), findsWidgets);
  });
}
