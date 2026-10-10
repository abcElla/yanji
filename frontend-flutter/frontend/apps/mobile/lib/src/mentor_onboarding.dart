import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'data.dart';

class MentorConnectionCard extends ConsumerStatefulWidget {
  const MentorConnectionCard({super.key});
  @override
  ConsumerState<MentorConnectionCard> createState() =>
      _MentorConnectionCardState();
}

class _MentorConnectionCardState extends ConsumerState<MentorConnectionCard> {
  String? selected;
  String? selectedMentor;
  String keyword = '';
  final pageCursors = <String?>[null];
  int pageIndex = 0;
  ({String keyword, String? cursor}) get pageQuery =>
      (keyword: keyword, cursor: pageCursors[pageIndex]);

  void changePage(int index, [String? nextCursor]) {
    setState(() {
      if (nextCursor != null &&
          index < pageCursors.length &&
          pageCursors[index] != nextCursor) {
        pageCursors.removeRange(index, pageCursors.length);
      }
      if (index >= pageCursors.length) pageCursors.add(nextCursor);
      pageIndex = index;
      selected = null;
      selectedMentor = null;
      error = null;
    });
  }

  bool searching = false;
  Timer? debounce;
  final searchController = TextEditingController();

  @override
  void dispose() {
    debounce?.cancel();
    searchController.dispose();
    super.dispose();
  }

  void search(String value) {
    debounce?.cancel();
    setState(() {
      selected = null;
      selectedMentor = null;
      searching = true;
      error = null;
    });
    debounce = Timer(const Duration(milliseconds: 350), () {
      if (!mounted) return;
      setState(() {
        keyword = value.trim();
        pageIndex = 0;
        pageCursors
          ..clear()
          ..add(null);
        searching = false;
      });
    });
  }

