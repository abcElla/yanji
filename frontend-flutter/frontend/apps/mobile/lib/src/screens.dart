import 'dart:async';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:uuid/uuid.dart';
import 'data.dart';
import 'models.dart';
import 'widgets.dart';
import 'design_system.dart';
import 'reminder_presentation.dart';
import 'mentor_onboarding.dart';
import 'agent_result_cards.dart';
import 'experiment_editor.dart';
import 'ai_action.dart';
import 'theses.dart' show ingestLabel;
import 'research_followups.dart';

const _pagePadding = EdgeInsets.fromLTRB(20, 16, 20, 28);
String _text(dynamic value) => value?.toString() ?? '';
Widget _empty(String text) => ResearchEmpty(text);

class RemoteView<T> extends StatelessWidget {
  const RemoteView(
      {required this.value,
      required this.retry,
      required this.builder,
      super.key});
  final AsyncValue<T> value;
  final VoidCallback retry;
  final Widget Function(T) builder;
  @override
  Widget build(BuildContext context) => value.when(
      data: builder,
      loading: () => const Padding(
          padding: EdgeInsets.all(30),
          child: Center(child: CircularProgressIndicator())),
      error: (e, _) => ResearchPanel(
          title: '暂时无法加载',
          icon: Icons.cloud_off_outlined,
          color: ResearchDesign.orange,
          child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(e.toString()),
                TextButton(onPressed: retry, child: const Text('重试'))
              ])));
}

Future<void> perform(
    BuildContext context, Future<void> Function() action) async {
  try {
    await action();
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(e.toString())));
    }
  }
}

Future<String?> askText(BuildContext context, String title,
    {String initial = '', int lines = 1}) async {
  final input = TextEditingController(text: initial);
  final value = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
              title: Text(title),
              content: TextField(
                  controller: input, maxLines: lines, autofocus: true),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(c), child: const Text('取消')),
                FilledButton(
                    onPressed: () {
                      if (input.text.trim().isNotEmpty) {
                        Navigator.pop(c, input.text.trim());
                      }
                    },
                    child: const Text('确认'))
              ]));
  // Dispose after the dialog's closing animation has removed the TextField.
  Future<void>.delayed(const Duration(seconds: 1), input.dispose);
  return value;
}

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});
  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final username = TextEditingController();
  final password = TextEditingController();
  bool loading = false;
  String? error;
  @override
  void initState() {
    super.initState();
    Future.microtask(() async {
      await ref.read(sessionProvider.notifier).restore();
      if (mounted && ref.read(sessionProvider).valueOrNull != null) {
        context.go('/overview');
      }
    });
  }

  @override
  void dispose() {
    username.dispose();
    password.dispose();
    super.dispose();
  }

  Future<void> login() async {
    if (loading) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      await ref
          .read(sessionProvider.notifier)
          .login(username.text, password.text);
      if (mounted) context.go('/overview');
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider);
    return Scaffold(
        body: SafeArea(
            child: Center(
                child: SingleChildScrollView(
                    padding: const EdgeInsets.all(28),
                    child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 440),
                        child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const Align(
                                  alignment: Alignment.centerLeft,
                                  child:
                                      ResearchIcon(Icons.auto_stories_rounded)),
                              const SizedBox(height: 18),
                              Text('欢迎回到研迹',
                                  style: Theme.of(context)
                                      .textTheme
                                      .headlineSmall),
                              const SizedBox(height: 8),
                              const Text('把任务、记录和导师反馈连成一条可追溯的科研路径。'),
                              const SizedBox(height: 34),
                              TextField(
                                  controller: username,
                                  decoration: const InputDecoration(
                                      labelText: '账号',
                                      prefixIcon: Icon(Icons.person_outline))),
                              const SizedBox(height: 14),
                              TextField(
                                  controller: password,
                                  obscureText: true,
                                  onSubmitted: (_) => login(),
                                  decoration: const InputDecoration(
                                      labelText: '密码',
                                      prefixIcon: Icon(Icons.lock_outline))),
                              if (error != null || session.hasError)
                                Padding(
                                    padding: const EdgeInsets.only(top: 12),
                                    child: Text(
                                        error ?? session.error.toString(),
                                        style: const TextStyle(
                                            color: Colors.red))),
                              const SizedBox(height: 22),
                              FilledButton(
                                  onPressed: loading || session.isLoading
                                      ? null
                                      : login,
                                  child: loading || session.isLoading
                                      ? const SizedBox.square(
                                          dimension: 22,
                                          child: CircularProgressIndicator(
                                              strokeWidth: 2))
                                      : const Text('登录')),
                              const SizedBox(height: 18),
                              TextButton(
                                onPressed: loading || session.isLoading
                                    ? null
                                    : () async {
                                        final account = await context
                                            .push<String>('/register');
                                        if (!mounted || account == null) return;
                                        setState(() {
                                          username.text = account;
                                          password.clear();
                                          error = null;
                                        });
                                        if (context.mounted) {
                                          ScaffoldMessenger.of(context)
                                              .showSnackBar(const SnackBar(
                                                  content:
                                                      Text('注册成功，请使用新账号登录')));
                                        }
                                      },
                                child: const Text('注册账号'),
                              ),
                            ]))))));
  }
}

