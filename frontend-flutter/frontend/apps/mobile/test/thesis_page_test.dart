import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/theses.dart';
import 'improvement_support.dart';

Map<String, dynamic> detail() => {
      'id': 'thesis',
      'title': '排队研究',
      'student_id': 'student-real',
      'version': 2,
      'revisions': [
        {
          'id': 'revision',
          'revision_number': 1,
          'file_id': 'file',
          'paper_id': 'paper',
          'ingest_status': 'ready',
          'change_summary': '补充方法',
          'created_at': '2026-10-08T00:00:00Z',
          'feedback': []
        }
      ]
    };

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  testWidgets(
      'student sees revision publishing and mentor sees feedback on the specified revision',
      (tester) async {
    for (final mentor in [false, true]) {
      final api = ApiClient('http://localhost');
      api.dio.interceptors.add(InterceptorsWrapper(
          onRequest: (r, h) => resolveData(r, h, detail())));
      final container = testContainer(api, mentor: mentor);
      await mountPage(
          tester, container, const ThesisDetailScreen(thesisId: 'thesis'));
      expect(find.text('发布新稿'), mentor ? findsNothing : findsOneWidget);
      expect(find.text('提交导师反馈'), mentor ? findsOneWidget : findsNothing);
      expect(find.text('下载 / 打开稿件'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      container.dispose();
    }
  });
  testWidgets(
      'mentor feedback validates content and submits decision to selected revision',
      (tester) async {
    final writes = <RequestOptions>[];
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        writes.add(r);
        resolveData(r, h, {'id': 'feedback'});
      } else {
        resolveData(r, h, detail());
      }
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(
        tester, container, const ThesisDetailScreen(thesisId: 'thesis'));
    await tapText(tester, '提交导师反馈');
    expect(find.text('请写下具体修改意见或通过理由'), findsOneWidget);
    expect(writes, isEmpty);
    await tester.enterText(
        find.byKey(const ValueKey('thesis-feedback')), '补充抽样方法');
    await tapText(tester, '提交导师反馈');
    expect(writes.single.path, '/theses/thesis/revisions/revision/feedback');
    expect(writes.single.data,
        {'content': '补充抽样方法', 'decision': 'changes_requested'});
    expect(writes.single.headers['Idempotency-Key'], isNotEmpty);
  });
  testWidgets('409 feedback preserves text and allows refreshed retry',
      (tester) async {
    var writes = 0;
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        writes++;
        rejectData(r, h, 409, '内容已更新');
      } else {
        resolveData(r, h, detail());
      }
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(
        tester, container, const ThesisDetailScreen(thesisId: 'thesis'));
    await tester.enterText(
        find.byKey(const ValueKey('thesis-feedback')), '我的意见保留');
    await tapText(tester, '提交导师反馈');
    expect(find.textContaining('填写内容已保留'), findsOneWidget);
    expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('thesis-feedback')))
            .controller!
            .text,
        '我的意见保留');
    expect(writes, 1);
  });
  testWidgets(
      'changing mentor student clears old revision and ignores delayed detail response',
      (tester) async {
    final completer = Completer<Map<String, dynamic>>();
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) async => resolveData(r, h, await completer.future)));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await tester.pumpWidget(MaterialApp(home: const SizedBox()));
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child:
            const MaterialApp(home: ThesisDetailScreen(thesisId: 'thesis'))));
    await tester.pump();
    container.read(selectedStudentProvider.notifier).state = 'another-student';
    await tester.pump();
    completer.complete(detail());
    await tester.pumpAndSettle();
    expect(find.text('补充方法'), findsNothing);
    expect(find.text('提交导师反馈'), findsNothing);
    expect(find.textContaining('访问范围已改变'), findsOneWidget);
  });
}