  Widget mentorResults(Map<String, dynamic> page) {
    final rawRows = page['mentors'];
    if (rawRows is! List) return const Text('导师列表返回格式不正确，请刷新重试。');
    final rows = rawRows
        .whereType<Map>()
        .map((m) => Map<String, dynamic>.from(m))
        .toList();
    final nextCursor =
        page['next_cursor'] is String ? page['next_cursor'] as String : null;
    var choices = options(rows);
    final fullId = RegExp(
        r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$');
    if (fullId.hasMatch(keyword)) {
      choices = choices
          .where((m) =>
              m['mentor_id'].toString().toLowerCase() == keyword.toLowerCase())
          .toList();
    }
    if (choices.isEmpty) {
      return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(keyword.isEmpty ? '暂无可申请的导师，请稍后刷新。' : '本页未找到匹配的导师。'),
        pagination(nextCursor)
      ]);
    }
    final teachers = <String, List<Map<String, dynamic>>>{};
    for (final choice in choices) {
      teachers.putIfAbsent(choice['mentor_id'] as String, () => []).add(choice);
    }
    final labs = teachers[selectedMentor] ?? [];
    final selection = choices.where((m) => m['key'] == selected).firstOrNull;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [Text('导师姓名'), Text('导师 ID')],
          )),
      ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: 300),
          child: ListView(
            shrinkWrap: true,
            children: teachers.entries.map((entry) {
              final chosen = selectedMentor == entry.key;
              return Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Material(
                      color: chosen
                          ? Theme.of(context).colorScheme.secondaryContainer
                          : Theme.of(context).colorScheme.surface,
                      shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                          side: BorderSide(
                              color: chosen
                                  ? Theme.of(context).colorScheme.primary
                                  : Theme.of(context)
                                      .colorScheme
                                      .outlineVariant)),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(12),
                        onTap: busy
                            ? null
                            : () => setState(() {
                                  selectedMentor = entry.key;
                                  selected = entry.value.length == 1
                                      ? entry.value.first['key'] as String
                                      : null;
                                }),
                        child: Padding(
                            padding: const EdgeInsets.all(12),
                            child: Row(children: [
                              Expanded(
                                  flex: 2,
                                  child: Text(
                                      entry.value.first['name'].toString(),
                                      style: const TextStyle(
                                          fontWeight: FontWeight.w600))),
                              const SizedBox(width: 12),
                              Expanded(
                                  flex: 3,
                                  child: Text(entry.key,
                                      textAlign: TextAlign.right,
                                      style: const TextStyle(fontSize: 12))),
                              if (chosen) ...[
                                const SizedBox(width: 6),
                                const Icon(Icons.check_circle, size: 18)
                              ],
                            ])),
                      )));
            }).toList(),
          )),
      if (labs.length == 1)
        Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text('实验室：${labs.first['lab_name']}')),
      if (labs.length > 1) ...[
        const Text('请选择该导师的实验室：'),
        ...labs.map((lab) => ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(selected == lab['key']
                  ? Icons.radio_button_checked
                  : Icons.radio_button_unchecked),
              title: Text(lab['lab_name'].toString()),
              onTap: busy
                  ? null
                  : () => setState(() => selected = lab['key'] as String),
            )),
      ],
      pagination(nextCursor),
      FilledButton(
          onPressed: busy || selection == null ? null : () => apply(selection),
          child: Text(busy ? '正在提交' : '申请加入')),
    ]);
  }

  Widget pagination(String? nextCursor) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child:
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          TextButton(
              onPressed: busy || pageIndex == 0
                  ? null
                  : () => changePage(pageIndex - 1),
              child: const Text('上一页')),
          Text('第 ${pageIndex + 1} 页'),
          TextButton(
              onPressed: busy ||
                      nextCursor == null ||
                      (pageCursors.take(pageIndex + 1).contains(nextCursor))
                  ? null
                  : () => changePage(pageIndex + 1, nextCursor),
              child: const Text('下一页')),
        ]),
      );

  bool busy = false;
  String? error;

  Future<void> refresh() async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await ref.read(sessionProvider.notifier).loadMe();
      ref.invalidate(myMentorApplicationProvider);
      setState(() {
        pageIndex = 0;
        pageCursors
          ..clear()
          ..add(null);
        selected = null;
        selectedMentor = null;
      });
      ref.invalidate(mentorsProvider);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> apply(Map<String, dynamic> mentor) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await ref.read(apiClientProvider).post('/mentor-applications',
          {'mentor_id': mentor['mentor_id'], 'lab_id': mentor['lab_id']});
      // Submission succeeds independently of the subsequent status refresh.
      ref.invalidate(myMentorApplicationProvider);
      await ref.read(sessionProvider.notifier).loadMe();
    } catch (e) {
      ref.invalidate(myMentorApplicationProvider);
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  List<Map<String, dynamic>> options(List<Map<String, dynamic>> rows) {
    final result = <Map<String, dynamic>>[];
    final keys = <String>{};
    for (final row in rows) {
      final labs = row['labs'] is List ? row['labs'] as List : [row];
      for (final lab in labs.whereType<Map>()) {
        final mentorId = row['mentor_id'] ?? row['id'];
        final labId = lab['lab_id'] ?? (row['labs'] is List ? lab['id'] : null);
        if (mentorId is! String ||
            labId is! String ||
            mentorId.isEmpty ||
            labId.isEmpty) {
          continue;
        }
        final key = '$mentorId:$labId';
        if (keys.add(key)) {
          result.add({
            'key': key,
            'mentor_id': mentorId,
            'lab_id': labId,
            'name': row['display_name'] ?? row['mentor_name'] ?? '导师',
            'lab_name': lab['lab_name'] ?? lab['name'] ?? ''
          });
        }
      }
    }
    return result;
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(sessionProvider).valueOrNull;
    if (session == null) return const SizedBox.shrink();
    if (session.collaborationEnabled) {
      return Card(
          child: ListTile(
              leading: const Icon(Icons.check_circle_outline),
              title: const Text('已加入实验室'),
              subtitle: const Text('科研协作功能已开放')));
    }
    final application = ref.watch(myMentorApplicationProvider);
    return Card(
        child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('导师与实验室',
                      style:
                          TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
                  const SizedBox(height: 12),
                  application.when(
                      loading: () => const LinearProgressIndicator(),
                      error: (e, _) => Text(e.toString()),
                      data: (value) {
                        final pending = value == null
                            ? session.onboardingStatus == 'pending'
                            : value['status'] == 'pending';
                        if (pending) {
                          return const Text('申请已提交，等待导师确认。批准后即可使用任务、周报和 AI。');
                        }
                        if (value?['status'] == 'approved' ||
                            value?['status'] == 'approve') {
                          return const Text('导师已批准申请，请刷新状态以开放科研协作功能。');
                        }
                        return Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              if (value?['status'] == 'rejected' ||
                                  value?['status'] == 'reject') ...[
                                const Text('申请未通过，可以重新选择导师。'),
                                Text(
                                    '拒绝原因：${value?['rejection_reason'] ?? '未提供'}'),
                                const SizedBox(height: 12),
                              ],
                              const Text('选择导师及其实验室，提交入组申请。'),
                              const SizedBox(height: 12),
                              TextField(
                                controller: searchController,
                                enabled: !busy,
                                maxLength: 100,
                                onChanged: search,
                                decoration: const InputDecoration(
                                  labelText: '搜索导师姓名或 ID',
                                  hintText: '输入老师姓名或老师 ID',
                                  prefixIcon: Icon(Icons.search),
                                  border: OutlineInputBorder(),
                                  counterText: '',
                                ),
                              ),
                              const SizedBox(height: 8),
                              if (searching)
                                const LinearProgressIndicator()
                              else
                                ref.watch(mentorsProvider(pageQuery)).when(
                                      skipLoadingOnRefresh: false,
                                      loading: () =>
                                          const LinearProgressIndicator(),
                                      error: (e, _) => Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.stretch,
                                          children: [
                                            Text(e.toString()),
                                            TextButton(
                                                onPressed: () => ref.invalidate(
                                                    mentorsProvider(pageQuery)),
                                                child: const Text('重试搜索')),
                                          ]),
                                      data: mentorResults,
                                    ),
                            ]);
                      }),
                  if (error != null)
                    Text(error!, style: const TextStyle(color: Colors.red)),
                  TextButton(
                      onPressed: busy ? null : refresh,
                      child: const Text('刷新申请状态')),
                ])));
  }
}