class OverviewScreen extends ConsumerWidget {
  const OverviewScreen({super.key});
  void messages(BuildContext context) {
    bool openingMessage = false;
    showModalBottomSheet(
        context: context,
        showDragHandle: true,
        builder: (_) => Consumer(
            builder: (c, r, _) => SingleChildScrollView(
                child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: RemoteView(
                        value: r.watch(notificationsProvider),
                        retry: () => r.invalidate(notificationsProvider),
                        builder:
                            (rows) => Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.stretch,
                                    children: [
                                      const SectionHeader('消息'),
                                      if (rows.isEmpty) _empty('暂无消息'),
                                      for (final n in rows)
                                        ListTile(
                                            leading: const ResearchIcon(
                                                Icons.notifications_outlined,
                                                color: ResearchDesign.blue),
                                            title: Text(const {
                                                  'task_due': '任务到期提醒',
                                                  'task_reminder': '任务提醒',
                                                  'stage_review': '阶段审阅'
                                                }[n['type']] ??
                                                _text(n['type'])),
                                            subtitle: Text(n['read_at'] == null
                                                ? '未读'
                                                : '已读'),
                                            onTap: () async {
                                              if (openingMessage) return;
                                              openingMessage = true;
                                              try {
                                                await perform(c, () async {
                                                  final saved = await r
                                                      .read(apiClientProvider)
                                                      .post(
                                                          '/notifications/${n['id']}/read');
                                                  r.invalidate(
                                                      notificationsProvider);
                                                  if (!c.mounted) return;
                                                  final target = saved['target']
                                                          is Map
                                                      ? saved['target'] as Map
                                                      : n['target'] is Map
                                                          ? n['target'] as Map
                                                          : const {};
                                                  await showDialog<void>(
                                                      context: c,
                                                      builder:
                                                          (dialog) =>
                                                              AlertDialog(
                                                                title: Text(_text(target[
                                                                            'title'])
                                                                        .isEmpty
                                                                    ? '消息详情'
                                                                    : _text(target[
                                                                        'title'])),
                                                                content: Column(
                                                                    mainAxisSize:
                                                                        MainAxisSize
                                                                            .min,
                                                                    crossAxisAlignment:
                                                                        CrossAxisAlignment
                                                                            .start,
                                                                    children: [
                                                                      Text(_text(target['message'])
                                                                              .isEmpty
                                                                          ? '请到对应的任务或阶段查看最新进展。'
                                                                          : _text(
                                                                              target['message'])),
                                                                      if (saved['closed_at'] !=
                                                                              null ||
                                                                          n['closed_at'] !=
                                                                              null)
                                                                        const Padding(
                                                                            padding:
                                                                                EdgeInsets.only(top: 12),
                                                                            child: Text('这条提醒已结束，请以最新任务状态为准。')),
                                                                    ]),
                                                                actions: [
                                                                  TextButton(
                                                                      onPressed: () =>
                                                                          Navigator.pop(
                                                                              dialog),
                                                                      child: const Text(
                                                                          '知道了'))
                                                                ],
                                                              ));
                                                });
                                              } finally {
                                                openingMessage = false;
                                              }
                                            }),
                                    ]))))));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(sessionProvider).valueOrNull;
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    final enabled = session?.collaborationEnabled == true;
    final dashboard = enabled
        ? ref.watch(dashboardProvider)
        : const AsyncData<Map<String, dynamic>>({});
    final papers = enabled
        ? ref.watch(papersProvider)
        : const AsyncData<List<Map<String, dynamic>>>([]);
    return RefreshIndicator(
        onRefresh: () async {
          await ref.read(sessionProvider.notifier).loadMe();
          ref.invalidate(dashboardProvider);
          ref.invalidate(papersProvider);
          if (mentor) {
            ref.invalidate(studentsProvider);
          } else {
            ref.invalidate(tasksProvider);
          }
          await ref.read(dashboardProvider.future);
        },
        child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: _pagePadding,
            children: [
              Row(children: [
                Expanded(
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                      Text(mentor ? '导师移动工作台' : '学生移动工作台',
                          style: const TextStyle(
                              color: ResearchDesign.purple,
                              fontSize: 12,
                              fontWeight: FontWeight.w600)),
                      const SizedBox(height: 4),
                      Text('你好，${session?.name ?? ''}',
                          style: const TextStyle(
                              fontSize: 21, fontWeight: FontWeight.w700)),
                    ])),
                IconButton(
                    tooltip: '消息',
                    onPressed: () => messages(context),
                    icon: const Icon(Icons.notifications_none, size: 23)),
                Container(
                    width: 38,
                    height: 38,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                        color: const Color(0xFFF0EDFF),
                        borderRadius: BorderRadius.circular(13)),
                    child: Text(session?.name.characters.firstOrNull ?? '研',
                        style: const TextStyle(
                            color: ResearchDesign.purple,
                            fontSize: 18,
                            fontWeight: FontWeight.w600))),
              ]),
              const SizedBox(height: 18),
              Container(
                  key: const ValueKey('overview-brand'),
                  clipBehavior: Clip.antiAlias,
                  decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(20),
                      gradient: const LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [
                            Color(0xFF191D3D),
                            Color(0xFF3D397A),
                            Color(0xFF7560E5)
                          ]),
                      boxShadow: [
                        BoxShadow(
                            color: ResearchDesign.purple.withValues(alpha: .12),
                            blurRadius: 16,
                            offset: const Offset(0, 6))
                      ]),
                  child: Stack(children: [
                    Positioned(
                        right: -30,
                        top: -35,
                        child: Container(
                            width: 180,
                            height: 180,
                            decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                border: Border.all(
                                    color: Colors.white.withValues(alpha: .13),
                                    width: 1)))),
                    Positioned(
                        right: -5,
                        top: -10,
                        child: Container(
                            width: 130,
                            height: 130,
                            decoration: BoxDecoration(
                                shape: BoxShape.circle,
                                border: Border.all(
                                    color: Colors.white.withValues(alpha: .12),
                                    width: 1)))),
                    Padding(
                        padding: const EdgeInsets.all(20),
                        child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              const Text('科研协同工作台',
                                  style: TextStyle(
                                      color: Color(0xFFBBAFFF),
                                      fontSize: 11,
                                      fontWeight: FontWeight.w600)),
                              const SizedBox(height: 6),
                              const Text('研迹',
                                  style: TextStyle(
                                      color: Colors.white,
                                      fontSize: 30,
                                      fontWeight: FontWeight.w700,
                                      letterSpacing: 2)),
                              const SizedBox(height: 6),
                              Text(
                                  mentor
                                      ? '把论文、周报、进度与文献连在一起。\n留出更多时间，陪学生理清下一步。'
                                      : '让每一步研究，有记录、有依据。\n把任务、材料与导师反馈连成科研路径。',
                                  style: const TextStyle(
                                      color: Color(0xFFEEEBFF),
                                      fontSize: 13,
                                      height: 1.6)),
                              const SizedBox(height: 14),
                              dashboard.when(
                                  data: (d) {
                                    final c = d['counters'] is Map
                                        ? d['counters'] as Map
                                        : const {};
                                    return Wrap(
                                        spacing: 18,
                                        runSpacing: 8,
                                        children: [
                                          _HeroMetric(
                                              mentor ? '绑定学生' : '任务总数',
                                              _counter(
                                                  c,
                                                  mentor
                                                      ? 'students_total'
                                                      : 'tasks_total')),
                                          _HeroMetric(
                                              mentor ? '任务总数' : '已完成',
                                              _counter(
                                                  c,
                                                  mentor
                                                      ? 'tasks_total'
                                                      : 'tasks_completed')),
                                          _HeroMetric('逾期任务',
                                              _counter(c, 'tasks_overdue')),
                                        ]);
                                  },
                                  loading: () => const Text('正在读取科研概况…',
                                      style: TextStyle(
                                          color: Colors.white70, fontSize: 12)),
                                  error: (e, _) => const Text('概况暂时无法加载，下拉重试',
                                      style: TextStyle(
                                          color: Colors.white70,
                                          fontSize: 12))),
                            ])),
                  ])),
              const SizedBox(height: 14),
              if (enabled) ...[
                const ResearchStudentSelector(),
                const ResearchPlanPanel(compact: true),
                const SizedBox(height: 14),
                Builder(builder: (context) {
                  final counters = dashboard.valueOrNull?['counters'] is Map
                      ? dashboard.valueOrNull!['counters'] as Map
                      : const {};
                  final archiveCount = counters['theses_total'];
                  final reportCount = counters['reports_submitted'] ??
                      counters['weekly_reports_submitted'];
                  final overdue = counters['tasks_overdue'];
                  final readyCount = counters['papers_ready'] is num
                      ? counters['papers_ready']
                      : papers.valueOrNull
                          ?.where((p) => p['ingest_status'] == 'ready')
                          .length;
                  return Column(children: [
                    Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                              child: OverviewEntry(
                                  key: const ValueKey('overview-theses'),
                                  title: '论文档案',
                                  value: archiveCount is num
                                      ? '$archiveCount 份档案'
                                      : '查看档案',
                                  detail: '稿件与修改意见',
                                  icon: Icons.article_outlined,
                                  color: ResearchDesign.purple,
                                  onTap: () => context.push('/theses'))),
                          const SizedBox(width: 10),
                          Expanded(
                              child: OverviewEntry(
                                  key: const ValueKey('overview-reports'),
                                  title: '周报记录',
                                  value: reportCount is num
                                      ? '$reportCount 份已提交'
                                      : dashboard.isLoading
                                          ? '加载中'
                                          : '查看周报',
                                  detail: '记录进展与困难',
                                  icon: Icons.description_outlined,
                                  color: ResearchDesign.blue,
                                  onTap: () =>
                                      context.go('/work?tab=reports'))),
                        ]),
                    Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                              child: OverviewEntry(
                                  key: const ValueKey('overview-progress'),
                                  title: '科研进度',
                                  value:
                                      overdue is num ? '$overdue 项逾期' : '查看阶段',
                                  detail: '阶段目标与任务',
                                  icon: Icons.flag_outlined,
                                  color: ResearchDesign.orange,
                                  onTap: () => context.push('/progress'))),
                          const SizedBox(width: 10),
                          Expanded(
                              child: OverviewEntry(
                                  key: const ValueKey('overview-library'),
                                  title: '资料文献',
                                  value: readyCount != null
                                      ? '$readyCount 篇已就绪'
                                      : papers.isLoading
                                          ? '加载中'
                                          : '查看资料',
                                  detail: '导读与阅读笔记',
                                  icon: Icons.menu_book_outlined,
                                  color: ResearchDesign.teal,
                                  onTap: () => context.go('/library'))),
                        ]),
                  ]);
                }),
                const SizedBox(height: 14),
                Row(children: [
                  Expanded(
                      child: Text(mentor ? '学生培养状态' : '当前科研任务',
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.w700))),
                  TextButton(
                      onPressed: () => context.go('/work'),
                      child: Text(mentor ? '查看工作' : '全部任务'))
                ]),
                if (mentor) const StudentList() else const TaskList(limit: 3),
                if (dashboard.valueOrNull != null)
                  for (final risk in readableRisks(dashboard.valueOrNull!))
                    Card(
                        child: ListTile(
                            leading: const ResearchIcon(Icons.schedule_outlined,
                                color: ResearchDesign.orange),
                            title: Text(risk['title'].toString()),
                            subtitle: const Text('已超过截止时间，请检查进展'),
                            trailing: const Icon(Icons.chevron_right),
                            onTap: risk['task_id'] is! String
                                ? null
                                : () => perform(context, () async {
                                      final task = await ref
                                          .read(apiClientProvider)
                                          .get('/tasks/${risk['task_id']}');
                                      ref
                                          .read(selectedTaskProvider.notifier)
                                          .state = ResearchTask.fromJson(task);
                                      if (context.mounted) {
                                        context.push('/task');
                                      }
                                    }))),
                const SizedBox(height: 12),
                if (mentor)
                  RemoteView(
                      value: ref.watch(studentsProvider),
                      retry: () => ref.invalidate(studentsProvider),
                      builder: (rows) => DropdownButtonFormField<String>(
                          key: ValueKey(ref.watch(selectedStudentProvider)),
                          initialValue: rows.any((s) =>
                                  s['id'] == ref.watch(selectedStudentProvider))
                              ? ref.watch(selectedStudentProvider)
                              : null,
                          isExpanded: true,
                          decoration:
                              const InputDecoration(labelText: '选择学生开始指导'),
                          items: rows
                              .map((s) => DropdownMenuItem(
                                  value: s['id'].toString(),
                                  child: Text(_text(s['display_name']))))
                              .toList(),
                          onChanged: (v) => ref
                              .read(selectedStudentProvider.notifier)
                              .state = v)),
                if (mentor)
                  const AiActionPanel(
                      title: 'AI 风险简报',
                      kind: 'risk_brief',
                      description: '汇总这位学生的任务、周报和材料，检查未来 7 天需要优先指导的问题。',
                      action: '生成风险简报'),
                if (!mentor)
                  const AiActionPanel(
                      title: 'AI 本周周报',
                      kind: 'weekly_report',
                      description: '从已确认的记录提炼事实，核对依据后创建草稿。',
                      action: '生成本周周报'),
                Wrap(spacing: 8, runSpacing: 8, children: [
                  TextButton.icon(
                      onPressed: () => context.push('/assistant'),
                      icon: const Icon(Icons.auto_awesome, size: 18),
                      label: const Text('科研下一步')),
                  TextButton.icon(
                      onPressed: () => context.push('/agent-runs'),
                      icon: const Icon(Icons.history, size: 18),
                      label: const Text('运行历史'))
                ]),
                if (mentor) const MentorApplicationsCard(),
              ] else ...[
                const SizedBox(height: 12),
                const MentorConnectionCard()
              ],
            ]));
  }

  String _counter(Map counters, String key) =>
      counters[key] is num ? counters[key].toString() : '待完善';
}

