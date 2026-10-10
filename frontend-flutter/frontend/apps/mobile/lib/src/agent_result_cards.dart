import 'dart:convert';
import 'package:flutter/material.dart';
import 'design_system.dart';

String readableExcerpt(dynamic value) {
  final text = displayValue(value);
  try {
    final parsed = jsonDecode(text);
    if (parsed is List && parsed.every((item) => item is String)) {
      return parsed.map((item) => '• $item').join('\n');
    }
  } on FormatException {/* Normal prose needs no conversion. */}
  return text;
}

List<Map<String, dynamic>> objectRows(dynamic value) => value is List
    ? value
        .whereType<Map>()
        .map((e) => <String, dynamic>{
              for (final entry in e.entries)
                if (entry.key is String) entry.key as String: entry.value
            })
        .toList()
    : [];
bool invalidObjectRows(dynamic value) =>
    value != null &&
    (value is! List ||
        value.any((e) => e is! Map || e.keys.any((key) => key is! String)));
bool invalidStringRows(dynamic value) =>
    value != null && (value is! List || value.any((e) => e is! String));
List<String> stringRows(dynamic value) => value is List
    ? value.whereType<String>().where((e) => e.trim().isNotEmpty).toList()
    : [];
String displayValue(dynamic value) => value == null
    ? ''
    : value is Map || value is List
        ? jsonEncode(value)
        : value.toString();
String runStatusLabel(dynamic value) =>
    const {
      'queued': '等待处理',
      'running': '处理中',
      'succeeded': '分析已完成',
      'failed': '运行失败',
      'cancelled': '已取消'
    }[value] ??
    '状态暂不支持';
String runKindLabel(dynamic value) =>
    const {
      'research_qa': '科研下一步',
      'stage_plan': '阶段任务建议',
      'weekly_report': '周报建议',
      'paper_guide': '论文导读',
      'risk_brief': '风险分析'
    }[value] ??
    'AI 分析';

String beijingTime(dynamic value) {
  final text = value is String ? value : '';
  if (!RegExp(r'(Z|[+-]\d{2}:?\d{2})$', caseSensitive: false).hasMatch(text)) {
    return '时间未知';
  }
  final parsed = DateTime.tryParse(text);
  if (parsed == null) return '时间未知';
  final date = parsed.toUtc().add(const Duration(hours: 8));
  String pad(int n) => n.toString().padLeft(2, '0');
  return '${date.year}-${pad(date.month)}-${pad(date.day)} ${pad(date.hour)}:${pad(date.minute)}（北京时间）';
}

String eventStatusLabel(dynamic value) =>
    const {
      'queued': '等待处理',
      'running': '处理中',
      'completed': '已完成',
      'failed': '失败',
      'cancelled': '已取消',
      'succeeded': '已完成'
    }[value] ??
    '状态未知';
String eventStepLabel(dynamic value) =>
    const {
      'claimed': '已领取任务',
      'context': '了解当前进展',
      'retrieve': '检索证据',
      'generate': '生成建议',
      'validate': '校验结果',
      'completed': '处理完成',
      'regenerate': '重新生成',
      'failed': '处理失败',
      'cancelled': '已取消'
    }[value] ??
    '处理进度';
String proposalActionLabel(dynamic value) =>
    const {
      'weekly_report.draft': '生成周报草稿',
      'note.create': '创建笔记',
      'note.draft': '创建笔记草稿',
      'reading_note.draft': '创建阅读笔记草稿',
      'mentor_feedback.draft': '创建导师反馈草稿',
      'task.create_batch': '创建阶段任务',
      'task.reschedule_batch': '调整任务时间',
      'feedback.create': '创建反馈'
    }[value] ??
    '科研行动建议';
String proposalStatusLabel(dynamic value) =>
    const {
      'pending': '待确认',
      'applied': '已应用',
      'approved': '已确认',
      'rejected': '已拒绝',
      'expired': '已过期',
      'failed': '应用失败',
      'stale': '材料已更新'
    }[value] ??
    '状态未知';
String sourceLocation(dynamic value) {
  if (value is! Map) return '位置未知';
  if (value['kind'] == 'pdf_page' && value['page'] is int) {
    return '第 ${value['page']} 页';
  }
  if (value['kind'] == 'docx_paragraph' && value['paragraph'] is int) {
    return '第 ${value['paragraph']} 段';
  }
  if (value['kind'] == 'record_field') {
    return const {
          'purpose': '实验目的',
          'parameters': '实验参数',
          'steps': '实验步骤',
          'results': '实验结果',
          'done_items': '本周完成工作',
          'issues': '遇到的问题',
          'next_plan': '下周计划',
          'help_needed': '需要导师帮助',
          'reflection': '实验反思',
          'body': '正文',
          'claims': '笔记结论',
          'questions': '待解问题',
          'title': '标题',
          'description': '说明',
          'text': '正文',
          'content': '正文',
          'requirements': '需要准备的材料',
          'acceptance_criteria': '验收要求',
          'phase': '适用阶段'
        }[value['field']] ??
        '相关内容';
  }
  return '位置未知';
}

String executionReceiptLabel(dynamic value) {
  if (value is! Map) return '执行回执暂不可用';
  final type = const {
        'weekly_report': '周报',
        'note': '笔记',
        'task': '任务',
        'feedback': '反馈'
      }[value['resource_type']] ??
      '记录';
  final state = value['status'] == 'draft' ? '草稿已创建' : '处理结果已返回';
  final ids = stringRows(value['resource_ids']);
  return '$type$state${ids.length > 1 ? ' · 共 ${ids.length} 项' : ''}';
}

