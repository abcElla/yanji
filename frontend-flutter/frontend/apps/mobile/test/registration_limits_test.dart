import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:yanji_mobile/src/app.dart';
import 'package:yanji_mobile/src/data.dart';

void main() {
  for (final limits in [
    (6, 50, true),
    (12, 1, true),
    (5, 1, false),
    (13, 1, false),
    (6, 51, false),
    (6, 0, false)
  ]) {
    testWidgets('registration length password=${limits.$1} name=${limits.$2}',
        (tester) async {
      FlutterSecureStorage.setMockInitialValues({});
      final api = ApiClient('http://localhost');
      var submitted = false;
      api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
        submitted = true;
        expect(r.data.containsKey('invite_code'), false);
        h.resolve(Response(requestOptions: r, statusCode: 201, data: {
          'code': 0,
          'message': 'success',
          'data': {'user_id': 'new'}
        }));
      }));
      await tester.pumpWidget(ProviderScope(
          overrides: [apiClientProvider.overrideWithValue(api)],
          child: const YanjiApp()));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('注册账号'));
      await tester.tap(find.text('注册账号'));
      await tester.pumpAndSettle();
      for (final entry in {
        'register_username': 'new_student',
        'register_name': '名' * limits.$2,
        'register_password': 'a' * limits.$1,
        'register_confirmation': 'a' * limits.$1
      }.entries) {
        final field = find.byKey(Key(entry.key));
        await tester.ensureVisible(field);
        await tester.enterText(field, entry.value);
      }
      final submit = find.byKey(const Key('register_submit'));
      await tester.ensureVisible(submit);
      await tester.tap(submit);
      await tester.pumpAndSettle();
      expect(submitted, limits.$3);
    });
  }
}
