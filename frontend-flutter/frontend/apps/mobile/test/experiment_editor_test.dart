import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/experiment_editor.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:yanji_mobile/src/data.dart';
import 'improvement_support.dart';

void main() {
  testWidgets('saved steps keep existing numbering without duplicate prefixes',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: ExperimentSavedSummary(record: {
      'steps': ['1. 核对单位', '2、计算费用', '比较结果'],
    }))));
    expect(find.text('1. 核对单位'), findsOneWidget);
    expect(find.text('2、计算费用'), findsOneWidget);
    expect(find.text('3. 比较结果'), findsOneWidget);
    expect(find.textContaining('1. 1.'), findsNothing);
  });
  testWidgets('saved record presents parameters and steps without raw JSON',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: ExperimentSavedSummary(record: {
      'parameters': {'随机种子': 42, '运行环境与设置': '功率8瓦'},
      'steps': ['核对单位', '计算费用'],
      'file_ids': ['private-file-id']
    }))));
    expect(find.text('随机种子：42'), findsOneWidget);
    expect(find.text('运行环境与设置：功率8瓦'), findsOneWidget);
    expect(find.text('1. 核对单位'), findsOneWidget);
    expect(find.text('2. 计算费用'), findsOneWidget);
    expect(find.text('已关联 1 个文件'), findsOneWidget);
    expect(find.textContaining('private-file-id'), findsNothing);
    expect(find.textContaining('{"'), findsNothing);
  });
  test('experiment settings can be recorded without losing previous parameters',
      () {
    final body = experimentDraftBody(
        purpose: '检查数据',
        results: '没有重复编号',
        reflection: '继续检查图片',
        environment: 'Python 3.12；按物品编号分组',
        stepsText: '提取编号\n比较交集',
        existing: {
          'version': 2,
          'parameters': {'seed': 42},
          'steps': ['旧步骤'],
          'file_ids': ['file']
        });
    expect(body['parameters'], {'seed': 42, '运行环境与设置': 'Python 3.12；按物品编号分组'});
    expect(body['steps'], ['提取编号', '比较交集']);
    expect(body['file_ids'], ['file']);
  });
  testWidgets('failed readback prevents confirm using write-response version',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method == 'POST') {
        resolveData(r, h, {
          'id': 'saved',
          'purpose': '目的',
          'results': '结果',
          'version': 1,
          'status': 'draft'
        });
      } else if (r.path == '/experiments/saved') {
        rejectData(r, h, 503, '读回失败');
      } else {
        resolveData(r, h, {'experiments': []});
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(
        tester, container, const ExperimentEditorScreen(taskId: 'task'));
    await tester.enterText(find.byType(TextField).at(0), '目的');
    await tester.enterText(find.byType(TextField).at(1), '结果');
    await tapText(tester, '保存草稿');
    expect(
        tester
            .widget<OutlinedButton>(
                find.widgetWithText(OutlinedButton, '确认这份记录'))
            .onPressed,
        isNull);
    expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '保存草稿'))
            .onPressed,
        isNull);
  });
  testWidgets('saving reads actual version; failed confirmation keeps draft',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    final requests = <String>[];
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      requests.add('${r.method} ${r.path}');
      if (r.path.endsWith('/confirm')) {
        expect(r.data, {'expected_version': 7});
        rejectData(r, h, 503, '确认服务暂不可用');
      } else if (r.path == '/experiments' && r.method == 'POST') {
        expect(r.data['visibility'], 'private');
        expect(r.data['task_id'], 'task-real');
        resolveData(r, h, {'id': 'exp-real', 'version': 1});
      } else if (r.path == '/experiments/exp-real') {
        resolveData(r, h, {
          'id': 'exp-real',
          'version': 7,
          'revision': 3,
          'status': 'draft',
          'purpose': '验证目的',
          'results': '测量结果',
          'reflection': '',
          'visibility': 'private'
        });
      } else {
        resolveData(r, h, {'experiments': []});
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(
        tester, container, const ExperimentEditorScreen(taskId: 'task-real'));
    expect(find.text('请补充实验结果后再确认。'), findsOneWidget);
    expect(
        tester
            .widget<OutlinedButton>(
                find.widgetWithText(OutlinedButton, '确认这份记录'))
            .onPressed,
        isNull);
    await tester.enterText(find.byType(TextField).at(0), '验证目的');
    await tester.enterText(find.byType(TextField).at(1), '测量结果');
    await tapText(tester, '保存草稿');
    expect(requests.where((r) => r.contains('/confirm')), isEmpty);
    expect(requests, contains('GET /experiments/exp-real'));
    expect(find.text('状态：草稿'), findsOneWidget);
    await tapText(tester, '确认这份记录');
    expect(find.text('状态：草稿'), findsOneWidget);
    expect(find.textContaining('确认未完成'), findsOneWidget);
    expect(find.text('返回科研下一步，重新分析'), findsNothing);
  });
  testWidgets('409 editing keeps typed input and does not confirm',
      (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    final api = ApiClient('http://localhost');
    var confirms = 0;
    var patches = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.path.endsWith('/confirm')) confirms++;
      if (r.method == 'PATCH') {
        patches++;
        expect(r.data['expected_version'], 8);
        rejectData(r, h, 409, 'VERSION_CONFLICT');
      } else if (r.path == '/experiments/old') {
        resolveData(r, h, {
          'id': 'old',
          'purpose': '旧目的',
          'results': '旧结果',
          'reflection': '',
          'status': 'draft',
          'version': 8,
          'visibility': 'private',
          'parameters': {'seed': 1},
          'steps': ['步骤'],
          'file_ids': ['file']
        });
      } else {
        resolveData(r, h, {
          'experiments': [
            {'id': 'old', 'purpose': '旧目的', 'status': 'draft'}
          ]
        });
      }
    }));
    final container = testContainer(api);
    addTearDown(container.dispose);
    await mountPage(
        tester, container, const ExperimentEditorScreen(taskId: 'task-real'));
    await tapText(tester, '旧目的');
    await tester.enterText(find.byType(TextField).at(1), '我的新结果');
    await tapText(tester, '保存草稿');
    expect(patches, 1);
    expect(find.text('我的新结果'), findsOneWidget);
    expect(
        find.textContaining('记录已更新，输入已保留', findRichText: true), findsOneWidget);
    expect(confirms, 0);
    expect(
        tester
            .widget<OutlinedButton>(
                find.widgetWithText(OutlinedButton, '确认这份记录'))
            .onPressed,
        isNull);
  });
  testWidgets('mentor reads records and has no save or confirm action',
      (tester) async {
    final api = ApiClient('http://localhost');
    var writes = 0;
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      if (r.method != 'GET') writes++;
      if (r.path == '/experiments/shared') {
        resolveData(r, h, {
          'id': 'shared',
          'purpose': '共享实验',
          'results': '结果',
          'status': 'confirmed',
          'visibility': 'mentors',
          'version': 2
        });
      } else {
        expect(r.queryParameters['student_id'], 'student-real');
        resolveData(r, h, {
          'experiments': [
            {'id': 'shared', 'purpose': '共享实验'}
          ]
        });
      }
    }));
    final container = testContainer(api, mentor: true);
    addTearDown(container.dispose);
    await mountPage(
        tester,
        container,
        const ExperimentEditorScreen(
            taskId: 'task-real', studentId: 'student-real'));
    await tapText(tester, '共享实验');
    expect(find.text('保存草稿'), findsNothing);
    expect(find.text('确认这份记录'), findsNothing);
    expect(writes, 0);
    expect(
        tester.widget<TextField>(find.byType(TextField).first).readOnly, true);
  });
  test('editing preserves parameters, steps and files and uses version', () {
    final body = experimentDraftBody(
        purpose: ' purpose ',
        results: ' result ',
        reflection: ' r ',
        existing: {
          'version': 7,
          'revision': 3,
          'parameters': {'seed': 42},
          'steps': ['测量'],
          'file_ids': ['file1'],
          'visibility': 'mentors'
        });
    expect(body['expected_version'], 7);
    expect(body['parameters'], {'seed': 42});
    expect(body['steps'], ['测量']);
    expect(body['file_ids'], ['file1']);
    expect(body.containsKey('visibility'), false);
    expect(body['results'], 'result');
  });
  test('new records default private and cannot silently confirm empty results',
      () {
    expect(
        experimentDraftBody(
            purpose: 'p',
            results: '',
            reflection: '',
            taskId: 'task')['visibility'],
        'private');
    expect(
        canConfirmExperiment({'status': 'draft', 'version': 1, 'results': ''},
            dirty: false),
        false);
    expect(
        canConfirmExperiment(
            {'status': 'draft', 'version': 2, 'results': 'result'},
            dirty: true),
        false);
    expect(
        canConfirmExperiment(
            {'status': 'draft', 'version': 2, 'results': 'result'},
            dirty: false),
        true);
  });
}
