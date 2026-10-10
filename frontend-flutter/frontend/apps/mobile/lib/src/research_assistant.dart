import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'data.dart';
import 'models.dart';
import 'agent_result_cards.dart';
import 'design_system.dart';

const assistantDefaultQuestion = '对照当前阶段目标和已有材料，哪些证据还不足？建议下一步做什么？';

Map<String, dynamic> assistantRequest(
    {required UserRole role,
    String? studentId,
    String? stageId,
    String? paperId,
    required String question,
    bool plan = false}) {
  if (studentId == null || studentId.trim().isEmpty) {
    throw ArgumentError('请先选择学生');
  }
  if (question.trim().isEmpty) throw ArgumentError('请输入科研问题');
  if (plan &&
      (role != UserRole.mentor || stageId == null || stageId.trim().isEmpty)) {
    throw ArgumentError('阶段任务建议需要导师身份并选择阶段');
  }
  return {
    'kind': plan ? 'stage_plan' : 'research_qa',
    'subject': {
      'student_id': studentId,
      if (stageId != null && stageId.trim().isNotEmpty) 'stage_id': stageId,
      if (paperId != null && paperId.trim().isNotEmpty) 'paper_id': paperId,
    },
    'input': {'question': question.trim()},
    'client_context': <String, dynamic>{},
  };
}

class ResearchAssistantScreen extends ConsumerStatefulWidget {
  const ResearchAssistantScreen(
      {this.stageId, this.paperId, this.question, super.key});
  final String? stageId, paperId, question;
  @override
  ConsumerState<ResearchAssistantScreen> createState() =>
      _ResearchAssistantScreenState();
}

