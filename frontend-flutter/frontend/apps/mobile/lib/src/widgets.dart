import 'package:flutter/material.dart';

import 'app.dart';
import 'models.dart';
import 'agent_result_cards.dart';
import 'design_system.dart';

class ProposalContent extends StatelessWidget {
  const ProposalContent(this.proposal, {this.taskTitles = const {}, super.key});
  final Map<String, dynamic> proposal;
  final Map<String, String> taskTitles;
  @override
  Widget build(BuildContext context) {
    final payload =
        proposal['payload'] is Map ? proposal['payload'] as Map : const {};
    final localTaskTitles = <String, String>{
      for (final task in payload['tasks'] is List ? payload['tasks'] : const [])
        if (task is Map &&
            task['local_key'] is String &&
            task['title'] is String)
          task['local_key'] as String: task['title'] as String,
    };
    List<String> dependencyLabels(dynamic value, Map<String, String> titles) =>
        value is List
            ? [
                for (final id in value)
                  titles[id.toString()] == null
                      ? '前置任务暂时无法读取，请重新加载'
                      : '需先完成：${titles[id.toString()]}'
              ]
            : [];
    const labels = {
      'title': '标题',
      'description': '说明',
      'type': '类型',
      'due_at': '截止时间',
      'acceptance_criteria': '验收标准',
      'claims': '笔记结论',
      'questions': '待解问题',
      'done_items': '已完成',
      'issues': '遇到的问题',
      'next_plan': '下周计划',
      'help_needed': '需要帮助',
      'body': '反馈内容',
      'reason': '理由',
      'tasks': '任务',
      'changes': '调整',
      'items': '任务调整',
      'task_id': '相关任务',
      'week_start': '周报周期',
      'new_due_at': '新截止时间',
      'depends_on_local_keys': '前置任务',
      'existing_dependency_ids': '已有前置任务',
    };
    List<Widget> content(dynamic value) {
      if (value is List) return value.expand(content).toList();
      if (value is Map) {
        if (value['text'] != null) {
          return [
            Text(value['text'].toString()),
            SourceReferences(value['source_refs'])
          ];
        }
        return [
          if (value['source_refs'] != null)
            SourceReferences(value['source_refs']),
          for (final entry in value.entries)
            if (labels.containsKey(entry.key) &&
                (!const {'depends_on_local_keys', 'existing_dependency_ids'}
                        .contains(entry.key) ||
                    entry.value is List &&
                        (entry.value as List).isNotEmpty)) ...[
              Text(labels[entry.key]!,
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              ...content(entry.key == 'depends_on_local_keys'
                  ? dependencyLabels(entry.value, localTaskTitles)
                  : entry.key == 'existing_dependency_ids'
                      ? dependencyLabels(entry.value, taskTitles)
                      : entry.key == 'task_id'
                          ? entry.value == null
                              ? '未关联任务'
                              : taskTitles[entry.value.toString()] ??
                                  '相关任务暂时无法读取，请重新加载'
                          : ['due_at', 'new_due_at'].contains(entry.key)
                              ? beijingTime(entry.value)
                              : entry.key == 'type'
                                  ? const {
                                        'reading': '阅读',
                                        'experiment': '实验',
                                        'writing': '写作',
                                        'other': '其他'
                                      }[entry.value] ??
                                      '其他'
                                  : entry.value),
            ]
        ];
      }
      return value == null ? [] : [Text(value.toString())];
    }

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const SizedBox(height: 12),
      const Text('将应用的内容', style: TextStyle(fontWeight: FontWeight.bold)),
      ...content(payload),
      SourceReferences(proposal['source_refs']),
      if (proposal['execution_receipt'] != null)
        SelectableText(
            '保存结果：${executionReceiptLabel(proposal['execution_receipt'])}'),
    ]);
  }
}