String reanalysisHint(dynamic kind) =>
    const {
      'weekly_report': '需要更新周报时，请返回总览点击“生成本周周报”。',
      'paper_guide': '需要重新导读时，请返回资料选择对应文献。',
      'risk_brief': '需要更新风险分析时，请返回总览选择对应学生重新分析。',
      'stage_plan': '需要更新阶段建议时，请返回科研下一步选择阶段并重新分析。',
      'research_qa': '请返回科研下一步输入问题并重新分析。'
    }[kind] ??
    '请返回对应功能重新分析。';

class SourceReferences extends StatelessWidget {
  const SourceReferences(this.sources, {super.key});
  final dynamic sources;
  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (invalidObjectRows(sources)) const Text('引用数据格式异常，暂无法核验来源'),
          for (final source in objectRows(sources))
            ExpansionTile(
              tilePadding: EdgeInsets.zero,
              childrenPadding: const EdgeInsets.fromLTRB(12, 0, 12, 16),
              leading: const Icon(Icons.menu_book_outlined, size: 20),
              title: const Text('查看原文依据', style: TextStyle(fontSize: 13)),
              subtitle: Text(
                  '${source['title'] ?? source['source_title'] ?? '相关材料'} · ${sourceLocation(source['locator'])}'),
              children: [
                Align(
                    alignment: Alignment.centerLeft,
                    child: SelectableText('“${displayValue(source['quote'])}”',
                        style: const TextStyle(fontSize: 14, height: 1.7)))
              ],
            )
        ],
      );
}

class AgentResultCards extends StatelessWidget {
  const AgentResultCards(this.result, {super.key});
  final Map result;
  Widget section(String title, List<Widget> children) => ResearchPanel(
      title: title,
      icon: title == '接下来怎么做'
          ? Icons.auto_awesome
          : title == '我参考了这些材料'
              ? Icons.menu_book_outlined
              : Icons.info_outline,
      color: title == '需要补充'
          ? ResearchDesign.orange
          : title == '我参考了这些材料'
              ? ResearchDesign.blue
              : ResearchDesign.purple,
      child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch, children: children));
  @override
  Widget build(BuildContext context) {
    final claims = objectRows(result['claims']);
    final missing = stringRows(result['missing_inputs']);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (result['outcome'] == 'insufficient_evidence') const Text('材料不足'),
      if (result['outcome'] == 'needs_input') const Text('需要补充材料'),
      section('接下来怎么做', [
        for (final paragraph in displayValue(result['summary'])
            .split(RegExp(r'\n\s*\n|\n'))
            .where((text) => text.trim().isNotEmpty))
          Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: SelectableText(paragraph.trim(),
                  style: const TextStyle(fontSize: 15, height: 1.8))),
      ]),
      if (claims.isNotEmpty || invalidObjectRows(result['claims'])) ...[
        const SizedBox(height: 12),
        section(
            claims.isNotEmpty &&
                    claims.every(
                        (claim) => objectRows(claim['source_refs']).isEmpty)
                ? 'AI 分析要点'
                : '我参考了这些材料',
            [
              if (invalidObjectRows(result['claims']))
                const Text('依据数据格式异常，请刷新或联系维护人员'),
              for (final claim in claims) ...[
                SelectableText(readableExcerpt(claim['text']),
                    style: const TextStyle(height: 1.7)),
                SourceReferences(claim['source_refs']),
                const SizedBox(height: 12),
              ],
            ]),
      ],
      if (invalidStringRows(result['missing_inputs']))
        const Text('补充材料数据格式异常，请刷新或联系维护人员'),
      if (missing.isNotEmpty) ...[
        const SizedBox(height: 12),
        section('需要补充', [for (final value in missing) Text(value)])
      ],
    ]);
  }
}

/// Show the response and proposed actions in the conversation without opening details.
class AgentInlineAnswer extends StatelessWidget {
  const AgentInlineAnswer(this.result, {super.key});
  final Map result;

  @override
  Widget build(BuildContext context) {
    final steps = objectRows(result['next_steps']);
    final claims = objectRows(result['claims']);
    final summary = displayValue(result['summary']);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (summary.trim().isNotEmpty)
        SelectableText(summary, style: const TextStyle(height: 1.7)),
      for (final claim in claims)
        Padding(
          padding: const EdgeInsets.only(top: 10),
          child: SelectableText(readableExcerpt(claim['text']),
              style: const TextStyle(height: 1.7)),
        ),
      if (steps.isNotEmpty) ...[
        const SizedBox(height: 12),
        const Text('建议行动', style: TextStyle(fontWeight: FontWeight.w700)),
        for (final (index, step) in steps.indexed) ...[
          const SizedBox(height: 8),
          Text('${index + 1}. ${displayValue(step['title'])}',
              style: const TextStyle(fontWeight: FontWeight.w600)),
          if (displayValue(step['description']).isNotEmpty)
            SelectableText(displayValue(step['description']),
                style: const TextStyle(height: 1.7)),
          for (final criterion in stringRows(step['acceptance_criteria']))
            Text('• $criterion', style: const TextStyle(height: 1.7)),
        ],
      ],
      if (summary.isEmpty && claims.isEmpty && steps.isEmpty)
        const Text('未收到回答内容，请重试。'),
    ]);
  }
}
