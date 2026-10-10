import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/paper_reader.dart';
import 'improvement_support.dart';

void main() {
  testWidgets(
      'mentor reads full note as text without an inner editing viewport',
      (tester) async {
    final api = ApiClient('http://localhost');
    final lines = List.generate(12, (i) => '第${i + 1}项阅读结论：核对条件，保留未知事项。');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      resolveData(r, h, {
        'id': 'note',
        'paper_id': 'paper',
        'version': 2,
        'status': 'confirmed',
        'claims': lines,
        'questions': ['真实功率如何确认？'],
        'source_refs': []
      });
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(tester, container,
        const ReadingNoteScreen(paperId: 'paper', noteId: 'note'));
    expect(find.byType(TextField), findsNothing);
    expect(find.text(lines.join('\n')), findsOneWidget);
    expect(find.text('保存阅读笔记'), findsNothing);
  });
  testWidgets('paper reader follows notes cursor and keeps earlier notes',
      (tester) async {
    final api = ApiClient('http://localhost');
    final queries = <Map<String, dynamic>>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path == '/notes') {
        queries.add(r.queryParameters);
        final next = r.queryParameters['cursor'] != null;
        resolveData(r, h, {
          'items': [
            {
              'id': next ? 'n2' : 'n1',
              'status': 'confirmed',
              'claims': [next ? '第二页结论' : '第一页结论']
            }
          ],
          'next_cursor': next ? null : 'cursor-real'
        });
      } else {
        resolveData(
            r, h, {'id': 'paper', 'title': '已有文献', 'ingest_status': 'ready'});
      }
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(
        tester, container, const PaperReaderScreen(paperId: 'paper'));
    await tapText(tester, '加载更多笔记');
    expect(queries.last['cursor'], 'cursor-real');
    expect(queries.last['student_id'], 'student-real');
    expect(find.text('第一页结论'), findsOneWidget);
    expect(find.text('第二页结论'), findsOneWidget);
    expect(find.text('加载更多笔记'), findsNothing);
  });
  testWidgets(
      'paper reader lists actual notes with the selected student and paper scope',
      (tester) async {
    final api = ApiClient('http://localhost');
    final queries = <Map<String, dynamic>>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path == '/notes') {
        queries.add(r.queryParameters);
        resolveData(r, h, {
          'items': [
            {
              'id': 'note',
              'status': 'confirmed',
              'claims': ['已记录的阅读结论'],
              'questions': []
            }
          ]
        });
      } else {
        resolveData(
            r, h, {'id': 'paper', 'title': '已有文献', 'ingest_status': 'ready'});
      }
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(
        tester, container, const PaperReaderScreen(paperId: 'paper'));
    expect(queries.single, {'paper_id': 'paper', 'student_id': 'student-real'});
    expect(find.text('已记录的阅读结论'), findsOneWidget);
    expect(find.text('写阅读笔记'), findsNothing);
  });
  testWidgets('note edit preserves existing sources and retains input on 409',
      (tester) async {
    final writes = <RequestOptions>[];
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'PATCH') {
        writes.add(r);
        rejectData(r, h, 409, '已更新');
      } else {
        resolveData(r, h, {
          'id': 'note',
          'paper_id': 'paper',
          'version': 3,
          'status': 'draft',
          'claims': ['原结论'],
          'questions': [],
          'source_refs': [
            {
              'source_id': 'source-real',
              'source_revision': 2,
              'locator': {'kind': 'pdf_page', 'page': 3},
              'quote': '原引用'
            }
          ]
        });
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container,
        const ReadingNoteScreen(paperId: 'paper', noteId: 'note'));
    await tester.enterText(find.byKey(const ValueKey('note-claims')), '修正后的结论');
    await tapText(tester, '保存阅读笔记');
    expect(writes.single.data['expected_version'], 3);
    expect(
        writes.single.data['source_refs'].single['source_id'], 'source-real');
    expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('note-claims')))
            .controller!
            .text,
        '修正后的结论');
    expect(find.textContaining('填写内容已保留'), findsOneWidget);
  });
}
