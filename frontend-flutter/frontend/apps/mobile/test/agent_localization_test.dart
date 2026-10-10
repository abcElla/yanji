import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/data.dart';
import 'package:yanji_mobile/src/screens.dart';
import 'package:yanji_mobile/src/research_assistant.dart';
import 'package:yanji_mobile/src/widgets.dart';
import 'package:yanji_mobile/src/agent_result_cards.dart';
import 'improvement_support.dart';

void main() {
  testWidgets('model advice without references is displayed as analysis',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
        home: Scaffold(
            body: AgentResultCards({
      'outcome': 'ok',
      'summary': '先整理参数，再比较结果。',
      'claims': [
        {'text': '建议重复三次后比较差异。', 'source_refs': []}
      ],
      'missing_inputs': [],
      'limitations': []
    }))));
    expect(find.text('AI 分析要点'), findsOneWidget);
    expect(find.text('建议重复三次后比较差异。'), findsOneWidget);
    expect(find.text('我参考了这些材料'), findsNothing);
  });
  testWidgets('AI dependency preview resolves new and existing task names',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: ProposalContent({
      'payload': {
        'tasks': [
          {
            'local_key': 't1',
            'title': '整理台灯参数',
            'depends_on_local_keys': [],
            'existing_dependency_ids': []
          },
          {
            'local_key': 't2',
            'title': '复核电费',
            'depends_on_local_keys': ['t1'],
            'existing_dependency_ids': ['old-task']
          }
        ]
      }
    }, taskTitles: {
      'old-task': '确认计算公式'
    }))));
    expect(find.text('需先完成：整理台灯参数'), findsOneWidget);
    expect(find.text('需先完成：确认计算公式'), findsOneWidget);
    expect(find.text('t1'), findsNothing);
    expect(find.text('old-task'), findsNothing);
  });
  testWidgets('an independent note is not displayed as a failed task lookup',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: ProposalContent({
      'payload': {
        'paper_id': 'paper',
        'task_id': null,
        'claims': ['原文摘录']
      }
    }))));
    expect(find.text('未关联任务'), findsOneWidget);
    expect(find.textContaining('相关任务暂时无法读取'), findsNothing);
  });
  testWidgets('history uses Beijing time and safely handles unknown dates',
      (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(
        onRequest: (r, h) => resolveData(r, h, {
              'runs': [
                {
                  'id': 'one',
                  'kind': 'weekly_report',
                  'status': 'succeeded',
                  'created_at': '2026-10-07T02:59:05Z'
                },
                {
                  'id': 'two',
                  'kind': 'research_qa',
                  'status': 'succeeded',
                  'created_at': 'invalid'
                }
              ]
            })));
    final c = testContainer(api);
    addTearDown(c.dispose);
    await mountPage(tester, c, const AgentHistoryScreen());
    expect(find.textContaining('2026-10-07 10:59（北京时间）'), findsOneWidget);
    expect(find.textContaining('时间未知'), findsOneWidget);
  });
  testWidgets('weekly detail has Chinese states and no stage warning',
      (tester) async {
    final api = ApiClient('http://localhost');
    api.dio.interceptors.add(InterceptorsWrapper(onRequest: (r, h) {
      resolveData(
          r,
          h,
          r.path.endsWith('/events')
              ? {
                  'events': r.queryParameters['after_seq'] == 0
                      ? [
                          {'step': 'claimed', 'status': 'completed'}
                        ]
                      : [],
                  'next_seq': 1
                }
              : r.path == '/proposals'
                  ? {'proposals': []}
                  : {
                      'id': 'one',
                      'kind': 'weekly_report',
                      'status': 'succeeded',
                      'subject': {'student_id': 'student-real'},
                      'input': {'week_start': '2026-10-05'}
                    });
    }));
    final c = testContainer(api);
    addTearDown(c.dispose);
    await mountPage(tester, c, const AgentRunScreen(runId: 'one'));
    expect(find.text('运行状态：分析已完成'), findsOneWidget);
    expect(find.text('已领取任务'), findsNothing);
    await tester.tap(find.text('查看处理过程'));
    await tester.pumpAndSettle();
    expect(find.text('已领取任务'), findsOneWidget);
    expect(find.text('已完成'), findsOneWidget);
    expect(find.textContaining('缺少分析参数'), findsNothing);
    expect(find.textContaining('生成本周周报'), findsOneWidget);
  });
  testWidgets('receipt and citation locator remain traceable without raw JSON',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: ProposalContent({
      'payload': {},
      'source_refs': [
        {
          'source_id': 'source1',
          'source_revision': 1,
          'locator': {'kind': 'record_field', 'field': 'results'},
          'quote': '实测结果'
        }
      ],
      'execution_receipt': {
        'status': 'draft',
        'resource_type': 'weekly_report',
        'resource_ids': ['report1']
      }
    }))));
    expect(find.textContaining('实验结果'), findsOneWidget);
    expect(find.textContaining('周报草稿已创建'), findsOneWidget);
    expect(find.textContaining('report1'), findsNothing);
    expect(find.textContaining('resource_ids'), findsNothing);
  });
  testWidgets('weekly item citations are displayed independently',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: ProposalContent({
      'payload': {
        'done_items': [
          {
            'text': '逐条实验结论',
            'source_refs': [
              {
                'source_id': 'item-source',
                'source_revision': 2,
                'locator': {'kind': 'record_field', 'field': 'results'},
                'quote': '独立条目引用'
              }
            ]
          }
        ]
      },
      'source_refs': []
    }))));
    expect(find.textContaining('item-source'), findsNothing);
    await tester.tap(find.text('查看原文依据'));
    await tester.pumpAndSettle();
    expect(find.textContaining('独立条目引用'), findsOneWidget);
  });
  testWidgets('reading note payload citations remain visible', (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: ProposalContent({
      'payload': {
        'claims': ['笔记结论'],
        'questions': [],
        'source_refs': [
          {
            'source_id': 'note-source',
            'source_revision': 1,
            'locator': {'kind': 'pdf_page', 'page': 2},
            'quote': '笔记独立引用'
          }
        ]
      },
      'source_refs': []
    }))));
    expect(find.textContaining('note-source'), findsNothing);
    await tester.tap(find.text('查看原文依据'));
    await tester.pumpAndSettle();
    expect(find.textContaining('笔记独立引用'), findsOneWidget);
  });
}
