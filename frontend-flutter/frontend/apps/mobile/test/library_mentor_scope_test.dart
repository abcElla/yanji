import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:go_router/go_router.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';
import 'package:yanji_mobile/src/paper_reader.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'improvement_support.dart';

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  testWidgets(
      'mentor can select a student in library before opening actual notes',
      (tester) async {
    final queries = <Map<String, dynamic>>[];
    final writes = <Map<String, dynamic>>[];
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST' && r.path == '/agent-runs') {
        writes.add(Map<String, dynamic>.from(r.data));
        resolveData(r, h, {'run_id': 'run-real', 'status': 'queued'});
      } else if (r.path == '/agent-runs/run-real') {
        resolveData(r, h, {
          'id': 'run-real',
          'status': 'succeeded',
          'kind': 'paper_guide',
          'result': {'outcome': 'insufficient_evidence'}
        });
      } else if (r.path.endsWith('/events')) {
        resolveData(r, h, {'events': []});
      } else if (r.path == '/proposals') {
        resolveData(r, h, {'proposals': []});
      } else if (r.path == '/notes') {
        queries.add(Map.from(r.queryParameters));
        resolveData(r, h, {
          'items': [
            {
              'id': 'note',
              'status': 'confirmed',
              'claims': ['学生已确认的结论']
            }
          ]
        });
      } else if (r.path == '/papers') {
        resolveData(r, h, {
          'papers': [
            {'id': 'paper', 'title': '真实文献', 'ingest_status': 'ready'}
          ]
        });
      } else {
        resolveData(
            r, h, {'id': 'paper', 'title': '真实文献', 'ingest_status': 'ready'});
      }
    }));
    final container = ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(api),
      sessionProvider.overrideWith((ref) => TestSession(ref, mentor: true)),
      roleProvider.overrideWith((ref) => UserRole.mentor),
      selectedStudentProvider.overrideWith((ref) => null),
      studentsProvider.overrideWith((ref) async => [
            {'id': 'student-a', 'display_name': '学生甲'}
          ]),
    ]);
    addTearDown(container.dispose);
    final router = GoRouter(initialLocation: '/library', routes: [
      GoRoute(
          path: '/library',
          builder: (_, s) => const Scaffold(body: LibraryScreen())),
      GoRoute(
          path: '/papers/:id',
          builder: (_, s) =>
              PaperReaderScreen(paperId: s.pathParameters['id']!)),
      GoRoute(path: '/agent', builder: (_, s) => const AgentRunScreen()),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
        container: container, child: MaterialApp.router(routerConfig: router)));
    await tester.pumpAndSettle();
    expect(find.text('选择学生'), findsOneWidget);
    final viewButton = find.widgetWithText(OutlinedButton, '查看笔记');
    expect(tester.widget<OutlinedButton>(viewButton).onPressed, isNull);
    expect(
        tester
            .widget<OutlinedButton>(
                find.widgetWithText(OutlinedButton, 'AI 导读'))
            .onPressed,
        isNull);
    expect(writes, isEmpty);
    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('学生甲').last);
    await tester.pumpAndSettle();
    expect(container.read(selectedStudentProvider), 'student-a');
    await tapText(tester, 'AI 导读');
    expect(writes.single, {
      'kind': 'paper_guide',
      'subject': {'student_id': 'student-a', 'paper_id': 'paper'},
      'input': {}
    });
    router.pop();
    await tester.pumpAndSettle();
    await tapText(tester, '查看笔记');
    expect(queries.single, {'paper_id': 'paper', 'student_id': 'student-a'});
    expect(find.text('AI 文献导读'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('学生已确认的结论'), 300);
    expect(find.text('学生已确认的结论'), findsOneWidget);
  });
}
