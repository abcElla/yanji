import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('Result business error at HTTP 200 is not success', () async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 40900,
        'message': '内容已更新，请刷新后重试',
        'data': {
          'error_code': 'VERSION_CONFLICT',
          'retryable': false,
          'details': {'current_version': 4}
        }
      }));
    }));
    await expectLater(
        api.get('/tasks/id'),
        throwsA(isA<ApiFailure>()
            .having((e) => e.code, 'code', 'VERSION_CONFLICT')
            .having((e) => e.message, 'message', '内容已更新，请刷新后重试')));
  });
  test('list reads tasks and data next_cursor, including second page',
      () async {
    final api = ApiClient('http://localhost');
    var calls = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      calls++;
      final second = r.queryParameters['cursor'] == 'page2';
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 0,
        'message': 'success',
        'data': {
          'tasks': [
            {'id': second ? 'second' : 'first'}
          ],
          'next_cursor': second ? null : 'page2'
        }
      }));
    }));
    final rows = await api.list('/tasks');
    expect(rows.map((r) => r['id']), ['first', 'second']);
    expect(calls, 2);
  });
  test('events use events and next_seq until empty page', () async {
    final api = ApiClient('http://localhost');
    final requested = <int>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      final seq = r.queryParameters['after_seq'] as int;
      requested.add(seq);
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 0,
        'message': 'success',
        'data': {
          'events': seq == 0
              ? [
                  {'seq': 5}
                ]
              : [],
          'next_seq': 5
        }
      }));
    }));
    expect((await api.runEvents('run')).single['seq'], 5);
    expect(requested, [0, 5]);
  });
  test('no roles never implies mentor role', () async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 0,
        'message': 'success',
        'data': {
          'user': {'id': 'u', 'display_name': '新用户'},
          'allowed_roles': [],
          'memberships': []
        }
      }));
    }));
    final container = ProviderContainer(
        overrides: [apiClientProvider.overrideWithValue(api)]);
    addTearDown(container.dispose);
    await container.read(sessionProvider.notifier).loadMe();
    expect(container.read(roleProvider), isNot(UserRole.mentor));
    expect(container.read(sessionProvider).valueOrNull!.roles, isEmpty);
  });
}
