import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:yanji_mobile/src/ai_action.dart';
import 'package:yanji_mobile/src/data.dart';
import 'improvement_support.dart';

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  for (final reportId in [null, 'report-selected']) {
    testWidgets('weekly AI pins only the explicitly selected draft ($reportId)',
        (tester) async {
      final api = ApiClient('http://localhost');
      RequestOptions? write;
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        if (r.path == '/weekly-reports/report-selected') {
          resolveData(r, h, {'id': reportId, 'week_start': '2026-09-28'});
        } else if (r.method == 'POST') {
          write = r;
          resolveData(r, h, {'run_id': 'weekly-run', 'status': 'queued'});
        } else {
          resolveData(r, h, {'id': 'weekly-run', 'status': 'succeeded'});
        }
      }));
      final container = testContainer(api);
      addTearDown(container.dispose);
      final router = GoRouter(routes: [
        GoRoute(
            path: '/',
            builder: (_, s) => Scaffold(
                body: AiActionPanel(
                    title: '周报',
                    kind: 'weekly_report',
                    reportId: reportId,
                    action: '生成周报'))),
        GoRoute(
            path: '/agent',
            builder: (c, s) => Scaffold(
                body: TextButton(
                    onPressed: () => c.pop(), child: const Text('返回')))),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(routerConfig: router)));
      await tester.pumpAndSettle();
      await tapText(tester, '生成周报');
      expect(write!.data['input'], {
        'week_start': reportId == null ? currentWeek() : '2026-09-28',
        if (reportId != null) 'report_id': reportId,
      });
      await tapText(tester, '返回');
    });
  }
  for (final mentor in [false, true]) {
    testWidgets(
        'real AI ${mentor ? 'risk' : 'question'} preserves selected student and material scope',
        (tester) async {
      final writes = <RequestOptions>[];
      final api = ApiClient('http://localhost');
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        if (r.method == 'POST') {
          writes.add(r);
          resolveData(r, h, {'run_id': 'run-real', 'status': 'queued'});
        } else {
          resolveData(r, h, {
            'id': 'run-real',
            'status': 'succeeded',
            'result': {'outcome': 'insufficient_evidence'}
          });
        }
      }));
      final container = testContainer(api, mentor: mentor);
      addTearDown(container.dispose);
      final router = GoRouter(initialLocation: '/', routes: [
        GoRoute(
            path: '/',
            builder: (_, s) => Scaffold(
                body: AiActionPanel(
                    title: 'AI 下一步',
                    kind: mentor ? 'risk_brief' : 'research_qa',
                    stageId: 'stage-real',
                    paperId: 'paper-real',
                    reportId: mentor ? 'report-real' : null,
                    question: '核对材料',
                    action: '开始分析'))),
        GoRoute(
            path: '/agent',
            builder: (c, s) => Scaffold(
                body: TextButton(
                    onPressed: () => c.pop(), child: const Text('返回分析')))),
      ]);
      addTearDown(router.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(routerConfig: router)));
      await tester.pumpAndSettle();
      expect(writes, isEmpty);
      expect(find.text('尚未生成'), findsOneWidget);
      await tapText(tester, '开始分析');
      expect(writes.single.path, '/agent-runs');
      expect(writes.single.data['subject'], {
        'student_id': 'student-real',
        'stage_id': 'stage-real',
        'paper_id': 'paper-real'
      });
      expect(
          writes.single.data['input'],
          mentor
              ? {'horizon_days': 7, 'report_id': 'report-real'}
              : {'question': '核对材料'});
      await tapText(tester, '返回分析');
      expect(find.text('材料不足，请补充后重新分析'), findsOneWidget);
      container.read(selectedStudentProvider.notifier).state = 'different';
      await tester.pumpAndSettle();
      expect(find.text('材料不足，请补充后重新分析'), findsNothing);
    });
  }
}
