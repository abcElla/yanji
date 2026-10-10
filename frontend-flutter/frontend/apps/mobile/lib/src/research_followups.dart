import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:uuid/uuid.dart';

import 'data.dart';
import 'models.dart';
import 'design_system.dart';
import 'agent_result_cards.dart';

String readableResearchText(dynamic value) => (value?.toString() ?? '')
    .replaceAll(RegExp(r'AI\s*导师'), 'AI助教')
    .replaceAllMapped(
      RegExp(r'\b(todo|in_progress|blocked|completed|cancelled)\b'),
      (m) => const {
        'todo': '待开始',
        'in_progress': '进行中',
        'blocked': '等待前置任务',
        'completed': '已完成',
        'cancelled': '已取消',
      }[m[0]]!,
    )
    .replaceAll(
      RegExp(
        r'\s*[（(]\s*(?:id|task_id|stage_id|paper_id)\s*[:：]\s*[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\s*[）)]',
      ),
      '',
    );

Map<String, dynamic>? currentResearchPlan(List<Map<String, dynamic>> rows) {
  for (final row in rows) {
    final run = row['run'];
    if (!['dismissed', 'closed'].contains(row['state']) &&
        run is Map &&
        run['status'] == 'succeeded' &&
        run['result'] is Map) {
      return row;
    }
  }
  return null;
}

bool researchPlanUpdating(List<Map<String, dynamic>> rows) => rows.any(
      (r) =>
          r['state'] == 'waiting' ||
          (r['run'] is Map &&
              ['queued', 'running'].contains((r['run'] as Map)['status'])),
    );

final researchFollowupsForScopeProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, String>((ref, scope) async {
  final student = scope.split('|').last;
  Timer? timer;
  bool disposed = false;
  ref.onDispose(() {
    disposed = true;
    timer?.cancel();
  });
  try {
    final response = await ref
        .read(apiClientProvider)
        .get('/research-followups', query: {'student_id': student});
    return (response['items'] as List)
        .map((r) => Map<String, dynamic>.from(r as Map))
        .toList();
  } finally {
    // Start the next interval after this request settles, never overlap slow reads.
    if (!disposed) {
      timer = Timer(const Duration(seconds: 8), ref.invalidateSelf);
    }
  }
});

final researchFollowupsProvider =
    Provider<AsyncValue<List<Map<String, dynamic>>>>((ref) {
  final scope = collaborationScope(
    ref.watch(sessionProvider).valueOrNull,
    ref.watch(roleProvider),
    ref.watch(selectedStudentProvider),
  );
  return scope == null
      ? const AsyncData([])
      : ref.watch(researchFollowupsForScopeProvider(scope));
});

void refreshResearchPlans(WidgetRef ref) {
  final scope = collaborationScope(
    ref.read(sessionProvider).valueOrNull,
    ref.read(roleProvider),
    ref.read(selectedStudentProvider),
  );
  if (scope != null) ref.invalidate(researchFollowupsForScopeProvider(scope));
}

class ResearchStudentSelector extends ConsumerWidget {
  const ResearchStudentSelector({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (ref.watch(roleProvider) != UserRole.mentor) {
      return const SizedBox.shrink();
    }
    return ref.watch(studentsProvider).when(
          data: (students) => Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: DropdownButtonFormField<String>(
              initialValue: students.any(
                (s) => s['id'] == ref.watch(selectedStudentProvider),
              )
                  ? ref.watch(selectedStudentProvider)
                  : null,
              key: ValueKey(ref.watch(selectedStudentProvider)),
              decoration: const InputDecoration(
                labelText: '正在指导的学生',
                prefixIcon: Icon(Icons.school_outlined),
              ),
              items: students
                  .map(
                    (s) => DropdownMenuItem(
                      value: s['id'] as String,
                      child: Text('${s['display_name'] ?? s['name'] ?? '学生'}'),
                    ),
                  )
                  .toList(),
              onChanged: (id) =>
                  ref.read(selectedStudentProvider.notifier).state = id,
            ),
          ),
          loading: () => const LinearProgressIndicator(),
          error: (e, _) => Text('学生列表暂时无法加载：$e'),
        );
  }
}

