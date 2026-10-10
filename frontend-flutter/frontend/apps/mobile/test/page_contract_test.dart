import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';
import 'package:yanji_mobile/src/mentor_onboarding.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'improvement_support.dart';

void main() {
  testWidgets('task submission attaches confirmed shared experiment results',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    Map<String, dynamic>? submitted;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      dynamic data;
      if (r.path == '/experiments') {
        expect(r.queryParameters['task_id'], 'task');
        data = {
          'experiments': [
            {'id': 'record', 'status': 'confirmed', 'visibility': 'mentors'}
          ]
        };
      } else if (r.path == '/experiments/record') {
        data = {
          'status': 'confirmed',
          'visibility': 'mentors',
          'task_id': 'task',
          'purpose': '核对台灯费用',
          'results': '理论费用差为0.288元。',
          'source_id': 'source',
          'source_revision': 2
        };
      } else if (r.path == '/tasks/task/transition') {
        submitted = Map<String, dynamic>.from(r.data as Map);
        data = {};
      } else {
        throw StateError(r.path);
      }
      h.resolve(Response(
          requestOptions: r, statusCode: 200, data: {'code': 0, 'data': data}));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      apiClientProvider.overrideWithValue(api),
      roleProvider.overrideWith((ref) => UserRole.student),
      selectedTaskProvider.overrideWith((ref) => const ResearchTask(
          id: 'task',
          title: '台灯计算',
          type: '实验',
          status: TaskStatus.inProgress,
          dueLabel: '',
          description: '')),
      taskProvider.overrideWith((ref, id) async => ResearchTask.fromJson({
            'id': id,
            'title': '台灯计算',
            'type': 'experiment',
            'status': 'in_progress',
            'stage_id': 'stage',
            'version': 4
          })),
    ], child: const MaterialApp(home: TaskDetailScreen())));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('提交验收'));
    await tester.tap(find.text('提交验收'));
    await tester.pumpAndSettle();
    expect(find.text('核对台灯费用'), findsOneWidget);
    await tester.tap(find.text('确认提交'));
    await tester.pumpAndSettle();
    expect(submitted?['evidence_refs'], [
      {
        'source_id': 'source',
        'source_revision': 2,
        'chunk_id': null,
        'locator': {'kind': 'record_field', 'field': 'results'},
        'quote': '理论费用差为0.288元。'
      }
    ]);
    expect(submitted?['expected_version'], 4);
  });
  testWidgets('task shows student difficulty and mentor return comments',
      (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      expect(r.path, '/tasks/task/events');
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 0,
        'data': {
          'events': [
            {
              'from_status': 'blocked',
              'to_status': 'blocked',
              'comment': '导师确认新的截止时间'
            },
            {'to_status': 'blocked', 'comment': '学校实际电价尚未确认'},
            {'to_status': 'in_progress', 'comment': '请补充单位换算过程'}
          ]
        }
      }));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      apiClientProvider.overrideWithValue(api),
      sessionProvider.overrideWith((ref) => TestSession(ref)),
      selectedTaskProvider.overrideWith((ref) => const ResearchTask(
          id: 'task',
          title: '台灯估算',
          type: '实验',
          status: TaskStatus.inProgress,
          dueLabel: '',
          description: '')),
      taskProvider.overrideWith((ref, id) async => ResearchTask.fromJson(
          {'id': id, 'title': '台灯估算', 'status': 'in_progress'})),
    ], child: const MaterialApp(home: TaskDetailScreen())));
    await tester.pumpAndSettle();
    expect(find.text('学校实际电价尚未确认'), findsOneWidget);
    expect(find.text('调整安排'), findsOneWidget);
    expect(find.text('请补充单位换算过程'), findsOneWidget);
    expect(find.text('遇到困难'), findsWidgets);
  });
  for (final mentor in [false, true]) {
    testWidgets('task difficulty and return keep a real reason ($mentor)',
        (tester) async {
      FlutterSecureStorage.setMockInitialValues({});
      final api = ApiClient('http://localhost');
      Map<String, dynamic>? written;
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        written = Map<String, dynamic>.from(r.data as Map);
        h.resolve(Response(
            requestOptions: r,
            statusCode: 200,
            data: {'code': 0, 'message': 'success', 'data': {}}));
      }));
      await tester.pumpWidget(ProviderScope(overrides: [
        apiClientProvider.overrideWithValue(api),
        selectedTaskProvider.overrideWith((ref) => const ResearchTask(
            id: 'task',
            title: '台灯估算',
            type: '实验',
            status: TaskStatus.inProgress,
            dueLabel: '',
            description: '')),
        roleProvider
            .overrideWith((ref) => mentor ? UserRole.mentor : UserRole.student),
        taskProvider.overrideWith((ref, id) async => ResearchTask.fromJson({
              'id': id,
              'title': '台灯估算',
              'status': mentor ? 'submitted' : 'in_progress',
              'stage_id': 'stage',
              'version': 7
            })),
      ], child: const MaterialApp(home: TaskDetailScreen())));
      await tester.pumpAndSettle();
      final action = mentor ? '退回补充' : '遇到困难';
      expect(find.text(action), findsOneWidget);
      await tester.ensureVisible(find.text(action));
      await tester.tap(find.text(action));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '计算复核步骤还需要说明');
      await tester.tap(find.text('确认'));
      await tester.pumpAndSettle();
      expect(written?['to_status'], mentor ? 'in_progress' : 'blocked');
      expect(written?['expected_version'], 7);
      expect(written?['comment'], '计算复核步骤还需要说明');
      await tester.pump(const Duration(seconds: 1));
    });
  }
  testWidgets('AI uses created run_id and detail can_approve', (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      final data = switch (r.path) {
        '/agent-runs' => {'run_id': 'run1', 'status': 'queued'},
        '/agent-runs/run1' => {'id': 'run1', 'status': 'succeeded'},
        '/agent-runs/run1/events' => {'events': [], 'next_seq': 0},
        '/proposals' => {
            'proposals': [
              {'id': 'p1', 'status': 'pending'}
            ]
          },
        '/proposals/p1' => {
            'proposal': {
              'id': 'p1',
              'status': 'pending',
              'version': 1,
              'reason': '新计划',
              'action_type': 'task.create_batch'
            },
            'can_approve': true
          },
        _ => throw StateError('Unexpected path: ${r.path}')
      };
      if (r.path == '/agent-runs') {
        expect(r.headers['Idempotency-Key'], isNotEmpty);
      }
      if (r.path == '/proposals') {
        expect(r.queryParameters.containsKey('limit'), false);
      }
      h.resolve(Response(
          requestOptions: r,
          statusCode: 200,
          data: {'code': 0, 'message': 'success', 'data': data}));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      apiClientProvider.overrideWithValue(api),
      agentInputProvider.overrideWith((ref) => {
            'kind': 'weekly_report',
            'subject': {'student_id': 'u'},
            'input': {'week_start': '2026-09-28'}
          })
    ], child: const MaterialApp(home: AgentRunScreen())));
    await tester.pumpAndSettle();
    expect(find.text('运行状态：分析已完成'), findsOneWidget);
    final approve =
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, '确认应用'));
    expect(approve.onPressed, isNotNull);
  });

  testWidgets('student can complete a submitted personal task', (tester) async {
    await tester.pumpWidget(ProviderScope(overrides: [
      selectedTaskProvider.overrideWith((ref) => const ResearchTask(
          id: 'personal',
          title: '个人任务',
          type: '其他',
          status: TaskStatus.submitted,
          dueLabel: '',
          description: '')),
      roleProvider.overrideWith((ref) => UserRole.student),
      sessionProvider.overrideWith((ref) => TestSession(ref)),
      taskProvider.overrideWith((ref, id) async => ResearchTask.fromJson({
            'id': id,
            'title': '个人任务',
            'status': 'submitted',
            'created_by': 'student-real',
            'stage_id': null
          })),
    ], child: const MaterialApp(home: TaskDetailScreen())));
    await tester.pumpAndSettle();
    expect(find.text('完成个人任务'), findsOneWidget);
  });

  for (final creator in ['mentor-real', null]) {
    testWidgets(
        'unstaged task is not personal without verified ownership ($creator)',
        (tester) async {
      await tester.pumpWidget(ProviderScope(overrides: [
        sessionProvider.overrideWith((ref) => TestSession(ref)),
        roleProvider.overrideWith((ref) => UserRole.student),
        selectedTaskProvider.overrideWith((ref) => const ResearchTask(
            id: 'assigned',
            title: '导师任务',
            type: '实验',
            status: TaskStatus.inProgress,
            dueLabel: '',
            description: '')),
        taskProvider.overrideWith((ref, id) async => ResearchTask.fromJson({
              'id': id,
              'title': '导师任务',
              'status': 'in_progress',
              'stage_id': null,
              'created_by': creator
            })),
      ], child: const MaterialApp(home: TaskDetailScreen())));
      await tester.pumpAndSettle();
      expect(find.text('完成个人任务'), findsNothing);
      expect(find.text('提交验收'), findsOneWidget);
    });
  }

  testWidgets('student can resume a blocked staged task', (tester) async {
    await tester.pumpWidget(ProviderScope(overrides: [
      selectedTaskProvider.overrideWith((ref) => const ResearchTask(
          id: 'blocked',
          title: '阻塞任务',
          type: '实验',
          status: TaskStatus.blocked,
          dueLabel: '',
          description: '')),
      roleProvider.overrideWith((ref) => UserRole.student),
      taskProvider.overrideWith((ref, id) async => ResearchTask.fromJson({
            'id': id,
            'title': '阻塞任务',
            'status': 'blocked',
            'stage_id': 'stage1'
          })),
    ], child: const MaterialApp(home: TaskDetailScreen())));
    await tester.pumpAndSettle();
    expect(find.text('恢复任务'), findsOneWidget);
    expect(find.text('完成个人任务'), findsNothing);
  });

  testWidgets('student cannot self-complete a staged task', (tester) async {
    await tester.pumpWidget(ProviderScope(overrides: [
      selectedTaskProvider.overrideWith((ref) => const ResearchTask(
          id: 'staged',
          title: '培养任务',
          type: '其他',
          status: TaskStatus.submitted,
          dueLabel: '',
          description: '')),
      roleProvider.overrideWith((ref) => UserRole.student),
      taskProvider.overrideWith((ref, id) async => ResearchTask.fromJson({
            'id': id,
            'title': '培养任务',
            'status': 'submitted',
            'stage_id': 'stage1'
          })),
    ], child: const MaterialApp(home: TaskDetailScreen())));
    await tester.pumpAndSettle();
    expect(find.text('完成个人任务'), findsNothing);
  });

  testWidgets('queued AI can be cancelled with the current version',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    var cancelled = false;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      Map<String, dynamic> data;
      switch (r.path) {
        case '/agent-runs':
          data = {'run_id': 'run1', 'status': 'queued'};
        case '/agent-runs/run1':
          data = {
            'id': 'run1',
            'status': cancelled ? 'cancelled' : 'queued',
            'version': 3
          };
        case '/agent-runs/run1/events':
          data = {'events': [], 'next_seq': 0};
        case '/proposals':
          data = {'proposals': []};
        case '/agent-runs/run1/cancel':
          expect(r.data, {'expected_version': 3});
          cancelled = true;
          data = {'id': 'run1', 'status': 'cancelled', 'version': 4};
        default:
          throw StateError(r.path);
      }
      h.resolve(Response(
          requestOptions: r,
          statusCode: 200,
          data: {'code': 0, 'message': 'success', 'data': data}));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      apiClientProvider.overrideWithValue(api),
      agentInputProvider.overrideWith((ref) => {
            'kind': 'weekly_report',
            'subject': {'student_id': 'u'},
            'input': {'week_start': '2026-09-28'}
          })
    ], child: const MaterialApp(home: AgentRunScreen())));
    await tester.pumpAndSettle();
    expect(find.textContaining('正在等待处理'), findsOneWidget);
    await tester.tap(find.text('取消运行'));
    await tester.pumpAndSettle();
    expect(cancelled, isTrue);
    expect(find.text('运行状态：已取消'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('mentor applications show backend student_display_name',
      (tester) async {
    await tester.pumpWidget(ProviderScope(
        overrides: [
          pendingMentorApplicationsProvider.overrideWith((ref) async => [
                {'id': 'a', 'student_id': 'u', 'student_display_name': '联调测试学生'}
              ])
        ],
        child:
            const MaterialApp(home: Scaffold(body: MentorApplicationsCard()))));
    await tester.pumpAndSettle();
    expect(find.text('联调测试学生'), findsOneWidget);
    expect(find.text('学生申请'), findsNothing);
  });

  for (final role in UserRole.values) {
    for (final status in [
      'draft',
      'submitted',
      'reviewed',
      'changes_requested'
    ]) {
      testWidgets('report actions follow $role / $status', (tester) async {
        await tester.pumpWidget(ProviderScope(overrides: [
          selectedReportProvider.overrideWith((ref) => 'r'),
          roleProvider.overrideWith((ref) => role),
          reportProvider.overrideWith((ref, id) async => {
                'id': id,
                'status': status,
                'week_start': '2026-09-28',
                'version': 1,
                'revision': 1,
                'done_items': [],
                'issues': [],
                'next_plan': [],
                'help_needed': [],
                'reviews': []
              })
        ], child: const MaterialApp(home: WeeklyReportScreen())));
        await tester.pumpAndSettle();
        expect(
            find.text('提交周报'),
            role == UserRole.student && status == 'draft'
                ? findsOneWidget
                : findsNothing);
        expect(
            find.text('发布反馈'),
            role == UserRole.mentor && status == 'submitted'
                ? findsOneWidget
                : findsNothing);
      });
    }
  }
}