class MentorApplicationsCard extends ConsumerStatefulWidget {
  const MentorApplicationsCard({super.key});
  @override
  ConsumerState<MentorApplicationsCard> createState() =>
      _MentorApplicationsCardState();
}

class _MentorApplicationsCardState
    extends ConsumerState<MentorApplicationsCard> {
  bool busy = false;
  String? error;
  Future<void> decide(String id, bool approve) async {
    if (busy) return;
    final reason = approve
        ? null
        : await showDialog<String>(
            context: context, builder: (_) => const _RejectionDialog());
    if (!mounted || (!approve && reason == null)) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await ref.read(apiClientProvider).post(
          '/mentor-applications/$id/decision', {
        'decision': approve ? 'approve' : 'reject',
        if (!approve) 'reason': reason
      });
      ref.invalidate(pendingMentorApplicationsProvider);
      ref.invalidate(studentsProvider);
      ref.invalidate(dashboardProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(approve ? '已同意，学生已加入实验室' : '已拒绝申请')));
      }
    } catch (e) {
      ref.invalidate(pendingMentorApplicationsProvider);
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Card(
      child: Padding(
          padding: const EdgeInsets.all(20),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            const Text('入组申请',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
            if (error != null)
              Text(error!, style: const TextStyle(color: Colors.red)),
            ref.watch(pendingMentorApplicationsProvider).when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(e.toString()),
                data: (rows) => Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (rows.isEmpty)
                            const Padding(
                                padding: EdgeInsets.all(12),
                                child: Text('暂无待审批申请')),
                          for (final row in rows)
                            ListTile(
                                title: Text(
                                    '${row['student_display_name'] ?? row['student_name'] ?? row['display_name'] ?? '学生申请'}'),
                                subtitle: Text('学生编号：${row['student_id']}'),
                                trailing: Wrap(spacing: 8, children: [
                                  TextButton(
                                      onPressed: busy
                                          ? null
                                          : () => decide(
                                              row['id'] as String, false),
                                      child: const Text('拒绝')),
                                  FilledButton(
                                      onPressed: busy
                                          ? null
                                          : () =>
                                              decide(row['id'] as String, true),
                                      child: const Text('同意')),
                                ])),
                        ])),
            TextButton(
                onPressed: busy
                    ? null
                    : () => ref.invalidate(pendingMentorApplicationsProvider),
                child: const Text('刷新申请列表')),
          ])));
}

class _RejectionDialog extends StatefulWidget {
  const _RejectionDialog();
  @override
  State<_RejectionDialog> createState() => _RejectionDialogState();
}

class _RejectionDialogState extends State<_RejectionDialog> {
  final input = TextEditingController();
  final form = GlobalKey<FormState>();
  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
          title: const Text('拒绝原因'),
          content: Form(
              key: form,
              child: TextFormField(
                  controller: input,
                  autofocus: true,
                  maxLines: 3,
                  validator: (v) =>
                      v?.trim().isNotEmpty == true ? null : '请填写拒绝原因')),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('取消')),
            FilledButton(
                onPressed: () {
                  if (form.currentState!.validate()) {
                    Navigator.pop(context, input.text.trim());
                  }
                },
                child: const Text('确认拒绝'))
          ]);
}
