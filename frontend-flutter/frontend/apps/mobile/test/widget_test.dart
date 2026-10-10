import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yanji_mobile/src/app.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

void main() {
  test('uses the configured API base URL', () {
    final container = ProviderContainer();
    addTearDown(container.dispose);

    expect(container.read(apiClientProvider).dio.options.baseUrl,
        endsWith('/api/v1'));
  });

  testWidgets('shows login surface', (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    await tester.pumpWidget(const ProviderScope(child: YanjiApp()));
    await tester.pumpAndSettle();
    expect(find.text('欢迎回到研迹'), findsOneWidget);
    expect(find.text('登录'), findsOneWidget);
  });

  testWidgets('login has a registration entry', (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    await tester.pumpWidget(const ProviderScope(child: YanjiApp()));
    await tester.pumpAndSettle();
    expect(find.text('注册账号'), findsOneWidget);
    await tester.ensureVisible(find.text('注册账号'));
    await tester.tap(find.text('注册账号'));
    await tester.pumpAndSettle();
    expect(find.text('创建研迹账号'), findsOneWidget);
    expect(find.text('邀请码（选填）'), findsNothing);
    expect(find.text('学生'), findsOneWidget);
    expect(find.text('导师'), findsOneWidget);
    expect(find.text('确认密码'), findsOneWidget);
  });

  for (final scenario in ['success', 'mentor', 'error']) {
    final succeeds = scenario != 'error';
    final role = scenario == 'mentor' ? 'mentor' : 'student';
    testWidgets(
        succeeds
            ? 'registration sends the contract payload and returns to login ($scenario)'
            : 'registration displays backend invitation error', (tester) async {
      FlutterSecureStorage.setMockInitialValues({});
      final api = ApiClient('http://localhost');
      var requests = 0;
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        requests++;
        expect(r.path, '/auth/register');
        expect(r.method, 'POST');
        expect(r.data, {
          'username': 'new_student',
          'password': 'testpass123',
          'display_name': '新同学',
          'role': role
        });
        if (succeeds) {
          h.resolve(Response(requestOptions: r, statusCode: 201, data: {
            'code': 0,
            'message': 'success',
            'data': {'user_id': 'new-user'}
          }));
        } else {
          h.reject(DioException(
              requestOptions: r,
              type: DioExceptionType.badResponse,
              response: Response(requestOptions: r, statusCode: 422, data: {
                'code': 42200,
                'message': '账号已存在',
                'data': {
                  'error_code': 'VALIDATION_ERROR',
                  'retryable': false,
                  'details': {}
                }
              })));
        }
      }));
      await tester.pumpWidget(ProviderScope(
          overrides: [apiClientProvider.overrideWithValue(api)],
          child: const YanjiApp()));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('注册账号'));
      await tester.tap(find.text('注册账号'));
      await tester.pumpAndSettle();
      if (role == 'mentor') {
        await tester.tap(find.text('导师'));
        await tester.pumpAndSettle();
      }
      for (final entry in {
        'register_username': 'new_student',
        'register_name': '新同学',
        'register_password': 'testpass123',
        'register_confirmation': 'mismatch',
      }.entries) {
        await tester.ensureVisible(find.byKey(Key(entry.key)));
        await tester.enterText(find.byKey(Key(entry.key)), entry.value);
      }
      await tester.ensureVisible(find.byKey(const Key('register_submit')));
      await tester.tap(find.byKey(const Key('register_submit')));
      await tester.pumpAndSettle();
      expect(requests, 0);
      expect(find.text('两次输入的密码不一致'), findsOneWidget);
      await tester
          .ensureVisible(find.byKey(const Key('register_confirmation')));
      await tester.enterText(
          find.byKey(const Key('register_confirmation')), 'testpass123');
      await tester.ensureVisible(find.byKey(const Key('register_submit')));
      await tester.tap(find.byKey(const Key('register_submit')));
      await tester.pumpAndSettle();
      expect(requests, 1);
      if (succeeds) {
        expect(find.text('欢迎回到研迹'), findsOneWidget);
        expect(find.text('注册成功，请使用新账号登录'), findsOneWidget);
        expect(find.text('new_student'), findsOneWidget);
      } else {
        expect(find.text('账号已存在'), findsOneWidget);
        expect(find.text('创建研迹账号'), findsOneWidget);
      }
    });
  }

  testWidgets('unjoined student can apply to a mentor and wait for approval',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    var submitted = false;
    final paths = <String>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      paths.add(r.path);
      Map<String, dynamic> data;
      switch (r.path) {
        case '/auth/login':
          data = {'access_token': 'test-token'};
        case '/me':
          data = {
            'user': {'id': 'u', 'display_name': '新同学'},
            'allowed_roles': ['student'],
            'collaboration_enabled': false,
            'onboarding_status': submitted ? 'pending' : 'choose_mentor',
            'memberships': []
          };
        case '/mentor-applications':
          expect(r.data, {'mentor_id': 'mentor1', 'lab_id': 'lab1'});
          submitted = true;
          data = {
            'application': {'id': 'application1', 'status': 'pending'}
          };
        case '/mentor-applications/me':
          data = {
            'application':
                submitted ? {'id': 'application1', 'status': 'pending'} : null
          };
        case '/mentors':
          data = {
            'mentors': [
              {
                'mentor_id': 'mentor1',
                'display_name': '导师甲',
                'lab_id': 'lab1',
                'lab_name': '实验室甲'
              }
            ]
          };
        case '/dashboard':
          data = {
            'counters': {'tasks_total': 3, 'tasks_overdue': 1},
            'risks': []
          };
        case '/tasks':
          data = {'tasks': [], 'next_cursor': null};
        case '/papers':
          data = {'papers': [], 'next_cursor': null};
        case '/weekly-reports':
          data = {'reports': [], 'next_cursor': null};
        case '/notifications':
          data = {'items': [], 'next_cursor': null};
        default:
          fail('Unexpected API: ${r.path}');
      }
      h.resolve(Response(
          requestOptions: r,
          statusCode: 200,
          data: {'code': 0, 'message': 'success', 'data': data}));
    }));
    await tester.pumpWidget(ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(api)],
        child: const YanjiApp()));
    await tester.pumpAndSettle();
    final fields = find.byType(TextField);
    await tester.enterText(fields.at(0), 'new_student');
    await tester.enterText(fields.at(1), 'testpass123');
    await tester.tap(find.text('登录'));
    await tester.pumpAndSettle();
    expect(find.text('导师与实验室'), findsOneWidget,
        reason: tester
            .widgetList<Text>(find.byType(Text))
            .map((t) => t.data)
            .join(' | '));
    expect(paths, isNot(contains('/dashboard')));
    expect(paths, isNot(contains('/tasks')));
    await tester.scrollUntilVisible(find.text('导师甲').hitTestable(), 200,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('导师甲'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('申请加入').hitTestable(), 200,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(find.text('申请加入'));
    await tester.pumpAndSettle();
    expect(submitted, isTrue);
    expect(find.text('申请已提交，等待导师确认。批准后即可使用任务、周报和 AI。'), findsOneWidget);
  });
}