class _ResearchAssistantScreenState
    extends ConsumerState<ResearchAssistantScreen> {
  late final TextEditingController question;
  String? stageId, paperId, error;
  bool busy = false;
  int historyRevision = 0;
  int epoch = 0;
  @override
  void initState() {
    super.initState();
    stageId = widget.stageId;
    paperId = widget.paperId;
    question = TextEditingController(
        text: widget.question ?? assistantDefaultQuestion);
  }

  @override
  void dispose() {
    epoch++;
    question.dispose();
    super.dispose();
  }

  Future<void> create(bool plan) async {
    if (busy) return;
    final session = ref.read(sessionProvider).valueOrNull;
    if (session?.collaborationEnabled != true) return;
    final role = ref.read(roleProvider);
    final studentId = role == UserRole.student
        ? session!.id
        : ref.read(selectedStudentProvider);
    final requestScope =
        collaborationScope(session, role, ref.read(selectedStudentProvider));
    final current = ++epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final body = assistantRequest(
          role: role,
          studentId: studentId,
          stageId: stageId,
          paperId: paperId,
          question: question.text,
          plan: plan);
      final created =
          await ref.read(apiClientProvider).write('/agent-runs', body);
      if (!mounted ||
          epoch != current ||
          requestScope !=
              collaborationScope(ref.read(sessionProvider).valueOrNull,
                  ref.read(roleProvider), ref.read(selectedStudentProvider))) {
        return;
      }
      if (created['run_id'] is! String ||
          (created['run_id'] as String).isEmpty) {
        throw const ApiFailure('运行响应缺少 run_id');
      }
      setState(() => historyRevision++);
      await context
          .push('/agent?run_id=${Uri.encodeQueryComponent(created['run_id'])}');
      if (mounted && epoch == current) setState(() => historyRevision++);
    } catch (e) {
      if (mounted && epoch == current) {
        setState(() =>
            error = e is ArgumentError ? e.message.toString() : e.toString());
      }
    } finally {
      if (mounted && epoch == current) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(sessionProvider, (previous, next) {
      if (!next.isLoading &&
          previous?.valueOrNull?.authorizationKey !=
              next.valueOrNull?.authorizationKey) {
        epoch++;
        setState(() {
          busy = false;
          error = null;
          stageId = null;
          paperId = null;
          question.text = assistantDefaultQuestion;
        });
      }
    });
    ref.listen(selectedStudentProvider, (previous, next) {
      if (previous != next) {
        epoch++;
        setState(() {
          stageId = null;
          paperId = null;
          busy = false;
          error = null;
          question.text = assistantDefaultQuestion;
        });
      }
    });
    ref.listen(roleProvider, (previous, next) {
      if (previous != next) {
        epoch++;
        setState(() {
          stageId = null;
          paperId = null;
          busy = false;
          error = null;
          question.text = assistantDefaultQuestion;
        });
      }
    });
    final session = ref.watch(sessionProvider).valueOrNull;
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    final studentId = mentor ? ref.watch(selectedStudentProvider) : session?.id;
    final enabled = session?.collaborationEnabled == true;
    final stages = enabled && studentId != null
        ? ref.watch(assistantStagesProvider(studentId))
        : const AsyncData<List<Map<String, dynamic>>>([]);
    final rows = stages.valueOrNull ?? [];
    final validStage = rows.any((e) => e['id'] == stageId) ? stageId : null;
    final students = mentor
        ? ref.watch(studentsProvider).valueOrNull ?? []
        : <Map<String, dynamic>>[];
    final names = students.where((s) => s['id'] == studentId);
    return Scaffold(
        appBar: AppBar(title: const Text('AI 科研导师'), actions: [
          IconButton(
              tooltip: '运行历史',
              onPressed: enabled && studentId != null && !busy
                  ? () async {
                      await context.push('/agent-runs');
                      if (mounted) setState(() => historyRevision++);
                    }
                  : null,
              icon: const Icon(Icons.history)),
        ]),
        body: ListView(padding: const EdgeInsets.all(20), children: [
          ResearchPanel(
              title: paperId != null ? '对照这版稿件与导师反馈' : '一起理清科研下一步',
              icon: Icons.auto_awesome,
              child: Text(paperId != null
                  ? '围绕当前指定的稿件提问，AI 会结合你有权查看的原文和导师意见。'
                  : '提出具体问题，结合阶段要求和已有材料查找依据。结论与处理过程都可查看。')),
          if (!enabled) const Text('加入实验室并完成绑定后，可使用科研下一步。'),
          if (mentor && studentId == null) const Text('请先从总览或工作页面选择学生。'),
          if (studentId != null)
            Text(
                '正在帮助：${mentor ? (names.isEmpty ? '当前学生' : names.first['display_name'] ?? '当前学生') : session?.name}'),
          const SizedBox(height: 16),
          DropdownButtonFormField<String>(
              initialValue: validStage,
              key: ValueKey('$studentId:$validStage'),
              isExpanded: true,
              decoration: const InputDecoration(labelText: '当前阶段（可选）'),
              items: [
                const DropdownMenuItem<String>(value: '', child: Text('不限定阶段')),
                for (final s in rows)
                  DropdownMenuItem(
                      value: s['id']?.toString(),
                      child: Text(displayValue(s['title']),
                          overflow: TextOverflow.ellipsis))
              ],
              onChanged: busy || !enabled || studentId == null
                  ? null
                  : (value) =>
                      setState(() => stageId = value == '' ? null : value)),
          if (stages.isLoading) const LinearProgressIndicator(),
          if (stages.hasError) ...[
            const Text('培养阶段暂时没有加载成功，请重试。'),
            TextButton(
                onPressed: busy
                    ? null
                    : () => ref.invalidate(assistantStagesProvider(studentId!)),
                child: const Text('重新加载阶段'))
          ],
          if (validStage != null)
            Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(
                    '阶段目标：${displayValue(rows.firstWhere((s) => s['id'] == validStage)['goal'])}')),
          const SizedBox(height: 16),
          TextField(
              controller: question,
              enabled: !busy && enabled && studentId != null,
              maxLines: 4,
              maxLength: 5000,
              decoration: const InputDecoration(labelText: '科研问题')),
          if (error != null)
            Text(error!, style: const TextStyle(color: Colors.red)),
          if (busy) const LinearProgressIndicator(),
          FilledButton(
              onPressed: busy || !enabled || studentId == null
                  ? null
                  : () {
                      if (stageId != validStage) stageId = validStage;
                      create(false);
                    },
              child: const Text('分析下一步')),
          if (mentor)
            OutlinedButton(
                onPressed:
                    busy || !enabled || studentId == null || validStage == null
                        ? null
                        : () => create(true),
                child: const Text('生成阶段任务建议')),
          const Text('分析不会自动修改任务或记录；建议需在运行详情中确认。'),
          if (enabled && studentId != null) ...[
            const SizedBox(height: 24),
            const Text('最近运行',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            IgnorePointer(
                ignoring: busy,
                child: AgentHistoryScreen(
                    key: ValueKey(historyRevision), embedded: true)),
          ],
        ]));
  }
}

