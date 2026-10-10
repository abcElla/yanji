import 'dart:async';
import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'package:yanji_mobile/src/design_system.dart';
import 'package:yanji_mobile/src/research_followups.dart';

class QaSessionController extends SessionController {
  QaSessionController(super.ref) {
    state = const AsyncData(Session(
        user: {'id': 'student', 'display_name': '学生'},
        roles: ['student'],
        collaborationEnabled: true));
  }
}

void main() {
  testWidgets(
      'notification opens readable detail after persisted read and allows repeated reading',
      (tester) async {
    final api = ApiClient('http://test');
    int reads = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      expect(r.path, '/notifications/n/read');
      reads++;
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 0,
        'data': {'read_at': '2026-10-08T12:00:00Z'}
      }));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      researchFollowupsProvider.overrideWith((ref) => const AsyncData([])),
      assistantStagesProvider.overrideWith((ref, studentId) async => []),
      apiClientProvider.overrideWithValue(api),
      sessionProvider.overrideWith((ref) => QaSessionController(ref)),
      dashboardProvider
          .overrideWith((ref) async => {'counters': {}, 'risks': []}),
      tasksProvider.overrideWith((ref) async => []),
      papersProvider.overrideWith((ref) async => []),
      notificationsProvider.overrideWith((ref) async => [
            {
              'id': 'n',
              'type': 'task_due',
              'read_at': reads > 0 ? 'today' : null,
              'target': {'title': '核实宿舍台灯电价来源', 'message': '请核对进展并与导师确认后续安排。'}
            }
          ]),
    ], child: const MaterialApp(home: Scaffold(body: OverviewScreen()))));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('消息'));
    await tester.pumpAndSettle();
    for (int i = 0; i < 2; i++) {
      await tester.tap(find.text('任务到期提醒'));
      await tester.pumpAndSettle();
      expect(find.text('核实宿舍台灯电价来源'), findsOneWidget);
      expect(find.text('请核对进展并与导师确认后续安排。'), findsOneWidget);
      await tester.tap(find.text('知道了'));
      await tester.pumpAndSettle();
      expect(find.text('已读'), findsOneWidget);
    }
    expect(reads, 2);
  });
  testWidgets('rapid notification taps send one request and open one dialog',
      (tester) async {
    final api = ApiClient('http://test');
    final release = Completer<void>();
    int reads = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) async {
      reads++;
      await release.future;
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 0,
        'data': {
          'read_at': 'today',
          'target': {'title': '核对电价清单'}
        }
      }));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      researchFollowupsProvider.overrideWith((ref) => const AsyncData([])),
      assistantStagesProvider.overrideWith((ref, studentId) async => []),
      apiClientProvider.overrideWithValue(api),
      sessionProvider.overrideWith((ref) => QaSessionController(ref)),
      dashboardProvider
          .overrideWith((ref) async => {'counters': {}, 'risks': []}),
      tasksProvider.overrideWith((ref) async => []),
      papersProvider.overrideWith((ref) async => []),
      notificationsProvider.overrideWith((ref) async => [
            {
              'id': 'n',
              'type': 'task_due',
              'read_at': reads > 0 ? 'today' : null
            }
          ]),
    ], child: const MaterialApp(home: Scaffold(body: OverviewScreen()))));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('消息'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('任务到期提醒'));
    await tester.tap(find.text('任务到期提醒'));
    await tester.pumpAndSettle();
    expect(reads, 1);
    release.complete();
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.tap(find.text('知道了'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('任务到期提醒'));
    await tester.pumpAndSettle();
    expect(reads, 2);
    expect(find.byType(AlertDialog), findsOneWidget);
  });
  testWidgets('failed notification read stays unread and does not show detail',
      (tester) async {
    final api = ApiClient('http://test');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 40400,
        'message': '消息已不可查看',
        'data': {'error_code': 'NOT_FOUND', 'retryable': false}
      }));
    }));
    await tester.pumpWidget(ProviderScope(overrides: [
      researchFollowupsProvider.overrideWith((ref) => const AsyncData([])),
      assistantStagesProvider.overrideWith((ref, studentId) async => []),
      apiClientProvider.overrideWithValue(api),
      sessionProvider.overrideWith((ref) => QaSessionController(ref)),
      dashboardProvider
          .overrideWith((ref) async => {'counters': {}, 'risks': []}),
      tasksProvider.overrideWith((ref) async => []),
      papersProvider.overrideWith((ref) async => []),
      notificationsProvider.overrideWith((ref) async => [
            {
              'id': 'n',
              'type': 'task_due',
              'read_at': null,
              'target': {'title': '不应展示的消息详情'}
            }
          ]),
    ], child: const MaterialApp(home: Scaffold(body: OverviewScreen()))));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('消息'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('任务到期提醒'));
    await tester.pumpAndSettle();
    expect(find.text('未读'), findsOneWidget);
    expect(find.text('已读'), findsNothing);
    expect(find.text('不应展示的消息详情'), findsNothing);
    expect(find.text('消息已不可查看'), findsOneWidget);
  });
  testWidgets('overview metrics fit a narrow phone without clipping',
      (tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(ProviderScope(
        overrides: [
          sessionProvider.overrideWith((ref) => QaSessionController(ref)),
          dashboardProvider.overrideWith((ref) async => {
                'counters': {'tasks_total': 6, 'tasks_overdue': 0},
                'risks': []
              }),
          tasksProvider.overrideWith((ref) async => []),
          notificationsProvider.overrideWith((ref) async => []),
          papersProvider.overrideWith((ref) async => []),
        ],
        child: MaterialApp(
            theme: ResearchDesign.theme(),
            home: const Scaffold(body: OverviewScreen()))));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('任务总数'), findsOneWidget);
  });
  testWidgets('overview translates real risk and notification types',
      (tester) async {
    await tester.pumpWidget(ProviderScope(overrides: [
      sessionProvider.overrideWith((ref) => QaSessionController(ref)),
      dashboardProvider.overrideWith((ref) async => {
            'counters': {},
            'risks': [
              {'type': 'task_overdue', 'task_id': 'task'}
            ]
          }),
      tasksProvider.overrideWith((ref) async => []),
      papersProvider.overrideWith((ref) async => []),
      notificationsProvider.overrideWith((ref) async => [
            {'id': 'n1', 'type': 'stage_review', 'read_at': null},
            {'id': 'n2', 'type': 'task_reminder', 'read_at': 'today'},
          ]),
    ], child: const MaterialApp(home: Scaffold(body: OverviewScreen()))));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('有任务需要处理'), 250);
    expect(find.text('有任务需要处理'), findsOneWidget);
    expect(find.text('已超过截止时间，请检查进展'), findsOneWidget);
    await tester.scrollUntilVisible(find.byTooltip('消息'), -250);
    await tester.tap(find.byTooltip('消息'));
    await tester.pumpAndSettle();
    expect(find.text('阶段审阅'), findsOneWidget);
    expect(find.text('任务提醒'), findsOneWidget);
  });
}
