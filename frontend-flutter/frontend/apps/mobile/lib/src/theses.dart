import 'dart:async';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';
import 'data.dart';
import 'models.dart';
import 'design_system.dart';
import 'agent_result_cards.dart';

String? thesisScope(WidgetRef ref) {
  final session = ref.read(sessionProvider).valueOrNull;
  final role = ref.read(roleProvider);
  return collaborationScope(session, role, ref.read(selectedStudentProvider));
}

String ingestLabel(dynamic status) =>
    const {
      'ready': '材料已准备好',
      'queued': '等待解析',
      'parsing': '正在解析',
      'indexing': '正在整理',
      'failed': '解析失败，请重新上传'
    }[status] ??
    '等待解析';

class ThesesScreen extends ConsumerStatefulWidget {
  const ThesesScreen({super.key});
  @override
  ConsumerState<ThesesScreen> createState() => _ThesesScreenState();
}

class _ThesesScreenState extends ConsumerState<ThesesScreen> {
  List<Map<String, dynamic>> items = [];
  final title = TextEditingController();
  String? scope, error;
  bool busy = false;
  int epoch = 0;
  @override
  void dispose() {
    epoch++;
    title.dispose();
    super.dispose();
  }

  Future<void> load() async {
    if (busy || scope == null) return;
    final ticket = epoch;
    final requested = scope;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final rows = await ref
          .read(apiClientProvider)
          .list('/theses', query: {'student_id': requested!.split('|').last});
      if (mounted && ticket == epoch && requested == thesisScope(ref)) {
        setState(() => items = rows);
      }
    } catch (e) {
      if (mounted && ticket == epoch && requested == thesisScope(ref)) {
        setState(() {
          if (isAccessFailure(e)) {
            items = [];
            title.clear();
          }
          error = e.toString();
        });
      }
    } finally {
      if (mounted && ticket == epoch) setState(() => busy = false);
    }
  }

  Future<void> create() async {
    if (busy || scope == null || ref.read(roleProvider) != UserRole.student) {
      return;
    }
    if (title.text.trim().isEmpty) {
      setState(() => error = '请填写论文题目');
      return;
    }
    final memberships = ref
            .read(sessionProvider)
            .valueOrNull
            ?.memberships
            .whereType<Map>()
            .where((m) =>
                m['role'] == 'student' &&
                (m['status'] == null || m['status'] == 'active'))
            .toList() ??
        [];
    if (memberships.isEmpty) {
      setState(() => error = '请先加入实验室');
      return;
    }
    final ticket = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final saved = await ref.read(apiClientProvider).write('/theses',
          {'title': title.text.trim(), 'lab_id': memberships.first['lab_id']});
      if (!mounted || ticket != epoch || scope != thesisScope(ref)) return;
      title.clear();
      setState(() => busy = false);
      await context
          .push('/theses/${Uri.encodeComponent(saved['id'].toString())}');
      if (mounted && ticket == epoch) await load();
    } catch (e) {
      if (mounted && ticket == epoch) setState(() => error = e.toString());
    } finally {
      if (mounted && ticket == epoch) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(sessionProvider);
    ref.watch(roleProvider);
    ref.watch(selectedStudentProvider);
    final current = thesisScope(ref);
    if (current != scope) {
      epoch++;
      scope = current;
      items = [];
      title.clear();
      error = null;
      busy = false;
      if (scope != null) Future.microtask(load);
    }
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    final students = mentor
        ? ref.watch(studentsProvider).valueOrNull ?? []
        : <Map<String, dynamic>>[];
    final selected = ref.watch(selectedStudentProvider);
    return Scaffold(
        appBar: AppBar(title: const Text('论文档案')),
        body: RefreshIndicator(
            onRefresh: load,
            child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(20),
                children: [
                  ResearchPanel(
                      title: mentor ? '跟进每一版稿件' : '从初稿到定稿',
                      icon: Icons.folder_open_outlined,
                      color: ResearchDesign.blue,
                      child: Text(mentor
                          ? '选定学生和稿件，再给出具体修改意见。历史稿件与反馈都会保留。'
                          : '写清每次修改，保留历史稿件，让导师和 AI 对照同一版材料。')),
                  if (mentor)
                    DropdownButtonFormField<String>(
                        key: ValueKey(selected),
                        isExpanded: true,
                        initialValue: students.any((s) => s['id'] == selected)
                            ? selected
                            : null,
                        decoration: const InputDecoration(labelText: '选择学生'),
                        items: students
                            .map((s) => DropdownMenuItem(
                                value: s['id'].toString(),
                                child: Text(
                                    s['display_name']?.toString() ?? '学生')))
                            .toList(),
                        onChanged: (v) => ref
                            .read(selectedStudentProvider.notifier)
                            .state = v),
                  if (scope == null) const ResearchEmpty('请登录并选择已绑定的学生。'),
                  if (scope != null && !mentor)
                    ResearchPanel(
                        title: '新建论文档案',
                        icon: Icons.note_add_outlined,
                        child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              TextField(
                                  controller: title,
                                  enabled: !busy,
                                  maxLength: 200,
                                  decoration: const InputDecoration(
                                      labelText: '论文题目',
                                      hintText: '请输入正在撰写的论文题目')),
                              FilledButton.icon(
                                  onPressed: busy ? null : create,
                                  icon: const Icon(Icons.add),
                                  label: const Text('创建档案'))
                            ])),
                  if (!busy && scope != null && items.isEmpty)
                    const ResearchEmpty('还没有论文档案。学生创建后，可发布第一版稿件。'),
                  for (final row in items)
                    Card(
                        child: ListTile(
                            leading: const ResearchIcon(Icons.article_outlined),
                            title: Text(row['title']?.toString() ?? '论文',
                                softWrap: true),
                            subtitle: Text(
                                '档案已建立 · ${beijingTime(row['created_at'])}'),
                            trailing: const Icon(Icons.chevron_right),
                            onTap: () async {
                              await context.push(
                                  '/theses/${Uri.encodeComponent(row['id'].toString())}');
                              if (mounted) await load();
                            })),
                  if (busy) const LinearProgressIndicator(),
                  if (error != null) ...[
                    Text(error!,
                        style: const TextStyle(color: ResearchDesign.red)),
                    TextButton(
                        onPressed: busy ? null : load,
                        child: const Text('重新加载'))
                  ],
                ])));
  }
}

