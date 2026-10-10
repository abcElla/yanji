import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:yanji_mobile/src/data.dart';

// Explicit opt-in only; credentials are supplied by the calling process.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('live public API contract and unjoined account permissions', () async {
    HttpOverrides.global = null;
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient(Platform.environment['YANJI_TEST_URL'] ?? apiBaseUrl);
    final container = ProviderContainer(
        overrides: [apiClientProvider.overrideWithValue(api)]);
    addTearDown(container.dispose);
    addTearDown(() => api.dio.close());
    final controller = container.read(sessionProvider.notifier);
    final config = await api
        .get('/client-config', query: {'platform': 'android', 'build': 1});
    expect(config['api_contract'], isNotNull);
    await controller.login(Platform.environment['YANJI_TEST_USER']!,
        Platform.environment['YANJI_TEST_PASSWORD']!);
    addTearDown(() async {
      if (container.read(sessionProvider).valueOrNull != null) {
        await controller.logout();
      }
    });
    final session = container.read(sessionProvider).valueOrNull!;
    final sessions = await api.requestData('GET', '/auth/sessions');
    expect(sessions, isA<List>());
    expect((sessions as List).where((s) => s['current'] == true), hasLength(1));
    await api.list('/papers');
    await api.list('/notifications');
    if (!session.collaborationEnabled) {
      for (final path in [
        '/tasks',
        '/weekly-reports',
        '/proposals',
        '/students'
      ]) {
        try {
          await api.get(path);
          fail('$path must not allow an account without business roles');
        } on ApiFailure catch (e) {
          expect(e.status, 403, reason: path);
          expect(e.code, 'FORBIDDEN', reason: path);
          expect(e.message, isNot('请求失败，请重试'));
          expect(e.requestId, isNotEmpty);
        }
      }
    }
    // Invalid username guarantees validation fails before creating any account.
    await expectLater(
        api.post('/auth/register',
            {'username': '', 'password': 'abcdef', 'display_name': '接口验证'}),
        throwsA(isA<ApiFailure>()
            .having((e) => e.status, 'HTTP', 422)
            .having((e) => e.code, 'business code', 'VALIDATION_ERROR')));
    await controller.logout();
    expect(container.read(sessionProvider).valueOrNull, isNull);
  }, skip: Platform.environment['YANJI_LIVE_TEST'] != '1');
}
