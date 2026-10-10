import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'design_system.dart';
import 'ai_action.dart';
import 'data.dart';
import 'models.dart';
import 'agent_result_cards.dart';

Map<String, dynamic> experimentDraftBody(
        {required String purpose,
        required String results,
        required String reflection,
        String? environment,
        String? stepsText,
        String? taskId,
        String visibility = 'private',
        Map<String, dynamic>? existing}) =>
    {
      'purpose': purpose.trim(),
      'results': results.trim(),
      'reflection': reflection.trim(),
      'parameters': {
        if (existing?['parameters'] is Map)
          for (final entry in (existing!['parameters'] as Map).entries)
            if (environment == null || entry.key != '运行环境与设置')
              entry.key.toString(): entry.value,
        if (environment != null && environment.trim().isNotEmpty)
          '运行环境与设置': environment.trim(),
      },
      'steps': stepsText == null
          ? (existing?['steps'] is List ? existing!['steps'] : <String>[])
          : stepsText
              .split('\n')
              .map((s) => s.trim())
              .where((s) => s.isNotEmpty)
              .toList(),
      'file_ids':
          existing?['file_ids'] is List ? existing!['file_ids'] : <String>[],
      if (existing != null) 'expected_version': existing['version'],
      if (existing == null) ...{'task_id': taskId, 'visibility': visibility},
    };
bool canConfirmExperiment(Map<String, dynamic>? record,
        {required bool dirty}) =>
    !dirty &&
    record?['status'] == 'draft' &&
    record?['version'] is int &&
    record?['results'] is String &&
    (record!['results'] as String).trim().isNotEmpty;

class ExperimentSavedSummary extends StatelessWidget {
  const ExperimentSavedSummary({required this.record, super.key});
  final Map<String, dynamic> record;

  String readable(Object? value) {
    if (value == null) return '未填写';
    if (value is Map) {
      return value.entries
          .map((entry) => '${entry.key}：${readable(entry.value)}')
          .join('\n');
    }
    if (value is List) return value.map(readable).join('、');
    return value.toString();
  }

  String readableStep(Object? value, int index) {
    final text = readable(value).trim();
    return RegExp(r'^\d+\s*[.、．)）]').hasMatch(text)
        ? text
        : '${index + 1}. $text';
  }

  @override
  Widget build(BuildContext context) {
    final parameters = record['parameters'];
    final steps = record['steps'];
    final files = record['file_ids'];
    return ResearchPanel(
        title: '已保存的记录内容',
        icon: Icons.fact_check_outlined,
        color: ResearchDesign.teal,
        child:
            Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (parameters is Map && parameters.isNotEmpty) ...[
            const Text('环境与参数', style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            for (final entry in parameters.entries)
              Text('${entry.key}：${readable(entry.value)}'),
            const SizedBox(height: 12),
          ],
          if (steps is List && steps.isNotEmpty) ...[
            const Text('操作步骤', style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            for (var index = 0; index < steps.length; index++)
              Text(readableStep(steps[index], index)),
            const SizedBox(height: 12),
          ],
          Text(files is List && files.isNotEmpty
              ? '已关联 ${files.length} 个文件'
              : '尚未关联附件'),
        ]));
  }
}

class ExperimentEditorScreen extends ConsumerStatefulWidget {
  const ExperimentEditorScreen(
      {required this.taskId,
      this.studentId,
      this.stageId,
      this.returnOnSave = false,
      super.key});
  final bool returnOnSave;
  final String taskId;
  final String? studentId, stageId;
  @override
  ConsumerState<ExperimentEditorScreen> createState() =>
      _ExperimentEditorScreenState();
}