class AgentHistoryScreen extends ConsumerStatefulWidget {
  const AgentHistoryScreen({this.embedded = false, super.key});
  final bool embedded;
  @override
  ConsumerState<AgentHistoryScreen> createState() => _AgentHistoryScreenState();
}

class _AgentHistoryScreenState extends ConsumerState<AgentHistoryScreen> {
  List<Map<String, dynamic>> rows = [];
  String? cursor, error, scope;
  bool busy = false;
  int epoch = 0;
  @override
  void dispose() {
    epoch++;
    super.dispose();
  }

  String? currentScope() => collaborationScope(
      ref.read(sessionProvider).valueOrNull,
      ref.read(roleProvider),
      ref.read(selectedStudentProvider));

  void reset() {
    epoch++;
    scope = currentScope();
    rows = [];
    cursor = null;
    error = null;
    busy = false;
    if (scope != null) Future.microtask(load);
  }

  Future<void> load() async {
    if (busy || scope == null) return;
    final current = epoch;
    final requestedScope = scope;
    final next = cursor;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final data = await ref.read(apiClientProvider).get('/agent-runs', query: {
        'student_id': scope!.split('|').last,
        'limit': 20,
        if (next != null) 'cursor': next
      });
      if (!mounted || current != epoch || requestedScope != currentScope()) {
        return;
      }
      if (invalidObjectRows(data['runs']) || data['runs'] == null) {
        throw const ApiFailure('运行历史数据格式异常');
      }
      final nextCursor = data['next_cursor'];
      if (nextCursor != null &&
          (nextCursor is! String || nextCursor.isEmpty || nextCursor == next)) {
        throw const ApiFailure('运行历史分页响应异常');
      }
      setState(() {
        final ids = rows.map((e) => e['id']).toSet();
        rows.addAll(objectRows(data['runs'])
            .where((e) => e['id'] is String && ids.add(e['id'])));
        cursor = nextCursor as String?;
      });
    } catch (e) {
      if (mounted && current == epoch) setState(() => error = e.toString());
    } finally {
      if (mounted && current == epoch) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(sessionProvider);
    ref.watch(roleProvider);
    ref.watch(selectedStudentProvider);
    if (scope != currentScope()) reset();
    final children = <Widget>[
      if (scope == null) const Text('请登录并选择已绑定的学生。'),
      if (!busy && rows.isEmpty && error == null && scope != null)
        const Text('暂无运行记录'),
      for (final row in widget.embedded ? rows.take(3) : rows)
        Card(
            child: ListTile(
                leading: const ResearchIcon(Icons.auto_awesome),
                title: Text(runKindLabel(row['kind'])),
                subtitle: Text(
                    '${runStatusLabel(row['status'])} · ${beijingTime(row['created_at'])}\n${row['input'] is Map ? displayValue(row['input']['question']) : ''}'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () async {
                  await context.push(
                      '/agent?run_id=${Uri.encodeQueryComponent(row['id'])}');
                  if (mounted) setState(reset);
                })),
      if (busy) const LinearProgressIndicator(),
      if (error != null) ...[
        Text('运行历史加载失败：$error'),
        TextButton(onPressed: busy ? null : load, child: const Text('重试'))
      ],
      if (!widget.embedded && cursor != null && error == null)
        OutlinedButton(
            onPressed: busy ? null : load, child: const Text('加载更多')),
      if (widget.embedded)
        TextButton(
            onPressed: () async {
              await context.push('/agent-runs');
              if (mounted) setState(reset);
            },
            child: const Text('查看全部运行历史')),
    ];
    if (widget.embedded) {
      return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
    }
    return Scaffold(
        appBar: AppBar(title: const Text('运行历史')),
        body: RefreshIndicator(
            onRefresh: () async {
              setState(reset);
              await load();
            },
            child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(20),
                children: children)));
  }
}
