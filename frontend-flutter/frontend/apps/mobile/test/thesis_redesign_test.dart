import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yanji_mobile/src/design_system.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';
import 'package:yanji_mobile/src/research_assistant.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'improvement_support.dart';

void main() {
  testWidgets('library cards fill available width on a small phone',
      (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      resolveData(r, h, {
        'papers': [
          {'id': 'p', 'title': '短标题', 'ingest_status': 'ready'}
        ]
      });
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
            theme: ResearchDesign.theme(),
            home: const Scaffold(body: LibraryScreen()))));
    tester.view.physicalSize = const Size(360, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpAndSettle();
    final card =
        find.ancestor(of: find.text('短标题'), matching: find.byType(Card));
    expect(tester.getSize(card).width, 320);
    expect(tester.getSize(card).height, lessThan(175));
    expect(find.text('查看笔记'), findsOneWidget);
    expect(find.text('AI 导读'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  test(
      'thesis writes retry with the same idempotency key after a lost response',
      () async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost')..userScope = 'student-real';
    final keys = <dynamic>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      keys.add(r.headers['Idempotency-Key']);
      if (keys.length == 1) {
        h.reject(DioException(
            requestOptions: r, type: DioExceptionType.connectionError));
      } else {
        resolveData(r, h, {'id': 'revision-real', 'revision_number': 1});
      }
    }));
    final body = {
      'file_id': 'file-real',
      'change_summary': '补充分析',
      'expected_version': 1
    };
    await expectLater(
        api.write('/theses/t/revisions', body), throwsA(isA<ApiFailure>()));
    final saved = await api.write('/theses/t/revisions', body);
    expect(saved['id'], 'revision-real');
    expect(keys.first, isNotNull);
    expect(keys.last, keys.first);
  });
  test('specified paper AI request retains paper, stage and student scope', () {
    final body = Function.apply(assistantRequest, [], {
      #role: UserRole.student,
      #studentId: 'student-real',
      #stageId: 'stage-real',
      #paperId: 'paper-real',
      #question: '请结合导师反馈指出要修改的内容'
    }) as Map;
    expect(body['kind'], 'research_qa');
    expect(body['subject'], {
      'student_id': 'student-real',
      'stage_id': 'stage-real',
      'paper_id': 'paper-real'
    });
  });
}