class _HeroMetric extends StatelessWidget {
  const _HeroMetric(this.label, this.value);
  final String label, value;
  @override
  Widget build(BuildContext context) =>
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(value,
            style: const TextStyle(
                color: Colors.white,
                fontSize: 18,
                fontWeight: FontWeight.w700)),
        Text(label,
            style: const TextStyle(color: Color(0xFFDBD5FC), fontSize: 11)),
      ]);
}

class OverviewEntry extends StatelessWidget {
  const OverviewEntry(
      {required this.title,
      required this.value,
      required this.detail,
      required this.icon,
      required this.color,
      required this.onTap,
      super.key});
  final String title, value, detail;
  final IconData icon;
  final Color color;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Card(
      child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: onTap,
          child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(children: [
                      Container(
                          width: 28,
                          height: 28,
                          decoration: BoxDecoration(
                              color: color.withValues(alpha: .12),
                              borderRadius: BorderRadius.circular(9)),
                          child: Icon(icon, color: color, size: 17)),
                      const SizedBox(width: 8),
                      Expanded(
                          child: Text(title,
                              style: const TextStyle(
                                  fontSize: 12, fontWeight: FontWeight.w600)))
                    ]),
                    const SizedBox(height: 10),
                    Text(value,
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w700)),
                    Text(detail,
                        style: const TextStyle(
                            fontSize: 11, color: ResearchDesign.muted)),
                  ]))));
}

class StudentList extends ConsumerWidget {
  const StudentList({super.key});
  @override
  Widget build(BuildContext context, WidgetRef ref) => RemoteView(
      value: ref.watch(studentsProvider),
      retry: () => ref.invalidate(studentsProvider),
      builder: (rows) => Column(children: [
            if (rows.isEmpty) _empty('暂无已绑定学生'),
            ...rows.map((s) => Card(
                child: ListTile(
                    title: Text(_text(s['display_name'])),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () {
                      ref.read(selectedStudentProvider.notifier).state =
                          _text(s['id']);
                      context.go('/work');
                    })))
          ]));
}

class TaskList extends ConsumerWidget {
  const TaskList({this.limit, super.key});
  final int? limit;
  @override
  Widget build(BuildContext context, WidgetRef ref) => RemoteView(
      value: ref.watch(tasksProvider),
      retry: () => ref.invalidate(tasksProvider),
      builder: (tasks) => Column(children: [
            if (tasks.isEmpty) _empty('暂无任务'),
            ...tasks.take(limit ?? tasks.length).map((t) => Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: TaskCard(
                    task: t,
                    onTap: () {
                      ref.read(selectedTaskProvider.notifier).state = t;
                      context.push('/task');
                    })))
          ]));
}

class WorkScreen extends ConsumerStatefulWidget {
  const WorkScreen({this.initialTab = 0, super.key});
  final int initialTab;
  @override
  ConsumerState<WorkScreen> createState() => _WorkScreenState();
}

class _WorkScreenState extends ConsumerState<WorkScreen> {
  int tab = 0;
  bool busy = false;
  @override
  void initState() {
    super.initState();
    tab = widget.initialTab;
  }

  @override
  void didUpdateWidget(covariant WorkScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.initialTab != widget.initialTab) tab = widget.initialTab;
  }

  @override
  Widget build(BuildContext context) {
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true) {
      return ListView(
          padding: _pagePadding, children: const [MentorConnectionCard()]);
    }
    return RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(tasksProvider);
          ref.invalidate(reportsProvider);
          ref.invalidate(taskProvider);
          ref.invalidate(reportProvider);
          if (mentor) ref.invalidate(studentsProvider);
          ref.invalidate(dashboardProvider);
          await Future.wait([
            ref.read(tasksProvider.future),
            ref.read(reportsProvider.future),
          ]);
        },
        child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: _pagePadding,
            children: [
              const SectionHeader('任务与周报'),
              const SizedBox(height: 18),
              if (mentor)
                RemoteView(
                    value: ref.watch(studentsProvider),
                    retry: () => ref.invalidate(studentsProvider),
                    builder: (rows) => DropdownButton<String>(
                        isExpanded: true,
                        value: rows.any((s) =>
                                s['id'] == ref.watch(selectedStudentProvider))
                            ? ref.watch(selectedStudentProvider)
                            : null,
                        hint: const Text('选择学生'),
                        items: rows
                            .map((s) => DropdownMenuItem(
                                value: _text(s['id']),
                                child: Text(_text(s['display_name']))))
                            .toList(),
                        onChanged: (id) => ref
                            .read(selectedStudentProvider.notifier)
                            .state = id)),
              SegmentedButton<int>(segments: const [
                ButtonSegment(value: 0, label: Text('任务')),
                ButtonSegment(value: 1, label: Text('周报'))
              ], selected: {
                tab
              }, onSelectionChanged: (s) => setState(() => tab = s.first)),
              const SizedBox(height: 18),
              if (tab == 0) ...[
                AiActionPanel(
                    title: mentor ? 'AI 调整安排' : 'AI 下一步',
                    kind: mentor ? 'risk_brief' : 'research_qa',
                    question: '对照我的任务和当前阶段目标，哪些任务需要优先完成？缺少什么材料？请说明依据。',
                    description: mentor
                        ? '分析未来 7 天的风险，调整建议由导师确认后生效。'
                        : '根据实际任务与记录梳理行动顺序，生成前不会自动排序。',
                    action: '分析任务安排'),
                const TaskList(),
                if (!mentor)
                  OutlinedButton.icon(
                      onPressed: busy
                          ? null
                          : () => perform(context, () async {
                                final title = await askText(context, '新建个人任务');
                                if (title == null) return;
                                setState(() => busy = true);
                                try {
                                  await ref
                                      .read(apiClientProvider)
                                      .write('/tasks', {
                                    'student_id': ref
                                        .read(sessionProvider)
                                        .valueOrNull!
                                        .id,
                                    'title': title,
                                    'type': 'other'
                                  });
                                  ref.invalidate(tasksProvider);
                                  ref.invalidate(dashboardProvider);
                                } finally {
                                  if (mounted) setState(() => busy = false);
                                }
                              }),
                      icon: const Icon(Icons.add),
                      label: const Text('新建个人任务'))
              ],
              if (tab == 1) ...[
                AiActionPanel(
                    title: mentor ? 'AI 周报风险分析' : 'AI 本周周报草稿',
                    kind: mentor ? 'risk_brief' : 'weekly_report',
                    description: mentor
                        ? '结合这位学生的任务和周报梳理需要指导的问题。'
                        : '从已确认的记录提炼事实，核对引用并确认建议后创建草稿。',
                    action: mentor ? '分析本周风险' : '生成周报草稿'),
                RemoteView(
                    value: ref.watch(reportsProvider),
                    retry: () => ref.invalidate(reportsProvider),
                    builder: (rows) => Column(children: [
                          if (rows.isEmpty) _empty('暂无周报'),
                          ...rows.map((r) => Card(
                              child: ListTile(
                                  title: Text('${r['week_start']} 周报'),
                                  subtitle: Text(const {
                                        'draft': '草稿',
                                        'submitted': '待导师审阅',
                                        'reviewed': '已阅',
                                        'changes_requested': '需要修改'
                                      }[r['status']] ??
                                      '待处理'),
                                  trailing: const Icon(Icons.chevron_right),
                                  onTap: () {
                                    ref
                                        .read(selectedReportProvider.notifier)
                                        .state = _text(r['id']);
                                    context.push('/report');
                                  })))
                        ])),
                if (!mentor)
                  OutlinedButton(
                      onPressed: busy
                          ? null
                          : () => perform(context, () async {
                                setState(() => busy = true);
                                try {
                                  final r = await ref
                                      .read(apiClientProvider)
                                      .write('/weekly-reports',
                                          {'week_start': currentWeek()});
                                  ref.invalidate(reportsProvider);
                                  ref
                                      .read(selectedReportProvider.notifier)
                                      .state = _text(r['id']);
                                  if (context.mounted) context.push('/report');
                                } finally {
                                  if (mounted) setState(() => busy = false);
                                }
                              }),
                      child: const Text('新建本周周报'))
              ],
            ]));
  }
}

