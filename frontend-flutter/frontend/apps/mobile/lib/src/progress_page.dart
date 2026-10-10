import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'data.dart';
import 'models.dart';
import 'design_system.dart';
import 'ai_action.dart';
import 'guidance_page.dart';
import 'package:go_router/go_router.dart';

final progressStagesProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
  final session = ref.watch(sessionProvider).valueOrNull;
  final student = ref.watch(roleProvider) == UserRole.mentor
      ? ref.watch(selectedStudentProvider)
      : session?.id;
  if (student == null) return [];
  return ref
      .watch(apiClientProvider)
      .list('/stages', query: {'student_id': student});
});

class ProgressPage extends ConsumerWidget {
  const ProgressPage({super.key});
  Future<void> create(BuildContext context, WidgetRef ref) async {
    final student = ref.read(selectedStudentProvider);
    if (student == null) return;
    final saved = await Navigator.push<bool>(context,
        MaterialPageRoute(builder: (_) => StageEditor(studentId: student)));
    if (saved == true) ref.invalidate(progressStagesProvider);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    final students = ref.watch(studentsProvider).valueOrNull ?? [];
    final selected = ref.watch(selectedStudentProvider);
    final stages = ref.watch(progressStagesProvider);
    return Scaffold(
        appBar: AppBar(title: const Text('科研进度')),
        body: ListView(padding: const EdgeInsets.all(20), children: [
          ResearchPanel(
              title: mentor ? '安排目标，跟进进展' : '一步一步完成当前目标',
              icon: Icons.flag_outlined,
              color: ResearchDesign.orange,
              child: const Text('阶段进度按真实任务完成情况计算，完成后由导师验收。')),
          const SizedBox(height: 12),
          if (mentor)
            DropdownButtonFormField<String>(
                initialValue:
                    students.any((s) => s['id'] == selected) ? selected : null,
                key: ValueKey(selected),
                isExpanded: true,
                decoration: const InputDecoration(labelText: '选择学生'),
                items: [
                  for (final s in students)
                    DropdownMenuItem(
                        value: s['id'].toString(),
                        child: Text(s['display_name']?.toString() ?? '学生'))
                ],
                onChanged: (v) =>
                    ref.read(selectedStudentProvider.notifier).state = v),
          const SizedBox(height: 16),
          if (mentor && selected != null)
            FilledButton.icon(
                onPressed: () => create(context, ref),
                icon: const Icon(Icons.add),
                label: const Text('添加培养阶段')),
          const SizedBox(height: 18),
          ...stages.when(
              data: (rows) => [
                    if (rows.isEmpty)
                      Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(mentor && selected == null
                              ? '请选择学生，查看或安排培养阶段。'
                              : '还没有培养阶段，导师可以先设置目标。')),
                    for (final stage in rows)
                      Card(
                          child: ListTile(
                              leading: const ResearchIcon(Icons.flag_outlined,
                                  color: ResearchDesign.orange),
                              title: Text(stage['title']?.toString() ?? '培养阶段',
                                  style: const TextStyle(
                                      fontWeight: FontWeight.w700)),
                              subtitle: Text(
                                  '${stage['goal'] ?? ''}\n${const {
                                        'active': '正在推进',
                                        'submitted': '待导师验收',
                                        'accepted': '已通过'
                                      }[stage['status']] ?? '待处理'}',
                                  style: const TextStyle(height: 1.6)),
                              trailing: const Icon(Icons.chevron_right),
                              onTap: () => Navigator.push(
                                  context,
                                  MaterialPageRoute(
                                      builder: (_) => StageDetailPage(
                                          stageId: stage['id'].toString()))))),
                  ],
              loading: () => [const Center(child: CircularProgressIndicator())],
              error: (e, _) => [
                    const Text('进度暂时没有加载成功。'),
                    TextButton(
                        onPressed: () => ref.invalidate(progressStagesProvider),
                        child: const Text('重试'))
                  ]),
        ]));
  }
}

class StageEditor extends ConsumerStatefulWidget {
  const StageEditor({required this.studentId, super.key});
  final String studentId;
  @override
  ConsumerState<StageEditor> createState() => _StageEditorState();
}

class _StageEditorState extends ConsumerState<StageEditor> {
  final title = TextEditingController(), goal = TextEditingController();
  final form = GlobalKey<FormState>();
  String? guidanceId;
  DateTime? dueDate;
  bool busy = false;
  String? error;
  @override
  void dispose() {
    title.dispose();
    goal.dispose();
    super.dispose();
  }

