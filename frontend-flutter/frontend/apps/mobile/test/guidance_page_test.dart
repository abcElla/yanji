import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/guidance_page.dart';
import 'improvement_support.dart';

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  test('guidance retries keep the same save identity after a network failure',
      () async {
    final api = ApiClient('http://localhost');
    final keys = <String>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      keys.add(r.headers['Idempotency-Key'] as String);
      if (keys.length == 1) {
        rejectData(r, h, 503, '暂时没有保存成功，请重试。');
      } else {
        resolveData(r, h, {'id': 'saved-once'});
      }
    }));
    final body = {
      'title': '数据划分',
      'phase': '实验研究',
      'content': '相同物品放在同一划分中',
      'requirements': []
    };
    await expectLater(api.write('/guidance', body), throwsA(isA<ApiFailure>()));
    expect((await api.write('/guidance', body))['id'], 'saved-once');
    expect(keys, hasLength(2));
    expect(keys[0], keys[1]);
  });
  testWidgets('mentor guidance validates input and saves readable content',
      (tester) async {
    final api = ApiClient('http://localhost');
    Map<String, dynamic>? saved;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') saved = Map<String, dynamic>.from(r.data as Map);
      resolveData(
          r, h, r.method == 'GET' ? {'items': []} : {'id': 'rule', ...?saved});
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(tester, container, const GuidancePage());
    await tester.tap(find.text('添加科研指导'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存指导'));
    await tester.pumpAndSettle();
    expect(saved, isNull);
    expect(find.text('请填写标题'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextFormField, '指导标题'), '开题前检查');
    await tester.enterText(
        find.widgetWithText(TextFormField, '指导内容'), '先说明研究问题，再比较相关文献。');
    await tester.enterText(
        find.widgetWithText(TextFormField, '需要准备的材料（每行一项）'), '研究问题\n文献比较');
    await tester.tap(find.text('保存指导'));
    await tester.pumpAndSettle();
    expect(saved!['requirements'], ['研究问题', '文献比较']);
    expect(saved!['title'], '开题前检查');
  });
  testWidgets('multi lab mentor chooses the destination before saving',
      (tester) async {
    final api = ApiClient('http://localhost');
    Map<String, dynamic>? saved;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') saved = Map<String, dynamic>.from(r.data);
      resolveData(r, h, {'items': [], 'id': 'new'});
    }));
    final container = testContainer(api, mentor: true, memberships: [
      {'lab_id': 'lab-a', 'lab_name': '视觉实验室', 'role': 'mentor'},
      {'lab_id': 'lab-b', 'lab_name': '软件实验室', 'role': 'mentor'}
    ]);
    addTearDown(container.dispose);
    await mountPage(tester, container, const GuidanceEditor());
    await tester.enterText(find.widgetWithText(TextFormField, '指导标题'), '开题准备');
    await tester.enterText(
        find.widgetWithText(TextFormField, '指导内容'), '先确认研究问题。');
    await tapText(tester, '保存指导');
    expect(saved, isNull);
    expect(find.text('请选择所属实验室'), findsOneWidget);
    await tester.tap(find.byType(DropdownButtonFormField<String>).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('软件实验室').last);
    await tester.pumpAndSettle();
    await tapText(tester, '保存指导');
    expect(saved!['lab_id'], 'lab-b');
  });
  testWidgets('guidance disappears when the session is cleared',
      (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) => resolveData(r, h, {
              'items': [
                {'title': '原实验室指导'}
              ]
            })));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(tester, container, const GuidancePage());
    expect(find.text('原实验室指导'), findsOneWidget);
    (container.read(sessionProvider.notifier) as TestSession).clear();
    await tester.pumpAndSettle();
    expect(find.text('原实验室指导'), findsNothing);
  });
}
