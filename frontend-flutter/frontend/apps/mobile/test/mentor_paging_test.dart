import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:yanji_mobile/src/data.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('mentor search requests one page and preserves keyword for the cursor',
      () async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    final requests = <Map<String, dynamic>>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      Map<String, dynamic> data;
      switch (r.path) {
        case '/auth/login':
          data = {'access_token': 'mock'};
        case '/me':
          data = {
            'user': {
              'id': 'student',
              'display_name': '学生',
              'account_role': 'student'
            },
            'allowed_roles': ['student'],
            'collaboration_enabled': false
          };
        case '/mentors':
          requests.add(Map.from(r.queryParameters));
          data = {
            'mentors': List.generate(
                r.queryParameters['cursor'] == null ? 5 : 1,
                (i) => {
                      'mentor_id': 'mentor$i',
                      'display_name': '同名老师',
                      'lab_id': 'lab$i'
                    }),
            'next_cursor': r.queryParameters['cursor'] == null ? 'page2' : null
          };
        default:
          throw StateError(r.path);
      }
      h.resolve(Response(
          requestOptions: r,
          statusCode: 200,
          data: {'code': 0, 'data': data, 'message': 'success'}));
    }));
    final container = ProviderContainer(
        overrides: [apiClientProvider.overrideWithValue(api)]);
    addTearDown(container.dispose);
    await container.read(sessionProvider.notifier).login('student', 'mockpass');
    final first = await container
        .read(mentorsProvider((keyword: ' 同名老师 ', cursor: null)).future);
    expect(first['mentors'], hasLength(5));
    expect(requests, hasLength(1),
        reason: 'must not fetch all pages automatically');
    expect(requests.first, {'keyword': '同名老师', 'limit': 5});
    final second = await container.read(mentorsProvider(
        (keyword: '同名老师', cursor: first['next_cursor'] as String)).future);
    expect(second['mentors'], hasLength(1));
    expect(requests.last, {'keyword': '同名老师', 'limit': 5, 'cursor': 'page2'});
  });
}