  Future<void> save() async {
    if (busy || !form.currentState!.validate()) return;
    setState(() => busy = true);
    try {
      await ref.read(apiClientProvider).post('/stages', {
        'student_id': widget.studentId,
        'title': title.text.trim(),
        'goal': goal.text.trim(),
        if (dueDate != null)
          'due_at': DateTime.utc(
                  dueDate!.year, dueDate!.month, dueDate!.day, 15, 59, 59)
              .toIso8601String(),
        if (guidanceId != null) 'rule_set_id': guidanceId
      });
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final guidance = ref.watch(guidanceProvider).valueOrNull ?? [];
    return Scaffold(
        appBar: AppBar(title: const Text('添加培养阶段')),
        body: Form(
            key: form,
            child: ListView(padding: const EdgeInsets.all(20), children: [
              TextFormField(
                  controller: title,
                  enabled: !busy,
                  maxLength: 120,
                  decoration: const InputDecoration(
                      labelText: '阶段名称', hintText: '例如：开题准备'),
                  validator: (v) =>
                      (v ?? '').trim().isEmpty ? '请填写阶段名称' : null),
              const SizedBox(height: 16),
              TextFormField(
                  controller: goal,
                  enabled: !busy,
                  minLines: 3,
                  maxLines: 8,
                  decoration: const InputDecoration(
                      labelText: '希望学生完成什么', hintText: '写清研究目标和需要提交的成果'),
                  validator: (v) =>
                      (v ?? '').trim().isEmpty ? '请填写培养目标' : null),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                  onPressed: busy
                      ? null
                      : () async {
                          final date = await showDatePicker(
                              context: context,
                              initialDate: dueDate ??
                                  DateTime.now().add(const Duration(days: 7)),
                              firstDate: DateTime.now(),
                              lastDate: DateTime.now()
                                  .add(const Duration(days: 730)));
                          if (date != null && mounted) {
                            setState(() => dueDate = date);
                          }
                        },
                  icon: const Icon(Icons.calendar_today_outlined),
                  label: Text(dueDate == null
                      ? '设置截止日期（可选）'
                      : '截止日期：${dueDate!.year}-${dueDate!.month.toString().padLeft(2, '0')}-${dueDate!.day.toString().padLeft(2, '0')}')),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                  initialValue: guidanceId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: '使用哪份科研指导'),
                  items: [
                    for (final r in guidance)
                      DropdownMenuItem(
                          value: r['id'].toString(),
                          child: Text(r['title'].toString(),
                              overflow: TextOverflow.ellipsis))
                  ],
                  onChanged:
                      busy ? null : (v) => setState(() => guidanceId = v)),
              const SizedBox(height: 12),
              const Text('选择科研指导后，AI 会结合其中的要求提出任务建议。',
                  style: TextStyle(height: 1.6, color: Color(0xFF717180))),
              if (error != null)
                Text(error!, style: const TextStyle(color: Colors.red)),
              const SizedBox(height: 24),
              FilledButton(
                  onPressed: busy ? null : save,
                  child: Text(busy ? '正在保存…' : '保存阶段')),
            ])));
  }
}

class StageDetailPage extends ConsumerStatefulWidget {
  const StageDetailPage({required this.stageId, super.key});
  final String stageId;
  @override
  ConsumerState<StageDetailPage> createState() => _StageDetailPageState();
}

