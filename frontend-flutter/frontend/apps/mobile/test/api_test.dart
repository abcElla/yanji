import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('AI events follow next_seq rather than cursor pagination', () async {
    final api = ApiClient('http://localhost');
    final requested = <int>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      final seq = r.queryParameters['after_seq'] as int;
      requested.add(seq);
      h.resolve(Response(requestOptions: r, data: {
        'code': 0,
        'message': 'success',
        'data': {
          'events': seq == 0
              ? [
                  {'seq': 1, 'type': 'first'}
                ]
              : seq == 1
                  ? [
                      {'seq': 2, 'type': 'last'}
                    ]
                  : [],
          'next_seq': seq == 0 ? 1 : 2,
        }
      }));
    }));
    final events = await api.runEvents('run-id');
    expect(requested, [0, 1, 2]);
    expect(events.map((e) => e['seq']), [1, 2]);
  });
  test('report edit preserves all required sections and existing evidence', () {
    final original = {
      'version': 4,
      'done_items': [
        {
          'id': 'old',
          'text': '既有事实',
          'origin': 'agent',
          'source_refs': [
            {'source_id': 'source'}
          ]
        }
      ],
      'issues': [
        {'id': 'issue', 'text': '问题', 'origin': 'human', 'source_refs': []}
      ],
      'next_plan': [],
      'help_needed': [],
    };
    final patch = reportItemPatch(original, 'next_plan', '新计划');
    expect(patch.keys.toSet(), {
      'expected_version',
      'done_items',
      'issues',
      'next_plan',
      'help_needed'
    });
    expect(patch['done_items'], original['done_items']);
    expect(patch['issues'], original['issues']);
    expect((patch['next_plan'] as List).single['text'], '新计划');
    expect(patch['expected_version'], 4);
  });
  test('login sends device identity, stores token and uses server roles',
      () async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    final container = ProviderContainer(
        overrides: [apiClientProvider.overrideWithValue(api)]);
    addTearDown(container.dispose);
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path == '/auth/login') {
        expect(r.data['username'], 'student');
        expect(r.data['installation_id'], matches(RegExp(r'^[a-f0-9-]{36}$')));
        expect(r.data['device_label'], isNotEmpty);
        h.resolve(Response(requestOptions: r, data: {
          'code': 0,
          'message': 'success',
          'data': {'access_token': 'opaque-token'}
        }));
      } else {
        expect(r.path, '/me');
        expect(r.headers['Authorization'], 'Bearer opaque-token');
        h.resolve(Response(requestOptions: r, data: {
          'code': 0,
          'message': 'success',
          'data': {
            'user': {'id': 'user-id', 'display_name': '老师'},
            'allowed_roles': ['mentor'],
            'memberships': []
          }
        }));
      }
    }));
    await container.read(sessionProvider.notifier).login(' student ', 'secret');
    expect(container.read(sessionProvider).valueOrNull?.name, '老师');
    expect(container.read(roleProvider), UserRole.mentor);
    expect(await const FlutterSecureStorage().read(key: 'access_token'),
        'opaque-token');
  });
  test('unwraps API envelopes and sends bearer authorization', () async {
    final api = ApiClient('http://192.168.8.230:8000/');
    api.setAccessToken('test-token');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      expect(r.uri.toString(), 'http://192.168.8.230:8000/api/v1/me');
      expect(r.headers['Authorization'], 'Bearer test-token');
      h.resolve(Response(requestOptions: r, data: {
        'code': 0,
        'message': 'success',
        'data': {
          'user': {'id': 'u'}
        }
      }));
    }));
    expect((await api.get('/me'))['user']['id'], 'u');
  });
  test('backend errors remain visible and do not become demo success',
      () async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      h.reject(DioException(
          requestOptions: r,
          response: Response(
            requestOptions: r,
            statusCode: 401,
            data: {
              'code': 40100,
              'message': '账号或密码错误',
              'data': {
                'error_code': 'INVALID_CREDENTIALS',
                'retryable': false,
                'details': {}
              }
            },
          ),
          type: DioExceptionType.badResponse));
    }));
    await expectLater(
        api.post('/auth/login', {}),
        throwsA(
            isA<ApiFailure>().having((e) => e.message, 'message', '账号或密码错误')));
  });
  test('unknown task statuses cannot be silently treated as todo', () {
    final task = ResearchTask.fromJson(
        {'id': 't', 'title': 'Task', 'status': 'future_status', 'version': 3});
    expect(task.status, TaskStatus.unsupported);
    expect(task.version, 3);
  });
}
