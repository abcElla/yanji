import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';

class TestSession extends SessionController {
  TestSession(super.ref,
      {String id = 'student-real',
      bool mentor = false,
      bool enabled = true,
      List<dynamic> memberships = const []}) {
    state = AsyncData(Session(
        user: {'id': id, 'display_name': '真实学生'},
        roles: [mentor ? 'mentor' : 'student'],
        memberships: memberships,
        collaborationEnabled: enabled));
  }
  void clear() {
    state = const AsyncData(null);
  }
}

void resolveData(RequestOptions r, RequestInterceptorHandler h,
        Map<String, dynamic> data) =>
    h.resolve(Response(
        requestOptions: r,
        statusCode: 200,
        data: {'code': 0, 'message': 'success', 'data': data}));
void rejectData(RequestOptions r, RequestInterceptorHandler h, int status,
        String message) =>
    h.reject(DioException(
        requestOptions: r,
        type: DioExceptionType.badResponse,
        response: Response(requestOptions: r, statusCode: status, data: {
          'code': status * 100,
          'message': message,
          'data': {'retryable': false}
        })));
ProviderContainer testContainer(ApiClient api,
        {bool mentor = false,
        String? selected = 'student-real',
        bool enabled = true,
        List<dynamic> memberships = const []}) =>
    ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(api),
      sessionProvider.overrideWith((ref) => TestSession(ref,
          id: mentor ? 'mentor-real' : 'student-real',
          mentor: mentor,
          enabled: enabled,
          memberships: memberships)),
      roleProvider
          .overrideWith((ref) => mentor ? UserRole.mentor : UserRole.student),
      selectedStudentProvider.overrideWith((ref) => selected),
    ]);
Future<void> mountPage(
    WidgetTester tester, ProviderContainer container, Widget page) async {
  tester.view.physicalSize = const Size(800, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(UncontrolledProviderScope(
      container: container, child: MaterialApp(home: page)));
  await tester.pumpAndSettle();
}

Future<void> tapText(WidgetTester tester, String text) async {
  await tester.pump();
  final finder = find.text(text);
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pumpAndSettle();
}