class LibraryScreen extends ConsumerStatefulWidget {
  const LibraryScreen({super.key});
  @override
  ConsumerState<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends ConsumerState<LibraryScreen> {
  bool busy = false;
  String search = '';
  Future<void> upload() async {
    final memberships = ref.read(sessionProvider).valueOrNull!.memberships;
    if (memberships.isEmpty) throw const ApiFailure('需要先加入实验室才能导入文献');
    final selection = await FilePicker.pickFiles(
        type: FileType.custom, allowedExtensions: ['pdf', 'docx']);
    if (selection == null || !mounted) return;
    final file = selection.files.single;
    if (file.path == null) throw const ApiFailure('无法读取选中的文件');
    setState(() => busy = true);
    try {
      final api = ref.read(apiClientProvider);
      if (file.size > 20 * 1024 * 1024) {
        throw const ApiFailure('PDF/DOCX 文件不能超过 20 MiB');
      }
      final digest = await sha256.bind(File(file.path!).openRead()).first;
      final identity =
          '${api.dio.options.baseUrl}:${api.userScope}:${memberships.first['lab_id']}:$digest';
      final slot = 'upload_${Uri.encodeComponent(identity)}';
      const storage = SessionController.storage;
      final uploadId = await storage.read(key: slot) ?? const Uuid().v4();
      await storage.write(key: slot, value: uploadId);
      final uploaded = await api.request('POST', '/files',
          key: uploadId,
          body: FormData.fromMap({
            'file': await MultipartFile.fromFile(file.path!,
                filename: file.name,
                contentType: DioMediaType.parse(file.extension?.toLowerCase() ==
                        'pdf'
                    ? 'application/pdf'
                    : 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')),
            'purpose': 'paper',
            'lab_id': memberships.first['lab_id'],
            'client_upload_id': uploadId
          }));
      final fileId = uploaded['file_id'];
      try {
        await api.write('/papers',
            {'file_id': fileId, 'title': file.name, 'visibility': 'private'});
      } on ApiFailure catch (e) {
        if (e.code != 'PAPER_EXISTS') rethrow;
        final papers = await api.list('/papers');
        if (!papers.any((paper) => paper['file_id'] == fileId)) rethrow;
      }
      await storage.delete(key: slot);
      ref.invalidate(papersProvider);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true) {
      return ListView(
          padding: _pagePadding, children: const [MentorConnectionCard()]);
    }
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    final selected = ref.watch(selectedStudentProvider);
    final canViewNotes = !mentor ||
        (ref
                .watch(studentsProvider)
                .valueOrNull
                ?.any((s) => s['id'] == selected) ==
            true);
    return RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(papersProvider);
          await ref.read(papersProvider.future);
        },
        child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: _pagePadding,
            children: [
              Row(children: [
                Expanded(
                    child: Text('资料',
                        style: Theme.of(context).textTheme.headlineSmall)),
                FilledButton.icon(
                    onPressed: busy ||
                            ref
                                    .watch(sessionProvider)
                                    .valueOrNull
                                    ?.collaborationEnabled !=
                                true
                        ? null
                        : () => perform(context, upload),
                    icon: const Icon(Icons.upload_file),
                    label: Text(busy ? '上传中' : '导入'))
              ]),
              const SizedBox(height: 18),
              if (mentor) ...[
                const Text('先选择学生，再查看该学生的阅读笔记。',
                    style: TextStyle(color: ResearchDesign.muted)),
                const SizedBox(height: 8),
                RemoteView(
                    value: ref.watch(studentsProvider),
                    retry: () => ref.invalidate(studentsProvider),
                    builder: (rows) => rows.isEmpty
                        ? const ResearchEmpty('暂无已绑定学生，请先完成师生绑定。')
                        : DropdownButtonFormField<String>(
                            key: ValueKey(selected),
                            initialValue: rows.any((s) => s['id'] == selected)
                                ? selected
                                : null,
                            isExpanded: true,
                            decoration:
                                const InputDecoration(labelText: '选择学生'),
                            items: rows
                                .map((s) => DropdownMenuItem(
                                    value: s['id'].toString(),
                                    child: Text(_text(s['display_name']))))
                                .toList(),
                            onChanged: (id) => ref
                                .read(selectedStudentProvider.notifier)
                                .state = id)),
                const SizedBox(height: 16),
              ],
              TextField(
                  onChanged: (v) => setState(() => search = v),
                  decoration: const InputDecoration(
                      hintText: '搜索文献', prefixIcon: Icon(Icons.search))),
              Card(
                  child: ListTile(
                      leading: const ResearchIcon(Icons.article_outlined,
                          color: ResearchDesign.blue),
                      title: const Text('论文档案'),
                      subtitle: const Text('发布修订稿、查看导师反馈和历史版本'),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => context.push('/theses'))),
              const SizedBox(height: 18),
              RemoteView(
                  value: ref.watch(papersProvider),
                  retry: () => ref.invalidate(papersProvider),
                  builder: (rows) {
                    final filtered = rows
                        .where((p) => _text(p['title'])
                            .toLowerCase()
                            .contains(search.toLowerCase()))
                        .toList();
                    return Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (filtered.isEmpty) _empty('暂无匹配的资料'),
                          ...filtered.map((p) => Card(
                              child: Padding(
                                  padding: const EdgeInsets.all(18),
                                  child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Row(
                                            crossAxisAlignment:
                                                CrossAxisAlignment.start,
                                            children: [
                                              const ResearchIcon(
                                                  Icons.menu_book_outlined,
                                                  color: ResearchDesign.teal),
                                              const SizedBox(width: 10),
                                              Expanded(
                                                  child: Column(
                                                      crossAxisAlignment:
                                                          CrossAxisAlignment
                                                              .start,
                                                      children: [
                                                    Text(_text(p['title']),
                                                        style: const TextStyle(
                                                            fontWeight:
                                                                FontWeight.w600,
                                                            height: 1.4)),
                                                    const SizedBox(height: 5),
                                                    Text(
                                                        ingestLabel(
                                                            p['ingest_status']),
                                                        style: TextStyle(
                                                            fontSize: 12,
                                                            color: p['ingest_status'] ==
                                                                    'ready'
                                                                ? ResearchDesign
                                                                    .teal
                                                                : p['ingest_status'] ==
                                                                        'failed'
                                                                    ? ResearchDesign
                                                                        .red
                                                                    : ResearchDesign
                                                                        .orange)),
                                                  ])),
                                            ]),
                                        const SizedBox(height: 10),
                                        Row(children: [
                                          Expanded(
                                              child: OutlinedButton.icon(
                                                  onPressed: canViewNotes
                                                      ? () => context.push(
                                                          '/papers/${Uri.encodeComponent(p['id'].toString())}')
                                                      : null,
                                                  icon: const Icon(
                                                      Icons.edit_note,
                                                      size: 18),
                                                  label: const Text('查看笔记'))),
                                          ...[
                                            const SizedBox(width: 8),
                                            Expanded(
                                                child: OutlinedButton.icon(
                                                    onPressed: p['ingest_status'] !=
                                                                'ready' ||
                                                            !canViewNotes ||
                                                            ref
                                                                    .watch(
                                                                        sessionProvider)
                                                                    .valueOrNull
                                                                    ?.collaborationEnabled !=
                                                                true
                                                        ? null
                                                        : () {
                                                            ref
                                                                .read(agentInputProvider
                                                                    .notifier)
                                                                .state = {
                                                              'kind':
                                                                  'paper_guide',
                                                              'subject': {
                                                                'student_id': mentor
                                                                    ? selected
                                                                    : ref
                                                                        .read(
                                                                            sessionProvider)
                                                                        .valueOrNull!
                                                                        .id,
                                                                'paper_id':
                                                                    p['id']
                                                              },
                                                              'input': {}
                                                            };
                                                            context
                                                                .push('/agent');
                                                          },
                                                    icon: const Icon(
                                                        Icons.auto_awesome),
                                                    label:
                                                        const Text('AI 导读'))),
                                          ],
                                        ])
                                      ]))))
                        ]);
                  }),
            ]));
  }
}

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});
  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  bool busy = false;
  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider).valueOrNull;
    return ListView(padding: _pagePadding, children: [
      Text('我的', style: Theme.of(context).textTheme.headlineSmall),
      const SizedBox(height: 18),
      Card(
          child: ListTile(
              leading: const Icon(Icons.person),
              title: Text(session?.name ?? ''),
              subtitle: Text(session?.hasBusinessRole == true
                  ? session!.roles
                      .map((r) => r == 'mentor' ? '导师' : '学生')
                      .join('、')
                  : '尚未加入实验室'))),
      if (session != null && ref.watch(roleProvider) == UserRole.student)
        const MentorConnectionCard(),
      if (session != null && ref.watch(roleProvider) == UserRole.mentor)
        const MentorApplicationsCard(),
      if (session != null && ref.watch(roleProvider) == UserRole.mentor)
        Card(
            child: ListTile(
                leading: const Icon(Icons.school_outlined),
                title: const Text('科研指导'),
                subtitle: const Text('整理指导经验和阶段要求'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => context.push('/guidance'))),
      if (session != null &&
          session.roles.contains('student') &&
          session.roles.contains('mentor'))
        SwitchListTile(
            value: ref.watch(roleProvider) == UserRole.mentor,
            onChanged: (v) {
              ref.read(selectedStudentProvider.notifier).state = null;
              ref.read(roleProvider.notifier).state =
                  v ? UserRole.mentor : UserRole.student;
            },
            title: const Text('导师视图')),
      const SizedBox(height: 18),
      Card(
          child: ListTile(
              leading: const ResearchIcon(Icons.article_outlined,
                  color: ResearchDesign.blue),
              title: const Text('论文档案'),
              subtitle: const Text('上传稿件与查看修改意见'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => context.push('/theses'))),
      OutlinedButton(
          onPressed: busy
              ? null
              : () => perform(context, () async {
                    setState(() => busy = true);
                    try {
                      await ref.read(sessionProvider.notifier).logout();
                      if (context.mounted) context.go('/login');
                    } finally {
                      if (mounted) setState(() => busy = false);
                    }
                  }),
          child: const Text('退出账号')),
    ]);
  }
}

