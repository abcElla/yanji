import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';

class ScopeSession extends SessionController {
  ScopeSession(super.ref, UserRole role) {
    state = AsyncData(Session(
        user: {'id': 'user'}, roles: [role.name], collaborationEnabled: true));
  }
}

void main() {
  for (final scenario in [
    (UserRole.mentor, null),
    (UserRole.mentor, 'student1'),
    (UserRole.student, null)
  ]) {
    test('report scope $scenario', () async {
      final api = ApiClient('http://localhost');
      final queries = <Map<String, dynamic>>[];
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        expect(r.path, '/weekly-reports');
        queries.add(Map.from(r.queryParameters));
        h.resolve(Response(requestOptions: r, statusCode: 200, data: {
          'code': 0,
          'data': {'reports': [], 'next_cursor': null},
          'message': 'success'
        }));
      }));
      final container = ProviderContainer(overrides: [
        apiClientProvider.overrideWithValue(api),
        sessionProvider.overrideWith((ref) => ScopeSession(ref, scenario.$1)),
        roleProvider.overrideWith((ref) => scenario.$1),
        selectedStudentProvider.overrideWith((ref) => scenario.$2),
      ]);
      addTearDown(container.dispose);
      expect(await container.read(reportsProvider.future), isEmpty);
      if (scenario.$1 == UserRole.mentor && scenario.$2 == null) {
        expect(queries, isEmpty,
            reason: 'mentor must select a student before requesting reports');
      } else {
        expect(queries, [
          {'limit': 100, if (scenario.$2 != null) 'student_id': scenario.$2}
        ]);
      }
    });
  }
}
