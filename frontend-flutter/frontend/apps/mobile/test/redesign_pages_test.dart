import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/design_system.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'improvement_support.dart';

void main() {
  testWidgets(
      'weekly report has compact colored sections and keeps source evidence',
      (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) => resolveData(r, h, {
              'id': 'report',
              'student_id': 'student-real',
              'week_start': '2026-10-05',
              'version': 1,
              'revision': 1,
              'status': 'draft',
              'done_items': [
                {
                  'id': 'fact',
                  'text': '实测数据结果',
                  'source_refs': [
                    {
                      'title': '实验结果',
                      'locator': {'kind': 'record_field', 'field': 'results'},
                      'quote': '可核对的原记录'
                    }
                  ]
                }
              ],
              'issues': [],
              'next_plan': [],
              'help_needed': [],
              'reviews': []
            })));
    final container = testContainer(api);
    addTearDown(container.dispose);
    container.read(selectedReportProvider.notifier).state = 'report';
    tester.view.physicalSize = const Size(360, 1500);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
            theme: ResearchDesign.theme(), home: const WeeklyReportScreen())));
    await tester.pumpAndSettle();
    expect(find.byType(ResearchPanel), findsAtLeastNWidgets(5));
    expect(find.text('查看原文依据'), findsOneWidget);
    final card = find
        .ancestor(of: find.text('实测数据结果'), matching: find.byType(Card))
        .first;
    expect(tester.getSize(card).width, 320);
    expect(tester.takeException(), isNull);
  });
}
