import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'data.dart';
import 'models.dart';
import 'design_system.dart';

final guidanceProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
  final session = ref.watch(sessionProvider).valueOrNull;
  ref.watch(roleProvider);
  if (session?.collaborationEnabled != true) return [];
  final data = await ref.watch(apiClientProvider).get('/guidance');
  return (data['items'] as List? ?? [])
      .whereType<Map>()
      .map((e) => Map<String, dynamic>.from(e))
      .toList();
});

class GuidancePage extends ConsumerWidget {
  const GuidancePage({super.key});
  Future<void> add(BuildContext context, WidgetRef ref) async {
    final saved = await Navigator.of(context)
        .push<bool>(MaterialPageRoute(builder: (_) => const GuidanceEditor()));
    if (saved == true) ref.invalidate(guidanceProvider);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    final records = ref.watch(guidanceProvider);
    return Scaffold(
        appBar: AppBar(title: const Text('科研指导')),
        body: RefreshIndicator(
            onRefresh: () async {
              ref.invalidate(guidanceProvider);
              await ref.read(guidanceProvider.future);
            },
            child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(20),
                children: [
                  ResearchPanel(
                      title: mentor ? '把指导经验留下来' : '实验室的科研指导',
                      icon: Icons.school_outlined,
                      color: ResearchDesign.blue,
                      child: const Text('阶段目标与材料要求会成为 AI 分析的依据。')),
                  const SizedBox(height: 10),
                  Text(
                      mentor
                          ? '写下学生需要准备的材料和注意事项，AI 会结合这些要求帮助学生安排下一步。'
                          : '遇到不清楚的要求，可以结合这些内容向导师请教。',
                      style: const TextStyle(
                          height: 1.7, color: Color(0xFF717180))),
                  const SizedBox(height: 20),
                  if (mentor)
                    FilledButton.icon(
                        onPressed: () => add(context, ref),
                        icon: const Icon(Icons.add),
                        label: const Text('添加科研指导')),
                  const SizedBox(height: 18),
                  ...records.when(
                      data: (rows) => [
                            if (rows.isEmpty)
                              const Padding(
                                  padding: EdgeInsets.all(24),
                                  child: Text('还没有科研指导内容',
                                      textAlign: TextAlign.center)),
                            for (final row in rows)
                              Card(
                                  child: Padding(
                                      padding: const EdgeInsets.all(18),
                                      child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.stretch,
                                          children: [
                                            Text(
                                                row['title']?.toString() ??
                                                    '科研指导',
                                                style: const TextStyle(
                                                    fontSize: 17,
                                                    fontWeight:
                                                        FontWeight.w700)),
                                            const SizedBox(height: 6),
                                            Text(
                                                row['phase']?.toString() ??
                                                    '通用指导',
                                                style: const TextStyle(
                                                    color: Color(0xFF5740F7))),
                                            const SizedBox(height: 12),
                                            Text(
                                                row['content']?.toString() ??
                                                    '',
                                                style: const TextStyle(
                                                    height: 1.7)),
                                            if ((row['requirements'] as List? ??
                                                    [])
                                                .isNotEmpty) ...[
                                              const SizedBox(height: 16),
                                              const Text('需要准备',
                                                  style: TextStyle(
                                                      fontWeight:
                                                          FontWeight.w600)),
                                              for (final item
                                                  in row['requirements']
                                                      as List)
                                                Padding(
                                                    padding:
                                                        const EdgeInsets.only(
                                                            top: 8),
                                                    child: Row(
                                                        crossAxisAlignment:
                                                            CrossAxisAlignment
                                                                .start,
                                                        children: [
                                                          const Icon(
                                                              Icons
                                                                  .check_circle_outline,
                                                              size: 18,
                                                              color: Color(
                                                                  0xFF5740F7)),
                                                          const SizedBox(
                                                              width: 8),
                                                          Expanded(
                                                              child: Text(
                                                                  item
                                                                      .toString(),
                                                                  style: const TextStyle(
                                                                      height:
                                                                          1.5))),
                                                        ])),
                                            ],
                                          ]))),
                          ],
                      loading: () =>
                          [const Center(child: CircularProgressIndicator())],
                      error: (e, _) => [
                            const Text('暂时没有加载成功，请重试。'),
                            TextButton(
                                onPressed: () =>
                                    ref.invalidate(guidanceProvider),
                                child: const Text('重新加载')),
                          ]),
                ])));
  }
}

class GuidanceEditor extends ConsumerStatefulWidget {
  const GuidanceEditor({super.key});
  @override
  ConsumerState<GuidanceEditor> createState() => _GuidanceEditorState();
}