class TaskDetailScreen extends ConsumerStatefulWidget {
  const TaskDetailScreen({this.updateMaterials = false, super.key});
  final bool updateMaterials;
  @override
  ConsumerState<TaskDetailScreen> createState() => _TaskDetailScreenState();
}

class _TaskDetailScreenState extends ConsumerState<TaskDetailScreen> {
  bool busy = false;
  Future<void> transition(ResearchTask task, String status,
      {String? comment, List<Map<String, dynamic>>? evidenceRefs}) async {
    setState(() => busy = true);
    try {
      await ref.read(apiClientProvider).write('/tasks/${task.id}/transition', {
        'expected_version': task.version,
        'to_status': status,
        if (comment != null) 'comment': comment,
        if (evidenceRefs != null) 'evidence_refs': evidenceRefs,
      });
      ref.invalidate(taskProvider(task.id));
      ref.invalidate(taskEventsProvider(task.id));
      ref.invalidate(tasksProvider);
      ref.invalidate(dashboardProvider);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> explainTransition(BuildContext context, ResearchTask task,
      String status, String title) async {
    final comment = await askText(context, title, lines: 4);
    if (comment == null || !context.mounted) return;
    await perform(context, () => transition(task, status, comment: comment));
  }

  Future<void> submitForReview(ResearchTask task) async {
    if (busy) return;
    if (task.stageId == null) return transition(task, 'submitted');
    setState(() => busy = true);
    try {
      final api = ref.read(apiClientProvider);
      final records =
          await api.list('/experiments', query: {'task_id': task.id});
      final refs = <Map<String, dynamic>>[];
      final titles = <String>[];
      for (final record in records) {
        if (record['status'] != 'confirmed' ||
            record['visibility'] == 'private') {
          continue;
        }
        final detail = await api.get('/experiments/${record['id']}');
        final results = detail['results']?.toString().trim() ?? '';
        if (detail['task_id'] != task.id ||
            detail['status'] != 'confirmed' ||
            detail['visibility'] == 'private' ||
            detail['source_id'] == null ||
            detail['source_revision'] == null ||
            results.isEmpty) {
          continue;
        }
        refs.add({
          'source_id': detail['source_id'],
          'source_revision': detail['source_revision'],
          'chunk_id': null,
          'locator': {'kind': 'record_field', 'field': 'results'},
          'quote': String.fromCharCodes(results.runes.take(240)),
        });
        titles.add(detail['purpose']?.toString() ?? '已确认实验记录');
      }
      if (!mounted) return;
      if (refs.isEmpty && task.type == '实验') {
        throw const ApiFailure('请先保存并确认这项任务的实验记录，选择向导师共享后再提交验收。');
      }
      if (refs.isNotEmpty) {
        final accepted = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
                  title: const Text('提交任务成果'),
                  content: SingleChildScrollView(
                      child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                        const Text('这次会把下面的已确认记录提交给导师，作为验收依据。'),
                        const SizedBox(height: 12),
                        for (final title in titles)
                          Padding(
                              padding: const EdgeInsets.only(bottom: 8),
                              child: Text(title)),
                      ])),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(context, false),
                        child: const Text('再看看')),
                    FilledButton(
                        onPressed: () => Navigator.pop(context, true),
                        child: const Text('确认提交'))
                  ],
                ));
        if (accepted != true || !mounted) return;
      }
      await transition(task, 'submitted', evidenceRefs: refs);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final id = ref.watch(selectedTaskProvider)?.id;
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    return Scaffold(
        appBar: AppBar(title: const Text('任务详情')),
        body: id == null
            ? _empty('请先选择任务')
            : SingleChildScrollView(
                padding: _pagePadding,
                child: RemoteView(
                    value: ref.watch(taskProvider(id)),
                    retry: () => ref.invalidate(taskProvider(id)),
                    builder: (task) => Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              ResearchPanel(
                                  title: task.title,
                                  icon: task.type == '实验'
                                      ? Icons.science_outlined
                                      : Icons.task_alt,
                                  color: task.type == '实验'
                                      ? ResearchDesign.blue
                                      : ResearchDesign.purple,
                                  child: Wrap(
                                      spacing: 10,
                                      runSpacing: 8,
                                      crossAxisAlignment:
                                          WrapCrossAlignment.center,
                                      children: [
                                        StatusPill(task.status),
                                        Text('${task.type} · ${task.dueLabel}')
                                      ])),
                              ResearchPanel(
                                  title: '任务目标',
                                  icon: Icons.flag_outlined,
                                  color: ResearchDesign.blue,
                                  child: Text(task.description.isEmpty
                                      ? '暂未填写目标，可向导师确认要求。'
                                      : task.description)),
                              AiActionPanel(
                                  title: mentor ? 'AI 调整安排' : 'AI 下一步行动',
                                  kind: mentor ? 'risk_brief' : 'research_qa',
                                  stageId: task.stageId,
                                  taskId: task.id,
                                  question:
                                      '当前任务：${task.title}。目标：${task.description}。请对照已保存材料和阶段要求，说明缺少的证据和建议完成顺序。',
                                  description: mentor
                                      ? '分析未来 7 天的任务风险，调整建议需人工确认。'
                                      : '根据这项任务与当前阶段梳理下一步，保留可核对依据。',
                                  action: '分析这项任务'),
                              if (mentor && task.stageId != null)
                                AiActionPanel(
                                    title: 'AI 阶段任务建议',
                                    kind: 'stage_plan',
                                    stageId: task.stageId,
                                    question: '请根据阶段目标与现有材料，安排可执行的下一步任务。',
                                    action: '生成阶段任务建议'),
                              ResearchPanel(
                                  title: '验收标准',
                                  icon: Icons.checklist,
                                  color: ResearchDesign.teal,
                                  child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        if (task.criteria.isEmpty)
                                          const Text('暂未填写验收标准，请向导师确认。'),
                                        ...task.criteria.map((t) => ListTile(
                                            contentPadding: EdgeInsets.zero,
                                            leading: const Icon(
                                                Icons.check_circle_outline,
                                                color: ResearchDesign.teal),
                                            title: Text(t))),
                                      ])),
                              if (task.blockingReason != null)
                                ResearchPanel(
                                  title: '先完成这些前置任务',
                                  icon: Icons.account_tree_outlined,
                                  color: ResearchDesign.orange,
                                  child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        Text(task.blockingReason!),
                                        for (final dependencyId
                                            in task.dependencyIds)
                                          TextButton.icon(
                                              onPressed: () => context
                                                  .push('/tasks/$dependencyId'),
                                              icon: const Icon(
                                                  Icons.arrow_forward),
                                              label: const Text('打开前置任务')),
                                      ]),
                                ),
                              RemoteView(
                                value: ref.watch(taskEventsProvider(id)),
                                retry: () =>
                                    ref.invalidate(taskEventsProvider(id)),
                                builder: (events) {
                                  final comments = events
                                      .where((e) =>
                                          (e['comment']?.toString().trim() ??
                                                  '')
                                              .isNotEmpty)
                                      .toList();
                                  if (comments.isEmpty) {
                                    return const SizedBox.shrink();
                                  }
                                  return ResearchPanel(
                                    title: '任务沟通记录',
                                    icon: Icons.forum_outlined,
                                    color: ResearchDesign.teal,
                                    child: Column(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.stretch,
                                        children: [
                                          for (final event in comments)
                                            Padding(
                                              padding: const EdgeInsets.only(
                                                  bottom: 12),
                                              child: Column(
                                                  crossAxisAlignment:
                                                      CrossAxisAlignment.start,
                                                  children: [
                                                    Text(
                                                        event['from_status'] ==
                                                                event[
                                                                    'to_status']
                                                            ? '调整安排'
                                                            : switch (event[
                                                                'to_status']) {
                                                                'blocked' =>
                                                                  '遇到困难',
                                                                'in_progress' =>
                                                                  event['from_status'] ==
                                                                          'submitted'
                                                                      ? '导师退回补充'
                                                                      : '开始或恢复任务',
                                                                'completed' =>
                                                                  '任务完成',
                                                                'submitted' =>
                                                                  '提交验收',
                                                                _ => '任务更新',
                                                              },
                                                        style: const TextStyle(
                                                            fontWeight:
                                                                FontWeight.w600,
                                                            color:
                                                                ResearchDesign
                                                                    .teal)),
                                                    const SizedBox(height: 4),
                                                    Text(event['comment']
                                                        .toString()),
                                                  ]),
                                            ),
                                        ]),
                                  );
                                },
                              ),
                              if (!mentor &&
                                  [TaskStatus.todo, TaskStatus.blocked]
                                      .contains(task.status))
                                FilledButton(
                                    onPressed: busy
                                        ? null
                                        : () => perform(
                                            context,
                                            () => transition(
                                                task, 'in_progress')),
                                    child: Text(
                                        task.status == TaskStatus.blocked
                                            ? '恢复任务'
                                            : '开始任务')),
                              if (!mentor &&
                                  task.status == TaskStatus.inProgress)
                                FilledButton(
                                    onPressed: busy
                                        ? null
                                        : () => perform(context,
                                            () => submitForReview(task)),
                                    child: const Text('提交验收')),
                              if (!mentor &&
                                  [TaskStatus.todo, TaskStatus.inProgress]
                                      .contains(task.status))
                                OutlinedButton.icon(
                                    onPressed: busy
                                        ? null
                                        : () => explainTransition(context, task,
                                            'blocked', '遇到了什么困难？'),
                                    icon: const Icon(Icons.help_outline),
                                    label: const Text('遇到困难')),
                              if (!mentor &&
                                  task.stageId == null &&
                                  task.createdBy != null &&
                                  task.createdBy ==
                                      ref
                                          .watch(sessionProvider)
                                          .valueOrNull
                                          ?.id &&
                                  [
                                    TaskStatus.inProgress,
                                    TaskStatus.blocked,
                                    TaskStatus.submitted
                                  ].contains(task.status))
                                FilledButton(
                                  onPressed: busy
                                      ? null
                                      : () => perform(context,
                                          () => transition(task, 'completed')),
                                  child: const Text('完成个人任务'),
                                ),
                              if (mentor && task.status == TaskStatus.submitted)
                                FilledButton(
                                    onPressed: busy
                                        ? null
                                        : () => perform(
                                            context,
                                            () =>
                                                transition(task, 'completed')),
                                    child: const Text('验收通过')),
                              if (mentor && task.status == TaskStatus.submitted)
                                OutlinedButton.icon(
                                    onPressed: busy
                                        ? null
                                        : () => explainTransition(context, task,
                                            'in_progress', '请说明需要补充的内容'),
                                    icon: const Icon(Icons.edit_note),
                                    label: const Text('退回补充')),
                              if (task.status != TaskStatus.unsupported)
                                OutlinedButton.icon(
                                    onPressed: busy
                                        ? null
                                        : () async {
                                            final saved = await Navigator.of(
                                                    context)
                                                .push<bool>(MaterialPageRoute(
                                                    builder: (_) =>
                                                        ExperimentEditorScreen(
                                                            taskId: task.id,
                                                            stageId:
                                                                task.stageId,
                                                            returnOnSave: widget
                                                                .updateMaterials,
                                                            studentId: ref.read(
                                                                selectedStudentProvider))));
                                            if (saved == true &&
                                                context.mounted &&
                                                widget.updateMaterials) {
                                              Navigator.of(context).pop(true);
                                            }
                                          },
                                    icon: const Icon(Icons.science_outlined),
                                    label: Text(mentor
                                        ? '查看实验记录'
                                        : widget.updateMaterials
                                            ? '修改或添加材料'
                                            : '记录实验')),
                            ]))));
  }
}

