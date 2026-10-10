import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'data.dart';
import 'models.dart';
import 'design_system.dart';
import 'agent_result_cards.dart';

class AiActionPanel extends ConsumerStatefulWidget {
  const AiActionPanel(
      {required this.title,
      required this.kind,
      this.stageId,
      this.taskId,
      this.paperId,
      this.reportId,
      this.question,
      this.description = '结合已保存的任务、记录和材料分析。建议需要你核对后确认。',
      this.enabled = true,
      this.action = '开始分析',
      super.key});
  final String title, kind, action;
  final String? stageId, taskId, paperId, reportId, question;
  final String description;
  final bool enabled;
  @override
  ConsumerState<AiActionPanel> createState() => _AiActionPanelState();
}

class _AiActionPanelState extends ConsumerState<AiActionPanel> {
  String? runId, error;
  Map<String, dynamic>? run;
  bool busy = false;
  int epoch = 0;
  @override
  void dispose() {
    epoch++;
    super.dispose();
  }

  String? scope() {
    final session = ref.read(sessionProvider).valueOrNull;
    return collaborationScope(
        session, ref.read(roleProvider), ref.read(selectedStudentProvider));
  }

  void clear() {
    epoch++;
    setState(() {
      runId = null;
      run = null;
      error = null;
      busy = false;
    });
  }

  @override
  void didUpdateWidget(AiActionPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.stageId != widget.stageId ||
        oldWidget.taskId != widget.taskId ||
        oldWidget.paperId != widget.paperId ||
        oldWidget.reportId != widget.reportId ||
        oldWidget.question != widget.question ||
        oldWidget.kind != widget.kind) {
      epoch++;
      runId = null;
      run = null;
      error = null;
      busy = false;
    }
  }

  Future<void> launch({bool existing = false}) async {
    if (busy || !widget.enabled || scope() == null) return;
    final requestedScope = scope()!;
    final ticket = epoch;
    bool valid() => mounted && ticket == epoch && requestedScope == scope();
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final api = ref.read(apiClientProvider);
      if (!existing || runId == null) {
        if (widget.kind == 'stage_plan' &&
            (ref.read(roleProvider) != UserRole.mentor ||
                widget.stageId == null)) {
          throw const ApiFailure('请选择阶段，再生成阶段任务建议');
        }
        final body = {
          'kind': widget.kind,
          'subject': {
            'student_id': requestedScope.split('|').last,
            if (widget.stageId != null) 'stage_id': widget.stageId,
            if (widget.taskId != null) 'task_id': widget.taskId,
            if (widget.paperId != null) 'paper_id': widget.paperId
          },
          'input': widget.kind == 'risk_brief'
              ? {
                  'horizon_days': 7,
                  if (widget.reportId != null) 'report_id': widget.reportId
                }
              : widget.kind == 'weekly_report'
                  ? {
                      'week_start': widget.reportId == null
                          ? currentWeek()
                          : (await api
                                  .get('/weekly-reports/${widget.reportId}'))[
                              'week_start'],
                      if (widget.reportId != null) 'report_id': widget.reportId
                    }
                  : widget.kind == 'paper_guide'
                      ? <String, dynamic>{}
                      : {
                          'question':
                              widget.question ?? '请对照当前阶段目标和已有材料，说明缺少的证据与下一步。'
                        },
          'client_context': <String, dynamic>{},
        };
        final created = await api.write('/agent-runs', body);
        if (!valid()) return;
        if (created['run_id'] is! String) {
          throw const ApiFailure('分析暂时无法创建，请重试');
        }
        runId = created['run_id'];
        run = {'status': created['status']};
      }
      if (!mounted || !valid()) return;
      await context.push('/agent?run_id=${Uri.encodeComponent(runId!)}');
      if (!valid()) return;
      final latest = await api.get('/agent-runs/$runId');
      if (!valid()) return;
      await verifyReadableRunSources(api, latest['result']);
      if (valid()) setState(() => run = latest);
    } catch (e) {
      if (valid()) {
        setState(() {
          if (isAccessFailure(e)) {
            run = null;
            runId = null;
          }
          error = e.toString();
        });
      }
    } finally {
      if (valid()) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(sessionProvider, (a, b) {
      if (!b.isLoading &&
          (a?.valueOrNull?.authorizationKey !=
                  b.valueOrNull?.authorizationKey ||
              b.valueOrNull?.collaborationEnabled != true)) {
        clear();
      }
    });
    ref.listen(selectedStudentProvider, (a, b) {
      if (a != b) clear();
    });
    ref.listen(roleProvider, (a, b) {
      if (a != b) clear();
    });
    ref.watch(sessionProvider);
    ref.watch(selectedStudentProvider);
    ref.watch(roleProvider);
    final result = run?['result'] is Map
        ? Map<String, dynamic>.from(run!['result'])
        : null;
    final status = error != null
        ? '分析失败，请重试'
        : busy
            ? '正在分析'
            : result?['outcome'] == 'insufficient_evidence' ||
                    result?['outcome'] == 'needs_input'
                ? '材料不足，请补充后重新分析'
                : run == null
                    ? '尚未生成'
                    : runStatusLabel(run!['status']);
    final enabled = widget.enabled && scope() != null && !busy;
    return Container(
        width: double.infinity,
        margin: const EdgeInsets.symmetric(vertical: 8),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: const Color(0xFFE1DAFF)),
            gradient: const LinearGradient(
                colors: [Color(0xFFF0EDFF), Color(0xFFFCFBFF)])),
        child:
            Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            const ResearchIcon(Icons.auto_awesome),
            const SizedBox(width: 12),
            Expanded(
                child: Text(widget.title,
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w700)))
          ]),
          const SizedBox(height: 10),
          Text(status,
              style: const TextStyle(
                  color: ResearchDesign.purple, fontWeight: FontWeight.w600)),
          Text(widget.description),
          if (scope() == null) const Text('请先选择已绑定的学生。'),
          if (error != null)
            Text(error!, style: const TextStyle(color: ResearchDesign.red)),
          if (busy) const LinearProgressIndicator(),
          Wrap(spacing: 8, runSpacing: 8, children: [
            TextButton.icon(
                onPressed: enabled ? () => launch() : null,
                icon: const Icon(Icons.auto_awesome, size: 18),
                label: Text(runId == null ? widget.action : '重新分析')),
            if (runId != null)
              TextButton(
                  onPressed: enabled ? () => launch(existing: true) : null,
                  child: const Text('查看分析与待确认建议'))
          ]),
          if (result != null)
            ExpansionTile(
                tilePadding: EdgeInsets.zero,
                title: const Text('展开分析结果与依据'),
                children: [AgentResultCards(result)]),
        ]));
  }
}