class ResearchPlanPanel extends ConsumerWidget {
  const ResearchPlanPanel({this.compact = false, super.key});
  final bool compact;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plans = ref.watch(researchFollowupsProvider);
    final rows = plans.valueOrNull ?? [];
    final plan = currentResearchPlan(rows);
    final updating = researchPlanUpdating(rows);
    final run = plan?['run'] as Map?;
    final result = run?['result'] as Map?;
    final steps = (result?['next_steps'] as List? ?? []).take(compact ? 2 : 3);
    final student = ref.watch(roleProvider) == UserRole.mentor
        ? ref.watch(selectedStudentProvider)
        : ref.watch(sessionProvider).valueOrNull?.id;
    final stages = student == null
        ? const AsyncData<List<Map<String, dynamic>>>([])
        : ref.watch(assistantStagesProvider(student));
    final active = (stages.valueOrNull ?? []).where(
      (s) => s['status'] == 'active',
    );
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFFE1DAFF)),
        gradient: const LinearGradient(
          colors: [Color(0xFFF0EBFF), Color(0xFFFCFBFF)],
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.auto_awesome, color: ResearchDesign.purple),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  'AI 当前推进计划',
                  style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
                ),
              ),
              if (updating)
                const Text(
                  '更新中',
                  style: TextStyle(color: ResearchDesign.purple, fontSize: 12),
                ),
            ],
          ),
          if (active.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                '当前阶段 · ${active.first['title']}',
                style: const TextStyle(
                  color: ResearchDesign.muted,
                  fontSize: 12,
                ),
              ),
            ),
          const SizedBox(height: 10),
          if (plans.hasError) ...[
            const Text('推进计划暂时无法加载，请重试。'),
            TextButton(
              onPressed: () => refreshResearchPlans(ref),
              child: const Text('重新加载'),
            ),
          ] else if (result != null) ...[
            Text(
              readableResearchText(result['summary']),
              maxLines: compact ? 4 : 7,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(height: 1.6),
            ),
            for (final step in steps)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(
                      Icons.arrow_circle_right_outlined,
                      size: 18,
                      color: ResearchDesign.purple,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        readableResearchText((step as Map)['title']),
                        maxLines: compact ? 2 : 4,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                    ),
                  ],
                ),
              ),
            if ((result['missing_inputs'] as List? ?? []).isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Text(
                  '还需要：${readableResearchText((result['missing_inputs'] as List).take(2).join('、'))}',
                  maxLines: compact ? 2 : 4,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 12,
                    color: ResearchDesign.muted,
                  ),
                ),
              ),
            if ((plan?['changes'] as List? ?? []).isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Text(
                  readableResearchText(
                      ((plan!['changes'] as List).last as Map)['reason']),
                  style: const TextStyle(
                    fontSize: 12,
                    color: ResearchDesign.muted,
                  ),
                ),
              ),
          ] else
            Text(
              student == null
                  ? '先选择一位学生，查看他的科研进展。'
                  : updating
                      ? '正在根据最新进展整理计划。你也可以先向 AI助教提问。'
                      : '告诉 AI助教你的目标与困惑，一起理清下一步。提交新材料后，计划也会随进展更新。',
            ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              if (plan != null)
                OutlinedButton(
                  onPressed: () =>
                      context.push('/research-followups/${plan['id']}'),
                  child: const Text('查看与落实计划'),
                ),
              if (compact)
                TextButton.icon(
                  onPressed: () => context.go('/ai-mentor'),
                  icon: const Icon(Icons.chat_bubble_outline, size: 17),
                  label: const Text('问 AI助教'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class AiMentorScreen extends ConsumerStatefulWidget {
  const AiMentorScreen({super.key});
  @override
  ConsumerState<AiMentorScreen> createState() => _AiMentorScreenState();
}

class _AiMentorScreenState extends ConsumerState<AiMentorScreen> {
  final question = TextEditingController();
  final conversationScroll = ScrollController();
  String conversationSignature = '';
  bool followLatest = true;
  bool sending = false;
  String? error, retryKey, retryQuestion;
  int epoch = 0;
  @override
  void dispose() {
    epoch++;
    question.dispose();
    conversationScroll.dispose();
    super.dispose();
  }

  Future<void> ask() async {
    if (sending || question.text.trim().isEmpty) return;
    final session = ref.read(sessionProvider).valueOrNull;
    final role = ref.read(roleProvider);
    final selected = ref.read(selectedStudentProvider);
    final scope = collaborationScope(session, role, selected);
    final student = role == UserRole.mentor ? selected : session?.id;
    if (scope == null || student == null) {
      setState(() => error = '请先选择学生并加入指导关系');
      return;
    }
    final text = question.text.trim();
    final key = retryQuestion == text
        ? retryKey ?? const Uuid().v4()
        : const Uuid().v4();
    retryQuestion = text;
    retryKey = key;
    final ticket = ++epoch;
    setState(() {
      sending = true;
      error = null;
      followLatest = true;
    });
    try {
      await ref.read(apiClientProvider).request(
            'POST',
            '/research-followups',
            body: {'student_id': student, 'question': text},
            key: key,
          );
      if (!mounted ||
          ticket != epoch ||
          scope !=
              collaborationScope(
                ref.read(sessionProvider).valueOrNull,
                ref.read(roleProvider),
                ref.read(selectedStudentProvider),
              )) {
        return;
      }
      question.clear();
      retryKey = null;
      retryQuestion = null;
      refreshResearchPlans(ref);
    } catch (e) {
      if (mounted && ticket == epoch) setState(() => error = e.toString());
    } finally {
      if (mounted && ticket == epoch) setState(() => sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(roleProvider, (a, b) {
      if (a != b) {
        epoch++;
        setState(() {
          sending = false;
          error = null;
          question.clear();
          retryKey = null;
          retryQuestion = null;
        });
      }
    });
    ref.listen(selectedStudentProvider, (a, b) {
      if (a != b) {
        epoch++;
        setState(() {
          sending = false;
          error = null;
          question.clear();
          retryKey = null;
          retryQuestion = null;
        });
      }
    });
    ref.listen(sessionProvider, (a, b) {
      if (a?.valueOrNull?.authorizationKey != b.valueOrNull?.authorizationKey &&
          !b.isLoading) {
        epoch++;
        setState(() {
          sending = false;
          error = null;
          question.clear();
          retryKey = null;
          retryQuestion = null;
        });
      }
    });
    final plans = ref.watch(researchFollowupsProvider);
    final rows = plans.valueOrNull ?? [];
    final signature =
        rows.map((row) => '${row['id']}:${row['run']?['status']}').join('|');
    if (signature != conversationSignature) {
      conversationSignature = signature;
      final shouldFollow = followLatest ||
          !conversationScroll.hasClients ||
          conversationScroll.position.maxScrollExtent -
                  conversationScroll.position.pixels <
              120;
      if (rows.isNotEmpty && shouldFollow) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted || !conversationScroll.hasClients) return;
          conversationScroll.animateTo(
            conversationScroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 250),
            curve: Curves.easeOut,
          );
          followLatest = false;
        });
      }
    }
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
          child: Row(
            children: [
              const Icon(Icons.auto_awesome, color: ResearchDesign.purple),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  'AI助教',
                  style: TextStyle(fontSize: 23, fontWeight: FontWeight.w700),
                ),
              ),
              IconButton(
                onPressed: () => context.push('/assistant'),
                tooltip: '专项分析与运行历史',
                icon: const Icon(Icons.history),
              ),
            ],
          ),
        ),
        Expanded(
          child: SingleChildScrollView(
            controller: conversationScroll,
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const ResearchStudentSelector(),
                ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  leading: const Icon(
                    Icons.route_rounded,
                    color: ResearchDesign.purple,
                  ),
                  title: const Text(
                    '查看当前推进计划',
                    style: TextStyle(fontWeight: FontWeight.w700),
                  ),
                  subtitle: const Text('和总览保持一致，随科研进展更新'),
                  children: const [ResearchPlanPanel(compact: true)],
                ),
                const SizedBox(height: 14),
                const Text(
                  '围绕你的科研进展，继续聊一聊',
                  style: TextStyle(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: ['我现在应该先做什么？', '导师意见应该怎么落实？', '目前还缺哪些材料？']
                      .map(
                        (q) => ActionChip(
                          label: Text(q, style: const TextStyle(fontSize: 12)),
                          onPressed: sending
                              ? null
                              : () {
                                  question.text = q;
                                  ask();
                                },
                        ),
                      )
                      .toList(),
                ),
                for (final row in rows.reversed)
                  if (!['closed', 'dismissed'].contains(row['state']))
                    _ConversationTurn(row),
                if (rows.isEmpty && !plans.isLoading)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Text(
                      '你可以告诉我正在研究什么。我会结合任务、材料和导师反馈，帮你找到下一步。',
                      style: TextStyle(
                        color: ResearchDesign.muted,
                        height: 1.7,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
        Container(
          color: Colors.white,
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
          child: Column(
            children: [
              if (error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    error!,
                    style: const TextStyle(color: ResearchDesign.red),
                  ),
                ),
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: TextField(
                      controller: question,
                      minLines: 1,
                      maxLines: 4,
                      maxLength: 2000,
                      decoration: const InputDecoration(
                        hintText: '有问题，问问 AI助教…',
                        counterText: '',
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filled(
                    onPressed: sending ? null : ask,
                    tooltip: '发送问题',
                    icon: sending
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.send_rounded),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _ConversationTurn extends StatelessWidget {
  const _ConversationTurn(this.row);
  final Map<String, dynamic> row;
  @override
  Widget build(BuildContext context) {
    final run = row['run'] as Map?;
    final changes = row['changes'] as List? ?? [];
    final question = changes
        .whereType<Map>()
        .where((c) => c['question'] != null)
        .firstOrNull?['question'];
    final result = run?['result'] as Map?;
    return Padding(
      padding: const EdgeInsets.only(top: 14, bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (question != null)
            Align(
              alignment: Alignment.centerRight,
              child: Container(
                margin: const EdgeInsets.only(left: 36, bottom: 10),
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: const Color(0xFFECE6FF),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Text('$question'),
              ),
            ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Row(
                    children: [
                      Icon(
                        Icons.smart_toy_outlined,
                        color: ResearchDesign.purple,
                        size: 20,
                      ),
                      SizedBox(width: 8),
                      Text(
                        '研迹 AI助教',
                        style: TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  if (result != null)
                    AgentInlineAnswer({
                      ...result,
                      'summary': readableResearchText(result['summary']),
                    })
                  else
                    Text(
                      run?['status'] == 'failed'
                          ? failedAdviceMessage(run?['error'])
                          : '正在结合你的科研进展整理建议…',
                    ),
                  TextButton.icon(
                    onPressed: () =>
                        context.push('/research-followups/${row['id']}'),
                    icon: const Icon(Icons.arrow_forward, size: 16),
                    label: const Text('查看建议与行动清单'),
                  ),
                  if (result?['suggested_skills'] is List)
                    ResearchSkillActions(
                      key: ValueKey('skills-${row['id']}'),
                      skills: result!['suggested_skills'] as List,
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

String failedAdviceMessage(dynamic error) {
  final code = error is Map ? error['code'] : null;
  return switch (code) {
    'MODEL_AUTH_FAILED' || 'MODEL_ACCESS_DENIED' => '模型服务配置或权限异常，请联系维护人员检查后重试。',
    'MODEL_READ_TIMEOUT' || 'RUN_BUDGET_EXCEEDED' => '模型响应超时，暂未收到回答，请重试。',
    'MODEL_OUTPUT_INVALID' ||
    'MODEL_RESPONSE_INVALID' ||
    'MODEL_OUTPUT_TRUNCATED' =>
      '回答未能完整整理，请打开详情重新生成。',
    'MODEL_CONNECTION_FAILED' ||
    'MODEL_CONNECT_TIMEOUT' ||
    'MODEL_UNAVAILABLE' =>
      '暂时无法连接模型服务，请检查服务连接后重试。',
    _ => '本次未收到模型回答，请打开详情重试。',
  };
}

class ResearchSkillActions extends ConsumerStatefulWidget {
  const ResearchSkillActions({required this.skills, super.key});
  final List<dynamic> skills;
  @override
  ConsumerState<ResearchSkillActions> createState() =>
      _ResearchSkillActionsState();
}

class _ResearchSkillActionsState extends ConsumerState<ResearchSkillActions> {
  bool busy = false;
  String? error;
  final keys = <String, String>{};
  Future<void> use(Map<String, dynamic> skill) async {
    if (busy) return;
    final session = ref.read(sessionProvider).valueOrNull;
    final role = ref.read(roleProvider);
    final selected = ref.read(selectedStudentProvider);
    final scope = collaborationScope(session, role, selected);
    final student = role == UserRole.mentor ? selected : session?.id;
    if (scope == null || student == null) return;
    final route = skill['route'];
    if (route != null) {
      if (['/work', '/library', '/theses'].contains(route)) {
        context.push(route as String);
      }
      return;
    }
    final subject = Map<String, dynamic>.from(skill['subject'] as Map? ?? {});
    if (subject['student_id'] != student ||
        ![
          'risk_brief',
          'weekly_report',
          'stage_plan',
          'paper_guide',
        ].contains(skill['kind'])) {
      setState(() => error = '这项建议与当前学生不一致，请重新分析');
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    final id = skill['id'].toString();
    final key = keys.putIfAbsent(id, () => const Uuid().v4());
    try {
      final response = await ref.read(apiClientProvider).request(
            'POST',
            '/agent-runs',
            body: {
              'kind': skill['kind'],
              'subject': subject,
              'input': skill['input'] ?? {},
            },
            key: key,
          );
      if (!mounted ||
          scope !=
              collaborationScope(
                ref.read(sessionProvider).valueOrNull,
                ref.read(roleProvider),
                ref.read(selectedStudentProvider),
              )) {
        return;
      }
      keys.remove(id);
      await context.push('/agent?run_id=${response['run_id']}');
      if (mounted) refreshResearchPlans(ref);
    } catch (e) {
      if (mounted &&
          scope ==
              collaborationScope(
                ref.read(sessionProvider).valueOrNull,
                ref.read(roleProvider),
                ref.read(selectedStudentProvider),
              )) {
        setState(() => error = e.toString());
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final raw in widget.skills.take(3))
            if (raw is Map)
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if ('${raw['reason'] ?? ''}'.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6),
                        child: Text(
                          readableResearchText(raw['reason']),
                          style: const TextStyle(
                            color: ResearchDesign.muted,
                            height: 1.5,
                          ),
                        ),
                      ),
                    FilledButton.tonalIcon(
                      onPressed: busy
                          ? null
                          : () => use(Map<String, dynamic>.from(raw)),
                      icon: const Icon(Icons.auto_awesome, size: 18),
                      label: Text(
                        '${raw['label']}',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
              ),
          if (busy)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text('正在打开专项分析…'),
            ),
          if (error != null)
            Text(error!, style: const TextStyle(color: ResearchDesign.red)),
        ],
      );
}

class ResearchFollowupDetailScreen extends ConsumerStatefulWidget {
  const ResearchFollowupDetailScreen({required this.id, super.key});
  final String id;
  @override
  ConsumerState<ResearchFollowupDetailScreen> createState() =>
      _ResearchFollowupDetailScreenState();
}

class _ResearchFollowupDetailScreenState
    extends ConsumerState<ResearchFollowupDetailScreen> {
  Map<String, dynamic>? plan;
  List<Map<String, dynamic>> edits = [];
  String feedbackNote = '';
  final Set<int> selected = {};
  final Map<int, ResearchTask> linked = {};
  String? error;
  bool busy = false, edited = false, loading = false;
  Timer? timer;
  String? scope;
  final Map<String, String> writeKeys = {};
  int readGeneration = 0;
  @override
  void initState() {
    super.initState();
    Future.microtask(load);
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  String? currentScope() => collaborationScope(
        ref.read(sessionProvider).valueOrNull,
        ref.read(roleProvider),
        ref.read(selectedStudentProvider),
      );
  Future<void> load() async {
    if (loading || busy) return;
    timer?.cancel();
    final expected = currentScope();
    if (expected == null) return;
    scope ??= expected;
    if (scope != expected) {
      if (mounted) {
        setState(() {
          plan = null;
          edits = [];
          selected.clear();
          linked.clear();
          error = '账号或学生已切换，请返回后重新打开计划';
        });
      }
      return;
    }
    loading = true;
    final generation = readGeneration;
    try {
      final response = await ref
          .read(apiClientProvider)
          .get('/research-followups/${widget.id}');
      if (!mounted ||
          expected != currentScope() ||
          busy ||
          generation != readGeneration) {
        return;
      }
      setState(() {
        plan = response;
        error = null;
        if (!edited) {
          edits = ((response['run']?['result']?['next_steps'] as List?) ?? [])
              .map((s) => Map<String, dynamic>.from(s as Map))
              .toList();
          selected
            ..clear()
            ..addAll(List.generate(edits.length, (i) => i));
        }
      });
    } catch (e) {
      if (mounted &&
          expected == currentScope() &&
          generation == readGeneration) {
        setState(() {
          error = e.toString();
          if (isAccessFailure(e)) {
            plan = null;
            edits = [];
            selected.clear();
            linked.clear();
            edited = false;
          }
        });
      }
    } finally {
      loading = false;
      if (mounted && expected == currentScope() && !busy) {
        timer?.cancel();
        timer = Timer(const Duration(seconds: 8), load);
      }
    }
  }

  Future<void> write(String action) async {
    if (busy || plan == null || scope != currentScope()) return;
    final expected = scope;
    readGeneration++;
    timer?.cancel();
    final body = {
      'expected_version': plan!['version'],
      if (['apply', 'dismiss'].contains(action) &&
          feedbackNote.trim().isNotEmpty)
        'feedback_note': feedbackNote.trim(),
      if (action == 'apply')
        'items': [
          for (int i = 0; i < edits.length; i++)
            if (selected.contains(i))
              if (linked[i] case final task?)
                {
                  'existing_task_id': task.id,
                  'expected_version': task.version,
                  if (edits[i]['step_id'] != null)
                    'step_id': edits[i]['step_id'],
                }
              else
                edits[i],
        ],
    };
    final signature = '$action:$body';
    final key = writeKeys.putIfAbsent(signature, () => const Uuid().v4());
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final saved = await ref.read(apiClientProvider).request(
            'POST',
            '/research-followups/${widget.id}/$action',
            body: body,
            key: key,
          );
      if (!mounted || expected != currentScope()) return;
      refreshResearchPlans(ref);
      ref.invalidate(tasksProvider);
      if (action == 'retry') {
        context.pushReplacement('/research-followups/${saved['id']}');
        return;
      }
      setState(() => plan = saved);
    } catch (e) {
      if (mounted && expected == currentScope()) {
        setState(() => error = e.toString());
      }
    } finally {
      if (mounted && expected == currentScope()) {
        setState(() => busy = false);
        timer?.cancel();
        timer = Timer(const Duration(seconds: 8), load);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(sessionProvider);
    ref.watch(roleProvider);
    ref.watch(selectedStudentProvider);
    if (scope != null && scope != currentScope()) {
      return Scaffold(
        appBar: AppBar(title: const Text('科研推进计划')),
        body: const Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Text('账号或学生已切换，请返回后重新打开计划'),
          ),
        ),
      );
    }
    final availableTasks = ref.watch(tasksProvider);
    final run = plan?['run'] as Map?;
    final result = run?['result'] as Map?;
    final canApply =
        plan?['state'] == 'active' && run?['status'] == 'succeeded';
    return Scaffold(
      appBar: AppBar(title: const Text('科研推进计划')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          if (error != null)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    Text(
                      error!,
                      style: const TextStyle(color: ResearchDesign.red),
                    ),
                    TextButton(onPressed: load, child: const Text('重新加载')),
                  ],
                ),
              ),
            ),
          if (plan == null && error == null)
            const Center(child: CircularProgressIndicator()),
          if (result != null)
            AgentResultCards(Map<String, dynamic>.from(result)),
          if (result?['suggested_skills'] is List)
            ResearchSkillActions(
              key: ValueKey('detail-skills-${widget.id}'),
              skills: result!['suggested_skills'] as List,
            ),
          if (run?['status'] == 'failed')
            FilledButton.icon(
              onPressed: busy ? null : () => write('retry'),
              icon: const Icon(Icons.refresh),
              label: const Text('重新分析当前进展'),
            ),
          if (plan != null &&
              (plan!['state'] == 'waiting' ||
                  ['queued', 'running'].contains(run?['status'])))
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text('AI 正在根据当前材料整理计划，请稍后查看。'),
            ),
          if (plan?['state'] == 'applied') ...[
            const Text(
              '已经加入任务',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
            ),
            for (final task in plan!['tasks'] as List? ?? [])
              Card(
                child: ListTile(
                  leading: const Icon(
                    Icons.checklist,
                    color: ResearchDesign.teal,
                  ),
                  title: Text('${task['title']}'),
                  subtitle: Text(
                    task['status'] == 'completed' ? '已完成' : '待继续推进',
                  ),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () {
                    ref.read(selectedTaskProvider.notifier).state =
                        ResearchTask.fromJson(Map<String, dynamic>.from(task));
                    context.push('/task');
                  },
                ),
              ),
          ],
          if (canApply) ...[
            const SizedBox(height: 16),
            const Text(
              '把建议变成具体任务',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            const Text(
              '逐项选择；已有相近任务时可以关联，避免重复新建。关联不会修改原任务或替代导师验收。',
              style: TextStyle(color: ResearchDesign.muted),
            ),
            for (int i = 0; i < edits.length; i++)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    children: [
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text('采用第 ${i + 1} 项'),
                        value: selected.contains(i),
                        onChanged: busy
                            ? null
                            : (value) => setState(() {
                                  edited = true;
                                  if (value == true) {
                                    selected.add(i);
                                  } else {
                                    selected.remove(i);
                                  }
                                }),
                      ),
                      availableTasks.when(
                        data: (rows) => DropdownButtonFormField<String>(
                          key: ValueKey('link-$i-${linked[i]?.id}'),
                          initialValue: linked[i]?.id ?? '',
                          isExpanded: true,
                          decoration: const InputDecoration(
                            labelText: '新建或关联已有任务',
                          ),
                          items: [
                            const DropdownMenuItem(
                              value: '',
                              child: Text('新建任务'),
                            ),
                            for (final task in rows.where(
                              (t) => t.status != TaskStatus.completed,
                            ))
                              DropdownMenuItem(
                                value: task.id,
                                child: Text(
                                  task.title,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            if (linked[i] != null &&
                                !rows.any(
                                  (t) =>
                                      t.id == linked[i]!.id &&
                                      t.status != TaskStatus.completed,
                                ))
                              DropdownMenuItem(
                                value: linked[i]!.id,
                                child: const Text('原任务已变化，请重新选择'),
                              ),
                          ],
                          onChanged: busy || !selected.contains(i)
                              ? null
                              : (id) => setState(() {
                                    edited = true;
                                    if (id == null || id.isEmpty) {
                                      linked.remove(i);
                                    } else {
                                      linked[i] = rows.firstWhere(
                                        (t) => t.id == id,
                                      );
                                    }
                                  }),
                        ),
                        loading: () => const LinearProgressIndicator(),
                        error: (e, _) => const Text('已有任务暂时无法加载，可以稍后重试'),
                      ),
                      if (linked[i] == null) ...[
                        if (edits[i]['task_mode'] == 'existing')
                          const Padding(
                            padding: EdgeInsets.only(bottom: 8),
                            child: Text('此项将关联已有任务，不重复创建。任务内容可在任务详情修改。'),
                          ),
                        TextFormField(
                          key: ValueKey('title-$i-${widget.id}'),
                          enabled: !busy &&
                              selected.contains(i) &&
                              edits[i]['task_mode'] != 'existing',
                          initialValue: edits[i]['title']?.toString(),
                          maxLength: 200,
                          decoration: InputDecoration(
                            labelText: '第 ${i + 1} 项 · 要做什么',
                          ),
                          onChanged: (v) => setState(() {
                            edited = true;
                            edits[i]['title'] = v;
                          }),
                        ),
                        const SizedBox(height: 10),
                        TextFormField(
                          initialValue: edits[i]['description']?.toString(),
                          enabled: !busy &&
                              selected.contains(i) &&
                              edits[i]['task_mode'] != 'existing',
                          minLines: 2,
                          maxLines: 5,
                          maxLength: 5000,
                          decoration: const InputDecoration(labelText: '怎么做'),
                          onChanged: (v) {
                            edited = true;
                            edits[i]['description'] = v;
                          },
                        ),
                        const SizedBox(height: 10),
                        TextFormField(
                          enabled: !busy &&
                              selected.contains(i) &&
                              edits[i]['task_mode'] != 'existing',
                          initialValue:
                              (edits[i]['acceptance_criteria'] as List? ?? [])
                                  .join('\n'),
                          minLines: 1,
                          maxLines: 4,
                          decoration: const InputDecoration(
                            labelText: '完成后核对什么',
                            hintText: '每行写一项',
                          ),
                          onChanged: (v) {
                            edited = true;
                            edits[i]['acceptance_criteria'] = v
                                .split('\n')
                                .map((s) => s.trim())
                                .where((s) => s.isNotEmpty)
                                .toList();
                          },
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            TextFormField(
              enabled: !busy,
              maxLength: 2000,
              minLines: 1,
              maxLines: 3,
              decoration: const InputDecoration(
                labelText: '修改或暂不采用的原因（选填）',
                hintText: '例如：缺少基线步骤，或任务安排超出了本周时间',
              ),
              onChanged: (value) {
                edited = true;
                feedbackNote = value;
              },
            ),
            FilledButton.icon(
              onPressed: busy ||
                      selected.isEmpty ||
                      selected.any(
                        (i) =>
                            linked[i] == null &&
                            (edits[i]['title']?.toString().trim() ?? '')
                                .isEmpty,
                      )
                  ? null
                  : () => write('apply'),
              icon: const Icon(Icons.add_task),
              label: const Text('确认并加入任务'),
            ),
            TextButton(
              onPressed: busy ? null : () => write('dismiss'),
              child: const Text('暂不采用这份计划'),
            ),
          ],
          const SizedBox(height: 16),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              OutlinedButton.icon(
                onPressed: () => context.push('/library'),
                icon: const Icon(Icons.library_books_outlined),
                label: const Text('补充资料'),
              ),
              OutlinedButton.icon(
                onPressed: () => context.push('/work'),
                icon: const Icon(Icons.edit_note),
                label: const Text('记录与任务'),
              ),
              OutlinedButton.icon(
                onPressed: () => context.push('/theses'),
                icon: const Icon(Icons.description_outlined),
                label: const Text('论文与导师反馈'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