class SectionHeader extends StatelessWidget {
  const SectionHeader(this.title, {this.action, super.key});
  final String title;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Expanded(
              child: Text(title,
                  style: Theme.of(context)
                      .textTheme
                      .titleLarge
                      ?.copyWith(fontWeight: FontWeight.w700))),
          if (action != null) action!,
        ],
      );
}

class StatusPill extends StatelessWidget {
  const StatusPill(this.status, {super.key});
  final TaskStatus status;

  @override
  Widget build(BuildContext context) {
    final color = statusColor(status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
          color: color.withValues(alpha: .10),
          borderRadius: BorderRadius.circular(99)),
      child: Text(statusLabel(status),
          style: TextStyle(
              color: color, fontSize: 12, fontWeight: FontWeight.w700)),
    );
  }
}

class TaskCard extends StatelessWidget {
  const TaskCard({required this.task, required this.onTap, super.key});
  final ResearchTask task;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Card(
        child: InkWell(
          borderRadius: BorderRadius.circular(20),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(18),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                ResearchIcon(
                    task.type == '实验'
                        ? Icons.science_outlined
                        : task.type == '阅读'
                            ? Icons.menu_book_outlined
                            : task.type == '写作'
                                ? Icons.edit_note
                                : Icons.task_alt,
                    color: task.status == TaskStatus.blocked
                        ? ResearchDesign.orange
                        : task.type == '实验'
                            ? ResearchDesign.blue
                            : task.type == '阅读'
                                ? ResearchDesign.teal
                                : ResearchDesign.purple),
                const SizedBox(width: 12),
                Expanded(
                    child: Text(task.title,
                        style: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.w700))),
                const SizedBox(width: 12),
                StatusPill(task.status),
              ]),
              const SizedBox(height: 10),
              if (task.description.isNotEmpty) ...[
                Text(task.description,
                    style: const TextStyle(
                        color: ResearchDesign.muted, height: 1.6)),
                const SizedBox(height: 10)
              ],
              Text('${task.type} · ${task.dueLabel}',
                  style: const TextStyle(color: Color(0xFF717180))),
              if (task.blockingReason != null) ...[
                const SizedBox(height: 10),
                Row(children: [
                  const Icon(Icons.warning_amber_rounded,
                      size: 18, color: Color(0xFFD45A3A)),
                  const SizedBox(width: 6),
                  Expanded(
                      child: Text(task.blockingReason!,
                          style: const TextStyle(color: Color(0xFFD45A3A)))),
                ]),
              ],
            ]),
          ),
        ),
      );
}

class EvidenceCard extends StatelessWidget {
  const EvidenceCard(this.evidence, {super.key});
  final EvidenceRef evidence;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(18),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(Icons.link_rounded, color: Color(0xFF6C4CF1)),
              const SizedBox(width: 8),
              Expanded(
                  child: Text(evidence.title,
                      style: const TextStyle(fontWeight: FontWeight.w800))),
              const Icon(Icons.chevron_right),
            ]),
            const SizedBox(height: 7),
            Text(evidence.location,
                style: const TextStyle(color: Color(0xFF717180), fontSize: 13)),
            const SizedBox(height: 10),
            Text('“${evidence.quote}”', style: const TextStyle(height: 1.55)),
          ]),
        ),
      );
}

class MetricCard extends StatelessWidget {
  const MetricCard(
      {required this.label,
      required this.value,
      required this.icon,
      required this.tint,
      super.key});
  final String label;
  final String value;
  final IconData icon;
  final Color tint;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                    color: tint.withValues(alpha: .12),
                    borderRadius: BorderRadius.circular(12)),
                child: Icon(icon, color: tint, size: 20)),
            const SizedBox(height: 8),
            Text(value,
                style:
                    const TextStyle(fontSize: 24, fontWeight: FontWeight.w900)),
            const SizedBox(height: 2),
            Text(label, style: const TextStyle(color: Color(0xFF717180))),
          ]),
        ),
      );
}
