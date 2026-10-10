import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/models.dart';

class DashboardSession extends SessionController {
  DashboardSession(super.ref) {
    state = const AsyncData(Session(
        user: {'id': 'mentor'}, roles: ['mentor'], collaborationEnabled: true));
  }
}

void main() {
  test('mentor overview stays global after selecting a work student', () async {
    final api = ApiClient('http://localhost');
    final queries = <Map<String, dynamic>>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      queries.add(Map.from(r.queryParameters));
      h.resolve(Response(requestOptions: r, statusCode: 200, data: {
        'code': 0,
        'data': {
          'counters': {'student_count': 4}
        },
        'message': 'success'
      }));
    }));
    final container = ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(api),
      sessionProvider.overrideWith((ref) => DashboardSession(ref)),
      roleProvider.overrideWith((ref) => UserRole.mentor),
      selectedStudentProvider.overrideWith((ref) => 'student1'),
    ]);
    addTearDown(container.dispose);
    await container.read(dashboardProvider.future);
    expect(queries, [
      {'role': 'mentor'}
    ]);
  });
}