class _GuidanceEditorState extends ConsumerState<GuidanceEditor> {
  final form = GlobalKey<FormState>();
  final title = TextEditingController(),
      content = TextEditingController(),
      requirements = TextEditingController();
  String phase = '开题准备';
  String? labId;
  List<Map> mentorLabs() =>
      (ref.read(sessionProvider).valueOrNull?.memberships ?? [])
          .whereType<Map>()
          .where((m) => m['role'] == 'mentor' && m['active'] != false)
          .toList();
  bool busy = false;
  String? error;
  @override
  void dispose() {
    title.dispose();
    content.dispose();
    requirements.dispose();
    super.dispose();
  }

  Future<void> save() async {
    if (busy || !form.currentState!.validate()) return;
    final session = ref.read(sessionProvider).valueOrNull;
    if (session?.collaborationEnabled != true ||
        ref.read(roleProvider) != UserRole.mentor) {
      return;
    }
    final labs = mentorLabs();
    final targetLab =
        labs.length == 1 ? labs.first['lab_id']?.toString() : labId;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await ref.read(apiClientProvider).write('/guidance', {
        if (targetLab != null) 'lab_id': targetLab,
        'title': title.text.trim(),
        'phase': phase,
        'content': content.text.trim(),
        'requirements': requirements.text
            .split('\n')
            .map((s) => s.trim())
            .where((s) => s.isNotEmpty)
            .toSet()
            .toList()
      });
      if (mounted &&
          identical(session, ref.read(sessionProvider).valueOrNull)) {
        Navigator.pop(context, true);
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(sessionProvider);
    final allowed = ref.watch(roleProvider) == UserRole.mentor &&
        ref.watch(sessionProvider).valueOrNull?.collaborationEnabled == true;
    if (!allowed) {
      return Scaffold(
          appBar: AppBar(title: const Text('科研指导')),
          body: const Center(child: Text('请重新登录后继续。')));
    }
    return Scaffold(
        appBar: AppBar(title: const Text('添加科研指导')),
        body: Form(
            key: form,
            child: ListView(padding: const EdgeInsets.all(20), children: [
              const Text('用你平时指导学生的方式写即可。保存后，同实验室的师生可以查看。',
                  style: TextStyle(height: 1.7)),
              const SizedBox(height: 20),
              if (mentorLabs().length > 1) ...[
                DropdownButtonFormField<String>(
                    initialValue: labId,
                    decoration: const InputDecoration(labelText: '所属实验室'),
                    items: [
                      for (final lab in mentorLabs())
                        DropdownMenuItem(
                            value: lab['lab_id'].toString(),
                            child: Text(lab['lab_name']?.toString() ??
                                '实验室 ${mentorLabs().indexOf(lab) + 1}'))
                    ],
                    validator: (v) => v == null ? '请选择所属实验室' : null,
                    onChanged: busy ? null : (v) => setState(() => labId = v)),
                const SizedBox(height: 16),
              ],
              TextFormField(
                  controller: title,
                  enabled: !busy,
                  maxLength: 120,
                  decoration: const InputDecoration(labelText: '指导标题'),
                  validator: (v) => (v ?? '').trim().isEmpty ? '请填写标题' : null),
              const SizedBox(height: 16),
              DropdownButtonFormField<String>(
                  initialValue: phase,
                  decoration: const InputDecoration(labelText: '适用阶段'),
                  items: [
                    for (final name in [
                      '开题准备',
                      '文献学习',
                      '实验研究',
                      '论文写作',
                      '阶段验收',
                      '通用指导'
                    ])
                      DropdownMenuItem(value: name, child: Text(name))
                  ],
                  onChanged: busy ? null : (v) => setState(() => phase = v!)),
              const SizedBox(height: 16),
              TextFormField(
                  controller: content,
                  enabled: !busy,
                  minLines: 5,
                  maxLines: 12,
                  maxLength: 12000,
                  decoration: const InputDecoration(
                      labelText: '指导内容', hintText: '例如：先比较相关方法，再说明你准备解决的问题。'),
                  validator: (v) =>
                      (v ?? '').trim().isEmpty ? '请填写指导内容' : null),
              const SizedBox(height: 16),
              TextFormField(
                  controller: requirements,
                  enabled: !busy,
                  minLines: 3,
                  maxLines: 8,
                  decoration: const InputDecoration(
                      labelText: '需要准备的材料（每行一项）', hintText: '研究问题\n文献比较\n实验方案'),
                  validator: (v) {
                    final rows =
                        (v ?? '').split('\n').where((s) => s.trim().isNotEmpty);
                    return rows.length > 30 ||
                            rows.any((s) => s.trim().length > 1000)
                        ? '最多填写30项，每项不超过1000字'
                        : null;
                  }),
              if (error != null)
                Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Text(error!,
                        style: const TextStyle(color: Colors.red))),
              const SizedBox(height: 24),
              FilledButton(
                  onPressed: busy ? null : save,
                  child: Text(busy ? '正在保存…' : '保存指导')),
            ])));
  }
}