class WeeklyReportScreen extends ConsumerStatefulWidget {
  const WeeklyReportScreen({super.key});
  @override
  ConsumerState<WeeklyReportScreen> createState() => _WeeklyReportScreenState();
}

class _WeeklyReportScreenState extends ConsumerState<WeeklyReportScreen> {
  bool busy = false;
  Future<void> write(String id, Map<String, dynamic> body, String action,
      {String method = 'POST'}) async {
    setState(() => busy = true);
    try {
      await ref
          .read(apiClientProvider)
          .write('/weekly-reports/$id$action', body, method: method);
      ref.invalidate(reportProvider(id));
      ref.invalidate(reportsProvider);
      ref.invalidate(dashboardProvider);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final id = ref.watch(selectedReportProvider);
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    const sections = {
      'done_items': '本周完成工作',
      'issues': '遇到的问题',
      'next_plan': '下周计划',
      'help_needed': '需要导师帮助'
    };
    const icons = {
      'done_items': Icons.task_alt,
      'issues': Icons.help_outline,
      'next_plan': Icons.edit_calendar_outlined,
      'help_needed': Icons.chat_bubble_outline
    };
    const colors = {
      'done_items': ResearchDesign.blue,
      'issues': ResearchDesign.orange,
      'next_plan': ResearchDesign.teal,
      'help_needed': ResearchDesign.purple
    };
    return Scaffold(
        appBar: AppBar(title: const Text('周报')),
        body: id == null
            ? _empty('请先选择周报')
            : SingleChildScrollView(
                padding: _pagePadding,
                child: RemoteView(
                    value: ref.watch(reportProvider(id)),
                    retry: () => ref.invalidate(reportProvider(id)),
                    builder: (r) {
                      final editable = !mentor &&
                          ['draft', 'changes_requested'].contains(r['status']);
                      return Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            ResearchPanel(
                                title: '${r['week_start']} 周报',
                                icon: Icons.description_outlined,
                                color: ResearchDesign.blue,
                                child: Text('状态：${const {
                                      'draft': '草稿',
                                      'submitted': '待导师审阅',
                                      'reviewed': '已阅',
                                      'changes_requested': '需要修改'
                                    }[r['status']] ?? '待处理'}')),
                            AiActionPanel(
                                title: mentor ? 'AI 反馈建议' : 'AI 周报草稿',
                                kind: mentor ? 'risk_brief' : 'weekly_report',
                                reportId: id,
                                enabled: !mentor ||
                                    ['submitted', 'changes_requested']
                                        .contains(r['status']),
                                description: mentor
                                    ? '结合学生的真实任务和周报分析风险，具体反馈由你确认发布。'
                                    : '基于已确认的实验、笔记和任务提炼事实。请检查引用后，在分析详情中确认草稿建议。',
                                action: mentor ? '分析指导重点' : '生成本周草稿'),
                            for (final section in sections.entries)
                              ResearchPanel(
                                  title: section.value,
                                  icon: icons[section.key]!,
                                  color: colors[section.key]!,
                                  child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        if (objectRows(r[section.key]).isEmpty)
                                          Text(
                                              editable
                                                  ? '还没有填写，点击下方添加内容。'
                                                  : '暂无内容',
                                              style: const TextStyle(
                                                  color: ResearchDesign.muted)),
                                        for (final item
                                            in objectRows(r[section.key])) ...[
                                          Row(
                                            crossAxisAlignment:
                                                CrossAxisAlignment.start,
                                            children: [
                                              Expanded(
                                                  child: Padding(
                                                      padding: const EdgeInsets
                                                          .symmetric(
                                                          vertical: 8),
                                                      child: Text(_text(
                                                          item['text'])))),
                                              if (editable)
                                                IconButton(
                                                    tooltip: '修改条目',
                                                    icon: const Icon(
                                                        Icons.edit_outlined,
                                                        size: 20),
                                                    onPressed: busy
                                                        ? null
                                                        : () => perform(context,
                                                                () async {
                                                              final text = await askText(
                                                                  context,
                                                                  '修改${section.value}',
                                                                  initial: _text(
                                                                      item[
                                                                          'text']),
                                                                  lines: 3);
                                                              if (text !=
                                                                      null &&
                                                                  ref.read(
                                                                          selectedReportProvider) ==
                                                                      id) {
                                                                await write(
                                                                    id,
                                                                    reportItemPatch(
                                                                        r,
                                                                        section
                                                                            .key,
                                                                        text,
                                                                        itemId: _text(item[
                                                                            'id'])),
                                                                    '',
                                                                    method:
                                                                        'PATCH');
                                                              }
                                                            }))
                                            ],
                                          ),
                                          SourceReferences(item['source_refs']),
                                        ],
                                        if (editable)
                                          Align(
                                              alignment: Alignment.centerLeft,
                                              child: TextButton.icon(
                                                  icon: const Icon(Icons.add,
                                                      size: 18),
                                                  label: const Text('添加条目'),
                                                  onPressed: busy
                                                      ? null
                                                      : () => perform(context,
                                                              () async {
                                                            final text =
                                                                await askText(
                                                                    context,
                                                                    '添加${section.value}',
                                                                    lines: 3);
                                                            if (text != null &&
                                                                ref.read(
                                                                        selectedReportProvider) ==
                                                                    id) {
                                                              await write(
                                                                  id,
                                                                  reportItemPatch(
                                                                      r,
                                                                      section
                                                                          .key,
                                                                      text),
                                                                  '',
                                                                  method:
                                                                      'PATCH');
                                                            }
                                                          }))),
                                      ])),
                            if (!mentor && r['status'] == 'changes_requested')
                              const ResearchEmpty('周报已退回，请先修改并保存条目，再提交。',
                                  icon: Icons.edit_note),
                            if (!mentor && r['status'] == 'draft')
                              FilledButton(
                                  onPressed: busy
                                      ? null
                                      : () => perform(
                                          context,
                                          () => write(
                                              id,
                                              {
                                                'expected_version': r['version']
                                              },
                                              '/submit')),
                                  child: const Text('提交周报')),
                            if (objectRows(r['reviews']).isNotEmpty)
                              ResearchPanel(
                                  title: '导师意见',
                                  icon: Icons.chat_bubble_outline,
                                  color: ResearchDesign.teal,
                                  child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        for (final review
                                            in objectRows(r['reviews']))
                                          ListTile(
                                              contentPadding: EdgeInsets.zero,
                                              title:
                                                  Text(_text(review['body'])),
                                              subtitle: Text(const {
                                                    'reviewed': '导师已阅',
                                                    'changes_requested': '请补充修改'
                                                  }[review['decision']] ??
                                                  '导师反馈'))
                                      ])),
                            if (mentor && r['status'] == 'submitted')
                              ResearchPanel(
                                  title: '审阅这份周报',
                                  icon: Icons.fact_check_outlined,
                                  color: ResearchDesign.orange,
                                  child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        for (final decision in const {
                                          'reviewed': '发布反馈',
                                          'changes_requested': '要求修改'
                                        }.entries)
                                          OutlinedButton(
                                              onPressed: busy
                                                  ? null
                                                  : () => perform(context,
                                                          () async {
                                                        final body = await askText(
                                                            context,
                                                            decision.value,
                                                            initial: r['review_draft']
                                                                    is Map
                                                                ? (r['review_draft']
                                                                            [
                                                                            'body']
                                                                        ?.toString() ??
                                                                    '')
                                                                : '',
                                                            lines: 4);
                                                        if (body != null &&
                                                            ref.read(
                                                                    selectedReportProvider) ==
                                                                id) {
                                                          await write(
                                                              id,
                                                              {
                                                                'expected_version':
                                                                    r['version'],
                                                                'report_revision':
                                                                    r['revision'],
                                                                'decision':
                                                                    decision
                                                                        .key,
                                                                'body': body
                                                              },
                                                              '/reviews');
                                                        }
                                                      }),
                                              child: Text(decision.value))
                                      ])),
                            if (busy) const LinearProgressIndicator(),
                          ]);
                    })));
  }
}