class ThesisDetailScreen extends ConsumerStatefulWidget {
  const ThesisDetailScreen({required this.thesisId, super.key});
  final String thesisId;
  @override
  ConsumerState<ThesisDetailScreen> createState() => _ThesisDetailScreenState();
}

class _ThesisDetailScreenState extends ConsumerState<ThesisDetailScreen> {
  Map<String, dynamic>? archive;
  final summary = TextEditingController(), feedback = TextEditingController();
  PlatformFile? file;
  String? uploadedFileId, selectedRevision, error, initialScope;
  String decision = 'changes_requested';
  bool busy = false, accessLost = false, conflict = false;
  int epoch = 0;
  Timer? timer;
  @override
  void initState() {
    super.initState();
    initialScope = thesisScope(ref);
    Future.microtask(load);
  }

  @override
  void dispose() {
    epoch++;
    timer?.cancel();
    summary.dispose();
    feedback.dispose();
    super.dispose();
  }

  bool valid(int ticket) =>
      mounted &&
      !accessLost &&
      ticket == epoch &&
      initialScope != null &&
      initialScope == thesisScope(ref);
  void clearAccess() {
    epoch++;
    timer?.cancel();
    setState(() {
      archive = null;
      file = null;
      uploadedFileId = null;
      selectedRevision = null;
      summary.clear();
      feedback.clear();
      busy = false;
      accessLost = true;
      error = '访问范围已改变，请返回论文档案重新选择。';
    });
  }