class _ExperimentEditorScreenState
    extends ConsumerState<ExperimentEditorScreen> {
  final purpose = TextEditingController(),
      results = TextEditingController(),
      reflection = TextEditingController(),
      environment = TextEditingController(),
      steps = TextEditingController();
  List<Map<String, dynamic>> records = [];
  Map<String, dynamic>? record;
  String visibility = 'private';
  String? cursor, error, selectedId;
  bool busy = false, dirty = false, loadingFields = false, conflict = false;
  int epoch = 0;
  bool detailVerified = false;
  bool get mentor => ref.read(roleProvider) == UserRole.mentor;
  bool get allowed =>
      ref.read(sessionProvider).valueOrNull?.collaborationEnabled == true;
  @override
  void initState() {
    super.initState();
    for (final c in [purpose, results, reflection, environment, steps]) {
      c.addListener(() {
        if (!loadingFields && mounted) setState(() => dirty = true);
      });
    }
    Future.microtask(loadList);
  }

  @override
  void dispose() {
    epoch++;
    for (final c in [purpose, results, reflection, environment, steps]) {
      c.dispose();
    }
    super.dispose();
  }

  void fill(Map<String, dynamic>? value) {
    loadingFields = true;
    record = value;
    detailVerified =
        value != null && value['id'] is String && value['version'] is int;
    selectedId = value?['id']?.toString();
    purpose.text = displayValue(value?['purpose']);
    results.text = displayValue(value?['results']);
    reflection.text = displayValue(value?['reflection']);
    environment.text = value?['parameters'] is Map
        ? displayValue(value!['parameters']['运行环境与设置'])
        : '';
    steps.text =
        value?['steps'] is List ? (value!['steps'] as List).join('\n') : '';
    visibility = value?['visibility']?.toString() ?? 'private';
    dirty = false;
    conflict = false;
    loadingFields = false;
  }

  Future<void> loadList({bool more = false}) async {
    if (busy || !allowed) return;
    final current = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final next = more ? cursor : null;
      final data =
          await ref.read(apiClientProvider).get('/experiments', query: {
        'task_id': widget.taskId,
        'limit': 20,
        if (mentor && widget.studentId != null) 'student_id': widget.studentId,
        if (next != null) 'cursor': next
      });
      if (!mounted || current != epoch) return;
      final nextCursor = data['next_cursor'];
      if (nextCursor != null &&
          (nextCursor is! String || nextCursor.isEmpty || nextCursor == next)) {
        throw const ApiFailure('实验记录分页响应异常');
      }
      setState(() {
        if (!more) records = [];
        final ids = records.map((e) => e['id']).toSet();
        records.addAll(objectRows(data['experiments'])
            .where((e) => e['id'] is String && ids.add(e['id'])));
        cursor = nextCursor as String?;
      });
    } catch (e) {
      if (mounted && current == epoch) setState(() => error = e.toString());
    } finally {
      if (mounted && current == epoch) setState(() => busy = false);
    }
  }

  Future<bool> discardPermission() async {
    if (!dirty) return true;
    return await showDialog<bool>(
            context: context,
            builder: (c) =>
                AlertDialog(title: const Text('放弃未保存的修改？'), actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(c, false),
                      child: const Text('继续编辑')),
                  TextButton(
                      onPressed: () => Navigator.pop(c, true),
                      child: const Text('放弃修改'))
                ])) ??
        false;
  }

  Future<void> open(String id) async {
    if (busy || !allowed || !await discardPermission() || !mounted) return;
    final current = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final data = await ref.read(apiClientProvider).get('/experiments/$id');
      if (mounted && current == epoch) setState(() => fill(data));
    } catch (e) {
      if (mounted && current == epoch) setState(() => error = e.toString());
    } finally {
      if (mounted && current == epoch) setState(() => busy = false);
    }
  }

  Future<void> save() async {
    if (busy ||
        mentor ||
        !allowed ||
        conflict ||
        (record != null && !detailVerified)) {
      return;
    }
    if (purpose.text.trim().isEmpty) {
      setState(() => error = '请填写实验目的');
      return;
    }
    final current = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final api = ref.read(apiClientProvider);
      final body = experimentDraftBody(
          purpose: purpose.text,
          results: results.text,
          reflection: reflection.text,
          environment: environment.text,
          stepsText: steps.text,
          taskId: widget.taskId,
          visibility: visibility,
          existing: record);
      if (widget.returnOnSave && results.text.trim().isEmpty) {
        throw const ApiFailure('请填写材料结果，保存并确认后 AI 才能使用。');
      }
      final saved = await api.write(
          record == null ? '/experiments' : '/experiments/$selectedId', body,
          method: record == null ? 'POST' : 'PATCH');
      if (!mounted || current != epoch) return;
      final id = record == null ? saved['id'] : selectedId;
      if (id is! String || id.isEmpty) throw const ApiFailure('保存响应缺少实验记录 ID');
      // Retain the saved identity if reading back fails, avoiding duplicate creates.
      selectedId = id;
      record = {...saved, 'id': id};
      detailVerified = false;
      final actual = await api.get('/experiments/$id');
      if (!mounted || current != epoch) return;
      setState(() {
        fill(actual);
      });
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('实验草稿已保存，请核对后确认')));
    } catch (e) {
      if (mounted && current == epoch) {
        setState(() {
          conflict = e is ApiFailure && e.status == 409;
          error = conflict ? '记录已更新，输入已保留。请复制所需内容，再刷新最新记录后修改。' : e.toString();
        });
      }
    } finally {
      if (mounted && current == epoch) setState(() => busy = false);
    }
    if (mounted && current == epoch && error == null) {
      if (widget.returnOnSave) {
        await confirm();
      } else {
        await loadList();
      }
    }
  }

  Future<void> confirm() async {
    if (busy ||
        mentor ||
        !allowed ||
        conflict ||
        !detailVerified ||
        !canConfirmExperiment(record, dirty: dirty)) {
      return;
    }
    final current = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final api = ref.read(apiClientProvider);
      await api.post('/experiments/$selectedId/confirm',
          {'expected_version': record!['version']});
      if (!mounted || current != epoch) return;
      final actual = await api.get('/experiments/$selectedId');
      if (mounted && current == epoch) setState(() => fill(actual));
    } catch (e) {
      if (mounted && current == epoch) {
        setState(() {
          conflict = e is ApiFailure && e.status == 409;
          error = conflict ? '记录已更新，输入已保留。请刷新最新记录后重新核对。' : '确认未完成：$e';
        });
      }
    } finally {
      if (mounted && current == epoch) setState(() => busy = false);
    }
    if (mounted && current == epoch && error == null) {
      if (widget.returnOnSave && record?['status'] == 'confirmed') {
        Navigator.of(context).pop(true);
      } else {
        await loadList();
      }
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
          fill(null);
          records = [];
          busy = false;
          error = '登录状态已改变，请重新打开任务';
        });
      }
    });
    ref.listen(roleProvider, (previous, next) {
      if (previous != next) {
        epoch++;
        setState(() {
          fill(null);
          records = [];
          busy = false;
        });
        Future.microtask(loadList);
      }
    });
    ref.listen(selectedStudentProvider, (previous, next) {
      if (previous != next && ref.read(roleProvider) == UserRole.mentor) {
        epoch++;
        setState(() {
          fill(null);
          records = [];
          busy = false;
          error = '查看范围已改变，请返回任务重新打开。';
        });
      }
    });
    final readOnly = ref.watch(roleProvider) == UserRole.mentor;
    final enabled =
        ref.watch(sessionProvider).valueOrNull?.collaborationEnabled == true;
    return PopScope(
        canPop: !dirty && !busy,
        onPopInvokedWithResult: (didPop, result) async {
          if (!didPop &&
              !busy &&
              await discardPermission() &&
              context.mounted) {
            setState(() => dirty = false);
            Navigator.of(context).pop();
          }
        },
        child: Scaffold(
          appBar: AppBar(title: Text(readOnly ? '查看实验记录' : '记录实验')),
          body: ListView(padding: const EdgeInsets.all(20), children: [
            if (!enabled) const Text('请登录并完成实验室绑定。'),
            const Text('最近实验记录',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            if (!busy && records.isEmpty) const Text('暂无可查看的实验记录'),
            for (final row in records)
              ListTile(
                  title: Text(displayValue(row['purpose'])),
                  subtitle: Text(
                      '${row['status'] == 'confirmed' ? '已确认' : '草稿'} · ${beijingTime(row['created_at'])}'),
                  selected: row['id'] == selectedId,
                  onTap: busy ? null : () => open(row['id'])),
            if (cursor != null)
              TextButton(
                  onPressed: busy ? null : () => loadList(more: true),
                  child: const Text('加载更多')),
            if (!readOnly)
              OutlinedButton(
                  onPressed: busy || !enabled
                      ? null
                      : () async {
                          if (await discardPermission() && mounted) {
                            setState(() {
                              fill(null);
                              error = null;
                            });
                          }
                        },
                  child: const Text('新建实验记录')),
            if (!readOnly || record != null) ...[
              if (record != null)
                Text('状态：${record!['status'] == 'confirmed' ? '已确认' : '草稿'}'),
              const SizedBox(height: 12),
              ResearchPanel(
                  title: '记录目标',
                  icon: Icons.flag_outlined,
                  color: ResearchDesign.purple,
                  child: TextField(
                      controller: purpose,
                      readOnly: readOnly,
                      enabled: !busy && enabled,
                      maxLines: 2,
                      maxLength: 5000,
                      decoration: const InputDecoration(labelText: '实验目的'))),
              const SizedBox(height: 16),
              ResearchPanel(
                  title: '环境与参数',
                  icon: Icons.tune,
                  color: ResearchDesign.blue,
                  child: TextField(
                      controller: environment,
                      readOnly: readOnly,
                      enabled: !busy && enabled,
                      minLines: 2,
                      maxLines: 5,
                      maxLength: 5000,
                      decoration: const InputDecoration(
                          labelText: '运行环境与设置',
                          hintText: '写清使用的软件、数据划分和主要参数，方便以后复查。'))),
              const SizedBox(height: 16),
              ResearchPanel(
                  title: '详细步骤',
                  icon: Icons.format_list_numbered,
                  color: ResearchDesign.blue,
                  child: TextField(
                      controller: steps,
                      readOnly: readOnly,
                      enabled: !busy && enabled,
                      minLines: 3,
                      maxLines: 8,
                      maxLength: 20000,
                      decoration:
                          const InputDecoration(labelText: '操作步骤（每行一步）'))),
              const SizedBox(height: 16),
              ResearchPanel(
                  title: '结果摘要',
                  icon: Icons.analytics_outlined,
                  color: ResearchDesign.teal,
                  child: TextField(
                      controller: results,
                      readOnly: readOnly,
                      enabled: !busy && enabled,
                      maxLines: 5,
                      maxLength: 20000,
                      decoration:
                          const InputDecoration(labelText: '实验结果（确认前必填）'))),
              const SizedBox(height: 16),
              ResearchPanel(
                  title: '反思与后续',
                  icon: Icons.lightbulb_outline,
                  color: ResearchDesign.orange,
                  child: TextField(
                      controller: reflection,
                      readOnly: readOnly,
                      enabled: !busy && enabled,
                      maxLines: 3,
                      maxLength: 10000,
                      decoration: const InputDecoration(labelText: '反思与下一步'))),
              if (record == null && !readOnly)
                DropdownButtonFormField<String>(
                    initialValue: visibility,
                    items: const [
                      DropdownMenuItem(
                          value: 'private', child: Text('仅自己（默认）')),
                      DropdownMenuItem(value: 'mentors', child: Text('向导师共享'))
                    ],
                    onChanged: busy
                        ? null
                        : (value) => setState(() {
                              visibility = value!;
                              dirty = true;
                            }),
                    decoration: const InputDecoration(labelText: '可见范围'))
              else
                Text(
                    '可见范围：${visibility == 'private' ? '仅自己' : visibility == 'mentors' ? '导师' : '实验室'}（保留原设置）'),
              const Text('仅自己保存的记录不会自动共享；选择向导师共享后，导师可查看已确认的记录。'),
              if (record != null) ExperimentSavedSummary(record: record!),
              if (!readOnly) ...[
                FilledButton(
                    onPressed: busy ||
                            !enabled ||
                            conflict ||
                            (record != null && !detailVerified) ||
                            (record != null && !dirty)
                        ? null
                        : save,
                    child: Text(widget.returnOnSave ? '保存并确认材料' : '保存草稿')),
                if (results.text.trim().isEmpty) const Text('请补充实验结果后再确认。'),
                if (dirty && record != null) const Text('有未保存的修改，请先保存并核对内容。'),
                OutlinedButton(
                    onPressed: busy ||
                            !enabled ||
                            conflict ||
                            !detailVerified ||
                            !canConfirmExperiment(record, dirty: dirty)
                        ? null
                        : confirm,
                    child: const Text('确认这份记录')),
                AiActionPanel(
                    title: 'AI 检查实验记录',
                    kind: 'research_qa',
                    stageId: widget.stageId,
                    enabled: record?['status'] == 'confirmed' &&
                        !dirty &&
                        !busy &&
                        detailVerified,
                    description: record?['status'] == 'confirmed' && !dirty
                        ? '核对已保存的目的、环境、步骤与结果，指出缺少的证据。分析不会修改或重新确认记录。'
                        : '请先保存并确认这份记录，再让 AI 检查。',
                    question:
                        ('请对照当前任务与阶段目标，检查这份已确认的实验记录：目的：${record?['purpose'] ?? ''}；环境与参数：${record?['parameters'] ?? ''}；步骤：${record?['steps'] ?? ''}；结果：${record?['results'] ?? ''}；反思：${record?['reflection'] ?? ''}。指出可以核对的依据与缺失材料，不要补造数据。')
                            .characters
                            .take(4500)
                            .toString(),
                    action: '检查已确认记录'),
              ],
            ],
            if (busy) const LinearProgressIndicator(),
            if (error != null) ...[
              SelectableText(error!, style: const TextStyle(color: Colors.red)),
              TextButton(
                  onPressed: busy
                      ? null
                      : selectedId != null
                          ? () => open(selectedId!)
                          : loadList,
                  child: const Text('刷新最新记录'))
            ],
          ]),
        ));
  }
}