class AgentRunScreen extends ConsumerStatefulWidget {
  const AgentRunScreen({this.runId, super.key});
  final String? runId;
  @override
  ConsumerState<AgentRunScreen> createState() => _AgentRunScreenState();
}

class _AgentRunScreenState extends ConsumerState<AgentRunScreen>
    with WidgetsBindingObserver {
  Map<String, dynamic>? run;
  List<Map<String, dynamic>> events = [], proposals = [];
  Map<String, String> proposalTaskTitles = {};
  String? error;
  bool busy = false;
  Timer? timer;
  int pollEpoch = 0, accessEpoch = 0;
  Map<String, dynamic>? input;
  String? targetRunId;
  bool foreground = true, accessLost = false;
  bool materialsUpdated = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    input = ref.read(agentInputProvider);
    targetRunId = widget.runId;
    if (targetRunId != null) run = {'id': targetRunId};
    Future.microtask(start);
  }

  @override
  void dispose() {
    pollEpoch++;
    accessEpoch++;
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    foreground = state == AppLifecycleState.resumed;
    timer?.cancel();
    pollEpoch++;
    if (foreground && targetRunId != null && !accessLost) poll();
  }

  void clearAccess() {
    materialsUpdated = false;
    timer?.cancel();
    pollEpoch++;
    accessEpoch++;
    setState(() {
      run = null;
      events = [];
      proposals = [];
      proposalTaskTitles = {};
      input = null;
      targetRunId = null;
      busy = false;
      accessLost = true;
      error = '登录或访问范围已改变，请重新打开运行历史';
    });
  }

  Future<void> start() async {
    if (busy || accessLost) return;
    final epoch = accessEpoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      if (targetRunId == null) {
        if (input == null) throw const ApiFailure('请从科研下一步、周报或资料页发起运行');
        final created =
            await ref.read(apiClientProvider).write('/agent-runs', input!);
        if (!mounted || epoch != accessEpoch) return;
        if (created['run_id'] is! String) {
          throw const ApiFailure('运行响应缺少 run_id');
        }
        targetRunId = created['run_id'];
        run = {'id': targetRunId, 'status': created['status']};
      }
      await poll();
    } catch (e) {
      if (mounted && epoch == accessEpoch) setState(() => error = e.toString());
    } finally {
      if (mounted && epoch == accessEpoch) setState(() => busy = false);
    }
  }

  Future<void> poll() async {
    if (targetRunId == null || !mounted || !foreground || accessLost) return;
    timer?.cancel();
    final epoch = pollEpoch;
    final id = targetRunId!;
    final api = ref.read(apiClientProvider);
    try {
      final latest = await api.get('/agent-runs/$id');
      if (!mounted || epoch != pollEpoch || id != targetRunId) return;
      await verifyReadableRunSources(api, latest['result']);
      if (!mounted || epoch != pollEpoch || id != targetRunId) return;
      final progress = await api.runEvents(id);
      if (!mounted || epoch != pollEpoch || id != targetRunId) return;
      final rows = await api.list('/proposals', query: {'run_id': id});
      if (!mounted || epoch != pollEpoch || id != targetRunId) return;
      final suggestions = <Map<String, dynamic>>[];
      for (final row in rows) {
        if (row['status'] == 'pending' || row['status'] == 'approved') {
          final detail = await api.get('/proposals/${row['id']}');
          if (!mounted || epoch != pollEpoch || id != targetRunId) return;
          suggestions.add({
            ...row,
            if (detail['proposal'] is Map)
              ...Map<String, dynamic>.from(detail['proposal']),
            'can_approve': detail['can_approve'] == true
          });
        } else {
          suggestions.add(row);
        }
      }
      if (!mounted || epoch != pollEpoch || id != targetRunId) return;
      await verifyReadableRunSources(api, suggestions);
      if (!mounted || epoch != pollEpoch || id != targetRunId) return;
      final titles = <String, String>{};
      void collectTaskIds(dynamic value) {
        if (value is Map) {
          if (value['task_id'] is String) titles[value['task_id']] = '';
          for (final nested in value.values) {
            collectTaskIds(nested);
          }
        } else if (value is List) {
          for (final nested in value) {
            collectTaskIds(nested);
          }
        }
      }

      for (final suggestion in suggestions) {
        collectTaskIds(suggestion['payload']);
      }
      for (final taskId in titles.keys.toList()) {
        final task = await api.get('/tasks/$taskId');
        if (!mounted || epoch != pollEpoch || id != targetRunId) return;
        final title = task['title'];
        if (title is! String || title.trim().isEmpty) {
          throw const ApiFailure('相关任务暂时无法读取，请重新加载后再确认建议。');
        }
        titles[taskId] = title;
      }
      setState(() {
        run = latest;
        events = progress;
        proposals = suggestions;
        proposalTaskTitles = titles;
        error = null;
      });
      if (['queued', 'running'].contains(latest['status'])) {
        timer = Timer(const Duration(seconds: 2), poll);
      }
    } catch (e) {
      if (mounted && epoch == pollEpoch && id == targetRunId) {
        setState(() {
          if (isAccessFailure(e)) {
            run = null;
            events = [];
            proposals = [];
          }
          error = e is ApiFailure && e.code == 'RUN_SOURCE_FORBIDDEN'
              ? e.message
              : e is ApiFailure && [403, 404].contains(e.status)
                  ? '运行不存在或无权访问，请返回运行历史'
                  : e.toString();
        });
      }
    }
  }

  Future<void> cancelRun() async {
    if (busy || run == null) return;
    timer?.cancel();
    pollEpoch++;
    final epoch = accessEpoch;
    setState(() => busy = true);
    try {
      final result = await ref.read(apiClientProvider).post(
          '/agent-runs/${run!['id']}/cancel',
          {'expected_version': run!['version']});
      if (mounted && epoch == accessEpoch) {
        setState(() {
          run = result;
          error = null;
        });
      }
    } finally {
      if (mounted && epoch == accessEpoch) {
        setState(() => busy = false);
        if (['queued', 'running'].contains(run?['status'])) await poll();
      }
    }
  }

  Future<void> retryRun() async {
    if (busy || !['failed', 'cancelled'].contains(run?['status'])) return;
    timer?.cancel();
    pollEpoch++;
    final epoch = accessEpoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final created = await ref
          .read(apiClientProvider)
          .write('/agent-runs/${run!['id']}/retry', {});
      if (created['run_id'] is! String) throw const ApiFailure('运行响应缺少 run_id');
      if (!mounted || epoch != accessEpoch) return;
      setState(() {
        targetRunId = created['run_id'];
        run = {'id': targetRunId, 'status': created['status']};
        events = [];
        proposals = [];
      });
      await poll();
    } finally {
      if (mounted && epoch == accessEpoch) setState(() => busy = false);
    }
  }

  Future<void> updateMaterials() async {
    if (busy || run == null) return;
    final epoch = accessEpoch;
    final subject = Map<String, dynamic>.from(run!['subject'] as Map);
    setState(() => busy = true);
    try {
      final api = ref.read(apiClientProvider);
      final taskId = subject['task_id'];
      final List<ResearchTask> tasks;
      if (taskId is String && taskId.isNotEmpty) {
        tasks = [ResearchTask.fromJson(await api.get('/tasks/$taskId'))];
      } else {
        tasks = (await api.list('/tasks', query: {
          'student_id': subject['student_id'],
          if (subject['stage_id'] != null) 'stage_id': subject['stage_id'],
        }))
            .map(ResearchTask.fromJson)
            .toList();
      }
      if (!mounted || epoch != accessEpoch) return;
      if (tasks.isEmpty) throw const ApiFailure('暂无可更新材料的任务，请先新建任务。');
      final task = tasks.length == 1
          ? tasks.first
          : await showDialog<ResearchTask>(
              context: context,
              builder: (c) =>
                  SimpleDialog(title: const Text('选择要更新材料的任务'), children: [
                    for (final task in tasks)
                      SimpleDialogOption(
                          onPressed: () => Navigator.pop(c, task),
                          child: Text(task.title))
                  ]));
      if (task == null || !mounted || epoch != accessEpoch) return;
      ref.read(selectedTaskProvider.notifier).state = task;
      final saved = await Navigator.of(context).push<bool>(MaterialPageRoute(
          builder: (_) => const TaskDetailScreen(updateMaterials: true)));
      if (!mounted || epoch != accessEpoch) return;
      if (saved == true) {
        ref.invalidate(tasksProvider);
        ref.invalidate(taskProvider(task.id));
        setState(() => materialsUpdated = true);
      }
    } finally {
      if (mounted && epoch == accessEpoch) setState(() => busy = false);
    }
  }

  Future<void> decide(Map<String, dynamic> p, bool approve) async {
    if (busy) return;
    final epoch = accessEpoch;
    final reason = approve ? null : await askText(context, '拒绝原因');
    if (!mounted || epoch != accessEpoch) return;
    if (!approve && reason == null) return;
    setState(() => busy = true);
    try {
      await ref.read(apiClientProvider).write(
          '/proposals/${p['id']}/${approve ? 'approve' : 'reject'}',
          {'expected_version': p['version'], if (!approve) 'reason': reason});
      if (!mounted || epoch != accessEpoch) return;
      ref.invalidate(reportsProvider);
      ref.invalidate(tasksProvider);
      if (approve) {
        ref.invalidate(reportProvider);
        ref.invalidate(taskProvider);
        ref.invalidate(dashboardProvider);
      }
      await poll();
    } finally {
      if (mounted && epoch == accessEpoch) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(sessionProvider, (previous, next) {
      if (!next.isLoading &&
          previous?.valueOrNull?.authorizationKey !=
              next.valueOrNull?.authorizationKey) {
        clearAccess();
      }
    });
    ref.listen(roleProvider, (previous, next) {
      if (previous != next) clearAccess();
    });
    ref.listen(selectedStudentProvider, (previous, next) {
      if (previous != next && ref.read(roleProvider) == UserRole.mentor) {
        clearAccess();
      }
    });
    final subject = run?['subject'] is Map ? run!['subject'] as Map : null;
    final original = run?['input'] is Map ? run!['input'] as Map : null;
    final canReanalyse =
        subject?['student_id'] is String && original?['question'] is String;
    return Scaffold(
        appBar: AppBar(title: const Text('AI 分析与建议')),
        body: ListView(padding: _pagePadding, children: [
          Text(
              run?['status'] == null
                  ? (error != null
                      ? '运行未加载'
                      : targetRunId != null
                          ? '正在加载运行'
                          : '正在创建运行')
                  : '运行状态：${runStatusLabel(run!['status'])}',
              style: Theme.of(context).textTheme.headlineSmall),
          if (busy) const LinearProgressIndicator(),
          if (run?['status'] == 'queued')
            const Text('正在等待处理，可稍后从运行历史查看。若长时间未开始，请联系维护人员。'),
          if (['queued', 'running'].contains(run?['status']))
            OutlinedButton(
                onPressed: busy || run?['version'] == null
                    ? null
                    : () => perform(context, cancelRun),
                child: const Text('取消运行')),
          if (error != null) ...[
            Text(error!, style: const TextStyle(color: Colors.red)),
            TextButton(onPressed: busy ? null : start, child: const Text('重试'))
          ],
          if (['failed', 'cancelled'].contains(run?['status']))
            OutlinedButton(
                onPressed: busy ? null : () => perform(context, retryRun),
                child: const Text('重新运行')),
          if (events.isNotEmpty)
            ExpansionTile(
              title: const Text('查看处理过程'),
              initiallyExpanded: ['queued', 'running'].contains(run?['status']),
              children: [
                for (final event in events)
                  ListTile(
                      title:
                          Text(eventStepLabel(event['step'] ?? event['type'])),
                      subtitle: Text(eventStatusLabel(event['status'])))
              ],
            ),
          if (run?['result'] is Map) AgentResultCards(run!['result']),
          if (run?['status'] != null &&
              !['queued', 'running'].contains(run!['status'])) ...[
            if (canReanalyse) ...[
              OutlinedButton.icon(
                  onPressed:
                      busy ? null : () => perform(context, updateMaterials),
                  icon: const Icon(Icons.edit_note),
                  label: Text(materialsUpdated ? '继续更新' : '更新材料')),
              OutlinedButton(
                  onPressed: busy
                      ? null
                      : () {
                          final stage = subject?['stage_id'];
                          final paper = subject?['paper_id'];
                          context
                              .push(Uri(path: '/assistant', queryParameters: {
                            'question': original!['question'],
                            if (stage is String && stage.isNotEmpty)
                              'stage_id': stage,
                            if (paper is String && paper.isNotEmpty)
                              'paper_id': paper,
                          }).toString());
                        },
                  child: const Text('重新分析')),
            ] else
              Text(reanalysisHint(run!['kind'])),
          ],
          if (run?['error'] != null)
            Text(run!['error'] is Map
                ? _text(run!['error']['message'] ?? run!['error']['code'])
                : _text(run!['error'])),
          if (proposals.isNotEmpty) const SectionHeader('待确认行动'),
          for (final p in proposals)
            Card(
                child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Text(proposalActionLabel(p['action_type'])),
                          Text(_text(p['reason'])),
                          ProposalContent(p, taskTitles: proposalTaskTitles),
                          Text('状态：${proposalStatusLabel(p['status'])}'),
                          if (p['status'] == 'pending' &&
                              p['can_approve'] != true)
                            Padding(
                                padding:
                                    const EdgeInsets.symmetric(vertical: 8),
                                child: Text(
                                    ref.watch(roleProvider) ==
                                                UserRole.mentor &&
                                            p['action_type'] ==
                                                'reading_note.draft'
                                        ? '阅读笔记由学生本人确认。你可以先查看导读，和学生讨论这些内容。'
                                        : '这条建议目前不能确认，请核对当前账号和材料是否仍可使用。',
                                    style: const TextStyle(height: 1.6))),
                          if (p['status'] == 'pending')
                            Row(children: [
                              Expanded(
                                  child: OutlinedButton(
                                      onPressed: busy ||
                                              p['can_approve'] != true
                                          ? null
                                          : () => perform(
                                              context, () => decide(p, false)),
                                      child: const Text('拒绝'))),
                              const SizedBox(width: 8),
                              Expanded(
                                  child: FilledButton(
                                      onPressed: busy ||
                                              p['can_approve'] != true
                                          ? null
                                          : () => perform(
                                              context, () => decide(p, true)),
                                      child: const Text('确认应用')))
                            ])
                        ]))),
        ]));
  }
}
