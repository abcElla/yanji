import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yanji_mobile/src/agent_result_cards.dart';

void main() {
  testWidgets('insufficient evidence is not failure; full citations are shown',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: SingleChildScrollView(
                child: AgentResultCards({
      'outcome': 'insufficient_evidence',
      'summary': '需要新的测量',
      'claims': [
        {
          'text': '现有测量',
          'source_refs': [
            {
              'source_id': 'source1',
              'source_revision': 2,
              'locator': {'page': 3},
              'quote': '完整引用'
            }
          ]
        }
      ],
      'missing_inputs': ['测试集结果'],
      'limitations': ['样本不足']
    })))));
    expect(find.text('材料不足'), findsOneWidget);
    await tester.tap(find.text('查看原文依据'));
    await tester.pumpAndSettle();
    expect(find.textContaining('完整引用'), findsOneWidget);
    expect(find.textContaining('source1'), findsNothing);
    expect(find.text('测试集结果'), findsOneWidget);
    expect(find.text('样本不足'), findsNothing);
    expect(find.text('当前限制'), findsNothing);
  });
  testWidgets(
      'conversation displays concrete steps and empty evidence cards are hidden',
      (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: AgentInlineAnswer({
      'summary': '先固定划分再对比基线。',
      'next_steps': [
        {
          'title': '记录基线配置',
          'description': '记录训练集划分和随机种子。',
          'acceptance_criteria': ['配置可复现'],
        }
      ],
    }))));
    expect(find.text('先固定划分再对比基线。'), findsOneWidget);
    expect(find.text('1. 记录基线配置'), findsOneWidget);
    expect(find.text('记录训练集划分和随机种子。'), findsOneWidget);
    expect(find.text('• 配置可复现'), findsOneWidget);
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: AgentResultCards({
      'summary': '建议先确定实验方案。',
      'claims': [],
      'limitations': ['待核对'],
    }))));
    expect(find.text('我参考了这些材料'), findsNothing);
    expect(find.text('没有可核验依据'), findsNothing);
    expect(find.text('当前限制'), findsNothing);
  });
  testWidgets('malformed optional fields do not crash', (tester) async {
    await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
            body: AgentResultCards({
      'summary': '旧结果',
      'claims': 'bad',
      'limitations': {},
      'missing_inputs': [null, {}]
    }))));
    expect(tester.takeException(), isNull);
    expect(find.textContaining('数据格式异常'), findsWidgets);
    expect(find.text('没有可核验依据'), findsNothing);
  });
}
