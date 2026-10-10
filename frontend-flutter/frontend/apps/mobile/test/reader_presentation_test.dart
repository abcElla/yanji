import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/agent_result_cards.dart';

void main() {
  test('guidance reference locations use readable Chinese', () {
    expect(sourceLocation({'kind': 'record_field', 'field': 'requirements'}),
        '需要准备的材料');
    expect(
        sourceLocation(
            {'kind': 'record_field', 'field': 'unknown_internal_field'}),
        '相关内容');
  });
  test(
      'guidance excerpts display a readable list while original quotes remain unchanged',
      () {
    const original = '["研究问题与范围","相关方法比较"]';
    expect(readableExcerpt(original), '• 研究问题与范围\n• 相关方法比较');
    expect(readableExcerpt('先说明研究问题。'), '先说明研究问题。');
  });
  testWidgets('references hide technical identifiers but retain original quote',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: SourceReferences([
      {
        'source_id': 'private-technical-id',
        'source_revision': 3,
        'locator': {'kind': 'pdf_page', 'page': 2},
        'quote': '准确率为 80%，仅限当前测试集。'
      }
    ]))));
    expect(find.textContaining('private-technical-id'), findsNothing);
    expect(find.textContaining('准确率为 80%'), findsNothing);
    await tester.tap(find.textContaining('查看原文依据'));
    await tester.pumpAndSettle();
    expect(find.textContaining('准确率为 80%，仅限当前测试集。'), findsOneWidget);
    expect(find.textContaining('第 2 页'), findsWidgets);
  });
  test('execution receipts communicate outcomes without database ids', () {
    expect(
        executionReceiptLabel({
          'resource_type': 'task',
          'status': 'applied',
          'resource_ids': ['internal-task-uuid']
        }),
        isNot(contains('internal-task-uuid')));
  });
  testWidgets(
      'AI summary separates paragraphs and gives readable section names',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: SingleChildScrollView(
                child: AgentResultCards({
      'summary': '先完成文献比较。\n\n再填写基线实验记录。',
      'claims': [],
      'missing_inputs': ['基线实验结果'],
      'limitations': []
    })))));
    expect(find.text('先完成文献比较。'), findsOneWidget);
    expect(find.text('再填写基线实验记录。'), findsOneWidget);
    expect(find.text('接下来怎么做'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