  List<Map<String, dynamic>> get revisions => objectRows(archive?['revisions']);
  Map<String, dynamic>? get revision {
    final rows = revisions.where((r) => r['id'] == selectedRevision);
    return rows.isEmpty ? null : rows.first;
  }

  Future<void> load() async {
    if (busy || accessLost || initialScope == null) return;
    timer?.cancel();
    final ticket = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final data =
          await ref.read(apiClientProvider).get('/theses/${widget.thesisId}');
      if (!valid(ticket)) return;
      if (data['student_id'] != initialScope!.split('|').last) {
        throw const ApiFailure('当前学生无权查看该档案', status: 403);
      }
      if (data['revisions'] is! List) throw const ApiFailure('稿件列表暂时无法读取');
      setState(() {
        archive = data;
        conflict = false;
        if (!revisions.any((r) => r['id'] == selectedRevision)) {
          selectedRevision =
              revisions.isEmpty ? null : revisions.first['id']?.toString();
        }
      });
      if (revisions.any((r) =>
          ['queued', 'parsing', 'indexing'].contains(r['ingest_status']))) {
        timer = Timer(const Duration(seconds: 4), load);
      }
    } catch (e) {
      if (valid(ticket)) {
        if (isAccessFailure(e)) {
          clearAccess();
          return;
        }
        setState(() {
          error = e.toString();
        });
      }
    } finally {
      if (valid(ticket)) setState(() => busy = false);
    }
  }

  Future<void> chooseFile() async {
    if (busy || accessLost) return;
    final ticket = epoch;
    final picked = await FilePicker.pickFiles(
        type: FileType.custom, allowedExtensions: ['pdf', 'docx']);
    if (!valid(ticket) || picked == null) return;
    final next = picked.files.single;
    if (next.path == null ||
        !['pdf', 'docx'].contains(next.extension?.toLowerCase())) {
      setState(() => error = '请选择可读取的 PDF 或 DOCX 文件');
      return;
    }
    if (next.size > 20 * 1024 * 1024) {
      setState(() => error = 'PDF / DOCX 文件不能超过 20 MiB');
      return;
    }
    setState(() {
      file = next;
      uploadedFileId = null;
      error = null;
    });
  }

  Future<void> publish() async {
    if (busy ||
        conflict ||
        archive == null ||
        accessLost ||
        ref.read(roleProvider) != UserRole.student) {
      return;
    }
    if (file == null || summary.text.trim().isEmpty) {
      setState(() => error = '请选择稿件并填写本次修改说明');
      return;
    }
    final ticket = epoch;
    final requestedVersion = archive!['version'];
    final change = summary.text.trim();
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final api = ref.read(apiClientProvider);
      final selected = file!;
      if (uploadedFileId == null) {
        final digest = await sha256.bind(File(selected.path!).openRead()).first;
        if (!valid(ticket)) return;
        final slot =
            'thesis_upload_${Uri.encodeComponent('${api.dio.options.baseUrl}:$initialScope:${archive!['lab_id']}:$digest')}';
        const storage = SessionController.storage;
        final key = await storage.read(key: slot) ?? const Uuid().v4();
        await storage.write(key: slot, value: key);
        if (!valid(ticket)) return;
        final uploaded = await api.request('POST', '/files',
            key: key,
            body: FormData.fromMap({
              'file': await MultipartFile.fromFile(selected.path!,
                  filename: selected.name,
                  contentType: DioMediaType.parse(selected.extension
                              ?.toLowerCase() ==
                          'pdf'
                      ? 'application/pdf'
                      : 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')),
              'purpose': 'paper',
              'lab_id': archive!['lab_id'],
              'client_upload_id': key
            }));
        if (!valid(ticket)) return;
        if (uploaded['file_id'] is! String) {
          throw const ApiFailure('上传响应缺少文件信息，请重试');
        }
        uploadedFileId = uploaded['file_id'];
        await storage.delete(key: slot);
      }
      if (!valid(ticket)) return;
      final saved = await api.write('/theses/${widget.thesisId}/revisions', {
        'file_id': uploadedFileId,
        'change_summary': change,
        'expected_version': requestedVersion
      });
      if (!valid(ticket)) return;
      setState(() {
        selectedRevision = saved['id']?.toString();
        file = null;
        uploadedFileId = null;
        summary.clear();
        busy = false;
      });
      ref.invalidate(papersProvider);
      await load();
    } catch (e) {
      if (valid(ticket)) {
        if (isAccessFailure(e)) {
          clearAccess();
          return;
        }
        setState(() {
          conflict = e is ApiFailure && e.status == 409;
          error = conflict ? '档案已更新，填写内容已保留。请刷新最新稿件后再发布。' : '发布未完成，填写内容已保留：$e';
        });
      }
    } finally {
      if (valid(ticket)) setState(() => busy = false);
    }
  }

  Future<void> submitFeedback() async {
    if (busy ||
        accessLost ||
        revision == null ||
        ref.read(roleProvider) != UserRole.mentor) {
      return;
    }
    if (feedback.text.trim().isEmpty) {
      setState(() => error = '请写下具体修改意见或通过理由');
      return;
    }
    final ticket = epoch;
    final target = revision!['id'];
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await ref.read(apiClientProvider).write(
          '/theses/${widget.thesisId}/revisions/$target/feedback',
          {'content': feedback.text.trim(), 'decision': decision});
      if (!valid(ticket)) return;
      setState(() {
        feedback.clear();
        busy = false;
      });
      await load();
    } catch (e) {
      if (valid(ticket)) {
        if (isAccessFailure(e)) {
          clearAccess();
          return;
        }
        setState(() => error = e is ApiFailure && e.status == 409
            ? '档案已更新，填写内容已保留。请刷新后再提交反馈。'
            : '反馈未提交，填写内容已保留：$e');
      }
    } finally {
      if (valid(ticket)) setState(() => busy = false);
    }
  }

  Future<void> download() async {
    if (busy || revision == null || accessLost) return;
    final ticket = epoch;
    final target = Map<String, dynamic>.from(revision!);
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final response = await ref.read(apiClientProvider).dio.get<List<int>>(
          '/theses/${widget.thesisId}/revisions/${target['id']}/download',
          options: Options(responseType: ResponseType.bytes));
      if (!valid(ticket)) return;
      final bytes = response.data;
      if (bytes == null || bytes.isEmpty) {
        throw const ApiFailure('稿件暂时无法下载，请重试');
      }
      final extension =
          response.headers.value('content-type')?.contains('pdf') == true ||
                  bytes.take(4).join(',') == '37,80,68,70'
              ? 'pdf'
              : 'docx';
      final folder = await getTemporaryDirectory();
      if (!valid(ticket)) return;
      final safeId =
          target['id'].toString().replaceAll(RegExp(r'[^a-zA-Z0-9_-]'), '_');
      final saved = File(
          '${folder.path}/yanji_${safeId}_v${target['revision_number']}.$extension');
      await saved.writeAsBytes(bytes, flush: true);
      if (!valid(ticket)) {
        await saved.delete();
        return;
      }
      final result = await OpenFilex.open(saved.path);
      if (!valid(ticket)) return;
      if (result.type != ResultType.done) {
        final output = await FilePicker.saveFile(
            dialogTitle: '保存稿件',
            fileName: '论文第${target['revision_number']}稿.$extension',
            bytes: await saved.readAsBytes());
        if (!mounted || !valid(ticket)) return;
        if (output != null) {
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('稿件已保存，可使用 PDF 或 Word 阅读器打开')));
        }
      }
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        ref.read(apiClientProvider).onSessionExpired?.call();
      }
      if (valid(ticket)) {
        if ([401, 403, 404].contains(e.response?.statusCode)) {
          clearAccess();
          return;
        }
        setState(() {
          error = '稿件下载失败，请检查访问权限后重试';
        });
      }
    } catch (e) {
      if (valid(ticket)) {
        if (isAccessFailure(e)) {
          clearAccess();
          return;
        }
        setState(() => error = '下载未完成：$e');
      }
    } finally {
      if (valid(ticket)) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(sessionProvider, (previous, next) {
      if (!next.isLoading &&
          (next.valueOrNull?.authorizationKey !=
                  previous?.valueOrNull?.authorizationKey ||
              next.valueOrNull?.collaborationEnabled != true)) {
        clearAccess();
      }
    });
    ref.listen(selectedStudentProvider, (previous, next) {
      if (previous != next) clearAccess();
    });
    ref.listen(roleProvider, (previous, next) {
      if (previous != next) clearAccess();
    });
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    final selected = revision;
    return Scaffold(
        appBar: AppBar(title: const Text('论文详情'), actions: [
          IconButton(
              tooltip: '刷新稿件',
              onPressed: busy || accessLost ? null : load,
              icon: const Icon(Icons.refresh))
        ]),
        body: ListView(padding: const EdgeInsets.all(20), children: [
          if (archive != null) ...[
            ResearchPanel(
                title: archive!['title']?.toString() ?? '论文',
                icon: Icons.article_outlined,
                child: Text('${revisions.length} 版稿件 · 每次发布都保留历史')),
            if (!mentor)
              ResearchPanel(
                  title: '发布新稿',
                  icon: Icons.upload_file_outlined,
                  color: ResearchDesign.blue,
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        const Text('支持 PDF / DOCX，最大 20 MiB。请说明本次修改，便于导师逐版审阅。'),
                        OutlinedButton.icon(
                            onPressed: busy ? null : chooseFile,
                            icon: const Icon(Icons.attach_file),
                            label: const Text('选择稿件')),
                        if (file != null) Text(file!.name, softWrap: true),
                        const SizedBox(height: 12),
                        TextField(
                            key: const ValueKey('thesis-summary'),
                            controller: summary,
                            enabled: !busy,
                            minLines: 3,
                            maxLines: 6,
                            maxLength: 5000,
                            decoration: const InputDecoration(
                                labelText: '本次修改说明',
                                hintText: '例如：补充抽样方法，修正图表，回应导师上一版意见')),
                        FilledButton(
                            onPressed: busy || conflict ? null : publish,
                            child: Text(busy ? '正在发布' : '上传并发布'))
                      ])),
            if (revisions.isEmpty)
              const ResearchEmpty('还没有稿件。发布第一版后，可查看历史记录和导师反馈。'),
            if (revisions.isNotEmpty) ...[
              ResearchPanel(
                  title: '历史稿件',
                  icon: Icons.history,
                  color: ResearchDesign.blue,
                  child: Column(children: [
                    for (final row in revisions)
                      ListTile(
                          contentPadding: EdgeInsets.zero,
                          leading: ResearchIcon(Icons.description_outlined,
                              color: row['id'] == selectedRevision
                                  ? ResearchDesign.purple
                                  : ResearchDesign.blue),
                          title: Text(
                              '第 ${row['revision_number']} 稿${row == revisions.first ? ' · 最新' : ''}'),
                          subtitle: Text(
                              '${beijingTime(row['created_at'])}\n${row['change_summary'] ?? ''}'),
                          trailing: row['id'] == selectedRevision
                              ? const Icon(Icons.check_circle,
                                  color: ResearchDesign.teal)
                              : const Icon(Icons.chevron_right),
                          onTap: busy
                              ? null
                              : () => setState(() {
                                    selectedRevision = row['id'];
                                    decision = 'changes_requested';
                                    feedback.clear();
                                    error = null;
                                  }))
                  ])),
              if (selected != null) ...[
                ResearchPanel(
                    title: '正在查看第 ${selected['revision_number']} 稿',
                    icon: Icons.description_outlined,
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Text(ingestLabel(selected['ingest_status']),
                              style: TextStyle(
                                  color: selected['ingest_status'] == 'ready'
                                      ? ResearchDesign.teal
                                      : ResearchDesign.orange)),
                          Text(selected['change_summary']?.toString() ?? ''),
                          OutlinedButton.icon(
                              onPressed: busy ? null : download,
                              icon: const Icon(Icons.download_outlined),
                              label: const Text('下载 / 打开稿件')),
                          const SizedBox(height: 8),
                          ResearchHero(
                              title: 'AI 修改建议',
                              description: selected['ingest_status'] == 'ready'
                                  ? '尚未生成。结合这版稿件和可查看的导师反馈，找出优先修改的内容。'
                                  : '材料正在准备，解析完成后可请 AI 分析。',
                              action: selected['ingest_status'] == 'ready'
                                  ? '分析这版稿件'
                                  : '刷新材料状态',
                              onTap: () {
                                if (busy) return;
                                if (selected['ingest_status'] != 'ready') {
                                  load();
                                  return;
                                }
                                context.push(
                                    Uri(path: '/assistant', queryParameters: {
                                  'paper_id': selected['paper_id'].toString(),
                                  'question':
                                      '请对照这版论文与导师反馈，列出需要修改的内容、缺少的证据和建议修改顺序。不要补造实验结果。'
                                }).toString());
                              }),
                        ])),
                ResearchPanel(
                    title: '这版稿件的导师反馈',
                    icon: Icons.chat_bubble_outline,
                    color: ResearchDesign.teal,
                    child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (objectRows(selected['feedback']).isEmpty)
                            const Text('暂时没有导师反馈。'),
                          for (final row in objectRows(selected['feedback']))
                            Container(
                                margin: const EdgeInsets.only(bottom: 12),
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                    color: (row['decision'] == 'accepted'
                                            ? ResearchDesign.teal
                                            : ResearchDesign.orange)
                                        .withValues(alpha: .08),
                                    borderRadius: BorderRadius.circular(12)),
                                child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.stretch,
                                    children: [
                                      Text(
                                          '${row['mentor_name'] ?? '导师'} · ${row['decision'] == 'accepted' ? '通过' : '需要修改'}',
                                          style: const TextStyle(
                                              fontWeight: FontWeight.w700)),
                                      Text(row['content']?.toString() ?? ''),
                                      Text(beijingTime(row['created_at']),
                                          style: const TextStyle(
                                              color: ResearchDesign.muted,
                                              fontSize: 12))
                                    ])),
                          if (mentor) ...[
                            const Text('反馈只针对当前选中的稿件。'),
                            const SizedBox(height: 10),
                            DropdownButtonFormField<String>(
                                key: ValueKey(selectedRevision),
                                initialValue: decision,
                                isExpanded: true,
                                decoration:
                                    const InputDecoration(labelText: '审阅结论'),
                                items: const [
                                  DropdownMenuItem(
                                      value: 'changes_requested',
                                      child: Text('需要修改')),
                                  DropdownMenuItem(
                                      value: 'accepted', child: Text('通过'))
                                ],
                                onChanged: busy
                                    ? null
                                    : (v) => setState(() => decision = v!)),
                            const SizedBox(height: 12),
                            TextField(
                                key: const ValueKey('thesis-feedback'),
                                controller: feedback,
                                enabled: !busy,
                                minLines: 3,
                                maxLines: 6,
                                maxLength: 10000,
                                decoration: const InputDecoration(
                                    labelText: '具体意见',
                                    hintText: '指出要修改的位置、原因和期望的修改方式')),
                            FilledButton(
                                onPressed: busy ? null : submitFeedback,
                                child: const Text('提交导师反馈')),
                          ]
                        ]))
              ]
            ]
          ],
          if (busy) const LinearProgressIndicator(),
          if (error != null) ...[
            Text(error!, style: const TextStyle(color: ResearchDesign.red)),
            if (!accessLost)
              TextButton(
                  onPressed: busy ? null : load,
                  child: const Text('刷新最新稿件（保留填写内容）'))
          ],
          if (initialScope == null && !accessLost)
            const ResearchEmpty('请登录并选择已绑定的学生。'),
        ]));
  }
}