class _StageDetailPageState extends ConsumerState<StageDetailPage> {
  Map<String, dynamic>? data;
  String? error;
  bool saving = false;
  int epoch = 0;
  bool scopeChanged = false;
  bool scopeValid(int ticket) =>
      mounted &&
      ticket == epoch &&
      !scopeChanged &&
      ref.read(sessionProvider).valueOrNull?.collaborationEnabled == true;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    if (scopeChanged) return;
    final ticket = ++epoch;
    if (ref.read(sessionProvider).valueOrNull?.collaborationEnabled != true) {
      return;
    }
    try {
      final d =
          await ref.read(apiClientProvider).get('/stages/${widget.stageId}');
      if (scopeValid(ticket)) {
        setState(() {
          data = d;
          error = null;
        });
      }
    } catch (e) {
      if (scopeValid(ticket)) setState(() => error = '进度暂时没有加载成功，请下拉重试。');
    }
  }

  Future<void> setGuidance() async {
    if (saving || data == null) return;
    final ticket = epoch;
    setState(() => saving = true);
    try {
      final rows = await ref.read(guidanceProvider.future);
      if (!mounted || !scopeValid(ticket)) return;
      final selected = await showModalBottomSheet<String>(
          context: context,
          showDragHandle: true,
          isScrollControlled: true,
          builder: (context) => SafeArea(
                  child: ListView(shrinkWrap: true, children: [
                const Padding(
                    padding: EdgeInsets.all(20),
                    child: Text('选择这阶段的科研指导',
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.w700))),
                if (rows.isEmpty)
                  const Padding(
                      padding: EdgeInsets.all(20),
                      child: Text('请先在“我的 → 科研指导”中添加内容。')),
                for (final row in rows)
                  ListTile(
                      title: Text(row['title']?.toString() ?? '科研指导'),
                      subtitle: Text(row['phase']?.toString() ?? '通用指导'),
                      onTap: () =>
                          Navigator.pop(context, row['id'].toString())),
              ])));
      if (selected == null || !scopeValid(ticket)) return;
      await ref.read(apiClientProvider).post('/guidance/$selected/apply', {
        'stage_id': widget.stageId,
        'expected_version': data!['stage']['version']
      });
      if (!scopeValid(ticket)) return;
      await load();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('已设置科研指导，AI 会结合这些要求给出建议。')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(e is ApiFailure ? e.message : '暂时无法保存，请稍后重试。')));
      }
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  Future<void> changeStatus(String? decision) async {
    if (saving || data == null) return;
    final ticket = epoch;
    setState(() => saving = true);
    try {
      final stage = data!['stage'] as Map;
      final api = ref.read(apiClientProvider);
      final body = <String, dynamic>{'expected_version': stage['version']};
      if (decision == null) {
        final tasks = data!['tasks'] as List? ?? [];
        if (tasks.isEmpty || tasks.any((t) => t['status'] != 'completed')) {
          throw const ApiFailure('请先完成本阶段的任务，再提交验收。');
        }
        final refs = <Map<String, dynamic>>[];
        for (final task in tasks) {
          final detail = await api.get('/tasks/${task['id']}');
          if (!scopeValid(ticket)) return;
          for (final entry in detail['evidence_refs'] as List? ?? []) {
            if (entry is Map) refs.add(Map<String, dynamic>.from(entry));
          }
        }
        if (refs.isEmpty) throw const ApiFailure('任务还没有提交成果材料，请先在任务详情中补充材料。');
        body['evidence_refs'] = refs;
      } else {
        final controller = TextEditingController();
        if (!mounted) {
          controller.dispose();
          return;
        }
        final confirmed = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
                    title: Text(decision == 'accept' ? '确认阶段通过' : '请学生补充修改'),
                    content: TextField(
                        controller: controller,
                        minLines: 3,
                        maxLines: 6,
                        maxLength: 5000,
                        decoration: const InputDecoration(labelText: '给学生的建议')),
                    actions: [
                      TextButton(
                          onPressed: () => Navigator.pop(context, false),
                          child: const Text('再看看')),
                      FilledButton(
                          onPressed: () => Navigator.pop(context, true),
                          child: const Text('发布意见'))
                    ]));
        final comment = controller.text.trim();
        // The dialog finishes its close animation before the controller is released.
        await Future<void>.delayed(const Duration(milliseconds: 300));
        controller.dispose();
        if (confirmed != true || !scopeValid(ticket)) return;
        body.addAll({'decision': decision, 'comment': comment});
      }
      if (!scopeValid(ticket)) return;
      await api.write(
          '/stages/${widget.stageId}/${decision == null ? 'submit' : 'review'}',
          body);
      if (!scopeValid(ticket)) return;
      await load();
      ref.invalidate(progressStagesProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(decision == null ? '已提交，等待导师验收。' : '意见已发布，学生可以查看。')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(e is ApiFailure ? e.message : '暂时无法保存，请稍后重试。')));
      }
      if (e is ApiFailure && e.status == 409 && scopeValid(ticket)) {
        await load();
      }
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    void clearScope() {
      scopeChanged = true;
      epoch++;
      data = null;
      error = '身份或查看范围已变化，请返回后重新打开。';
      saving = false;
      setState(() {});
    }

    ref.listen(sessionProvider, (previous, next) {
      if (!identical(previous?.valueOrNull, next.valueOrNull)) clearScope();
    });
    ref.listen(roleProvider, (previous, next) {
      if (previous != next) clearScope();
    });
    ref.listen(selectedStudentProvider, (previous, next) {
      if (previous != next) clearScope();
    });
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    final stage = data?['stage'] as Map? ?? {};
    final tasks = data?['tasks'] as List? ?? [];
    final done = tasks.where((t) => t['status'] == 'completed').length;
    return Scaffold(
        appBar: AppBar(title: Text(stage['title']?.toString() ?? '培养阶段')),
        body: RefreshIndicator(
            onRefresh: load,
            child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(20),
                children: [
                  if (data == null && error == null)
                    const Center(child: CircularProgressIndicator()),
                  if (error != null) Text(error!),
                  if (data != null) ...[
                    if (mentor && stage['status'] == 'active')
                      OutlinedButton.icon(
                          onPressed: saving ? null : setGuidance,
                          icon: const Icon(Icons.menu_book_outlined),
                          label: const Text('设置科研指导')),
                    FilledButton.icon(
                        onPressed: () => context
                            .push('/assistant?stage_id=${widget.stageId}'),
                        icon: const Icon(Icons.auto_awesome),
                        label: const Text('向 AI 科研导师请教')),
                    AiActionPanel(
                        title: mentor ? 'AI 阶段任务建议' : 'AI 阶段下一步',
                        kind: mentor ? 'stage_plan' : 'research_qa',
                        stageId: widget.stageId,
                        question: '对照阶段目标和实际任务，建议下一步做什么？哪些材料还不足？',
                        action: '分析当前阶段'),
                    if (mentor)
                      AiActionPanel(
                          title: 'AI 风险与调整安排',
                          kind: 'risk_brief',
                          stageId: widget.stageId,
                          action: '分析未来 7 天风险'),
                    Card(
                        child: Padding(
                            padding: const EdgeInsets.all(20),
                            child: Column(
                                crossAxisAlignment: CrossAxisAlignment.stretch,
                                children: [
                                  const Text('当前目标',
                                      style: TextStyle(
                                          fontSize: 17,
                                          fontWeight: FontWeight.w700)),
                                  const SizedBox(height: 10),
                                  Text(stage['goal']?.toString() ?? '',
                                      style: const TextStyle(height: 1.7)),
                                  if (stage['due_at'] != null) ...[
                                    const SizedBox(height: 10),
                                    Text(
                                        '截止日期：${stage['due_at'].toString().split('T').first}'),
                                  ],
                                  const SizedBox(height: 18),
                                  LinearProgressIndicator(
                                      value: tasks.isEmpty
                                          ? 0
                                          : done / tasks.length,
                                      borderRadius: BorderRadius.circular(5),
                                      minHeight: 7),
                                  const SizedBox(height: 10),
                                  Text('已完成 $done / ${tasks.length} 项任务'),
                                ]))),
                    for (final requirement
                        in data?['requirements'] as List? ?? [])
                      ListTile(
                          leading: const Icon(Icons.check_circle_outline),
                          title: Text(requirement is Map
                              ? (requirement['title'] ??
                                      requirement['description'] ??
                                      '需准备的材料')
                                  .toString()
                              : requirement.toString())),
                    for (final task in tasks)
                      Card(
                          child: ListTile(
                              trailing: const Icon(Icons.chevron_right),
                              onTap: () async {
                                ref.read(selectedTaskProvider.notifier).state =
                                    ResearchTask.fromJson(
                                        Map<String, dynamic>.from(task));
                                await context.push('/task');
                                if (mounted) await load();
                              },
                              title: Text(task['title'].toString()),
                              subtitle: Text(const {
                                    'todo': '待开始',
                                    'in_progress': '进行中',
                                    'blocked': '遇到困难',
                                    'submitted': '待导师验收',
                                    'completed': '已完成'
                                  }[task['status']] ??
                                  '待处理'))),
                    const SizedBox(height: 20),
                    if (!mentor && stage['status'] == 'active')
                      FilledButton(
                          onPressed: saving ? null : () => changeStatus(null),
                          child: const Text('提交阶段验收')),
                    if (stage['status'] == 'submitted')
                      const Text('阶段材料已提交，等待导师审阅。',
                          style: TextStyle(height: 1.7)),
                    if (mentor && stage['status'] == 'submitted') ...[
                      FilledButton(
                          onPressed:
                              saving ? null : () => changeStatus('accept'),
                          child: const Text('通过阶段验收')),
                      OutlinedButton(
                          onPressed:
                              saving ? null : () => changeStatus('return'),
                          child: const Text('请学生补充修改')),
                    ],
                    if (stage['status'] == 'accepted')
                      const Text('导师已确认本阶段通过。',
                          style: TextStyle(color: Colors.teal, height: 1.7)),
                    if (data?['submission'] is Map &&
                        (data!['submission']['review_comment']?.toString() ??
                                '')
                            .isNotEmpty)
                      Card(
                          child: Padding(
                              padding: const EdgeInsets.all(18),
                              child: Text(
                                  '导师建议\n${data!['submission']['review_comment']}',
                                  style: const TextStyle(height: 1.7)))),
                  ],
                ])));
  }
}
