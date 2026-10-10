import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'data.dart';
import 'models.dart';
import 'design_system.dart';
import 'agent_result_cards.dart';
import 'ai_action.dart';
import 'theses.dart' show thesisScope, ingestLabel;

class PaperReaderScreen extends ConsumerStatefulWidget {
  const PaperReaderScreen({required this.paperId, super.key});
  final String paperId;
  @override
  ConsumerState<PaperReaderScreen> createState() => _PaperReaderScreenState();
}

class _PaperReaderScreenState extends ConsumerState<PaperReaderScreen> {
  Map<String, dynamic>? paper;
  List<Map<String, dynamic>> notes = [];
  String? initialScope, error, cursor;
  bool busy = false, accessLost = false;
  int epoch = 0;
  @override
  void initState() {
    super.initState();
    initialScope = thesisScope(ref);
    Future.microtask(load);
  }

  @override
  void dispose() {
    epoch++;
    super.dispose();
  }

  bool valid(int ticket) =>
      mounted &&
      !accessLost &&
      ticket == epoch &&
      initialScope == thesisScope(ref);
  void clear() {
    epoch++;
    setState(() {
      paper = null;
      notes = [];
      cursor = null;
      busy = false;
      accessLost = true;
      error = '访问范围已改变，请返回资料重新选择。';
    });
  }

  Future<void> load({bool more = false}) async {
    if (busy || accessLost || initialScope == null) return;
    final ticket = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final api = ref.read(apiClientProvider);
      final data = more ? paper! : await api.get('/papers/${widget.paperId}');
      if (!valid(ticket)) return;
      final rows = await api.get('/notes', query: {
        'paper_id': widget.paperId,
        'student_id': initialScope!.split('|').last,
        if (more && cursor != null) 'cursor': cursor,
      });
      if (!valid(ticket)) return;
      if (rows['items'] is! List) throw const ApiFailure('阅读笔记暂时无法读取，请重试');
      final nextCursor = rows['next_cursor'];
      if (nextCursor != null &&
          (nextCursor is! String ||
              nextCursor.isEmpty ||
              (more && nextCursor == cursor))) {
        throw const ApiFailure('阅读笔记分页暂时无法读取，请重试');
      }
      setState(() {
        paper = data;
        final loaded = objectRows(rows['items']);
        if (more) {
          final ids = notes.map((e) => e['id']).toSet();
          notes.addAll(loaded.where((e) => ids.add(e['id'])));
        } else {
          notes = loaded;
        }
        cursor = nextCursor as String?;
      });
    } catch (e) {
      if (valid(ticket)) {
        if (isAccessFailure(e)) {
          clear();
        } else {
          setState(() => error = e.toString());
        }
      }
    } finally {
      if (valid(ticket)) setState(() => busy = false);
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
    final mentor = ref.watch(roleProvider) == UserRole.mentor;
    return Scaffold(
        appBar: AppBar(title: const Text('文献详情'), actions: [
          IconButton(
              tooltip: '刷新资料',
              onPressed: busy || accessLost ? null : load,
              icon: const Icon(Icons.refresh))
        ]),
        body: ListView(padding: const EdgeInsets.all(20), children: [
          if (paper != null) ...[
            ResearchPanel(
                title: paper!['title']?.toString() ?? '文献',
                icon: Icons.menu_book_outlined,
                color: ResearchDesign.teal,
                child: Text(ingestLabel(paper!['ingest_status']))),
            AiActionPanel(
                title: 'AI 文献导读',
                kind: 'paper_guide',
                paperId: widget.paperId,
                enabled: paper!['ingest_status'] == 'ready',
                description: paper!['ingest_status'] == 'ready'
                    ? '结合原文梳理核心观点、方法和待解问题。确认建议后可形成阅读笔记。'
                    : '请等待材料解析完成后再生成导读。',
                action: '生成导读'),
            AiActionPanel(
                title: 'AI 深入解读',
                kind: 'research_qa',
                paperId: widget.paperId,
                enabled: paper!['ingest_status'] == 'ready',
                question: '请结合这篇文献，解释关键方法、适用条件和局限，并说明与我的当前研究任务有什么关系。',
                action: '分析文献方法'),
            OutlinedButton.icon(
                onPressed: () => context.push(Uri(
                        path: '/assistant',
                        queryParameters: {
                          'paper_id': widget.paperId,
                          'question': '我想针对这篇文献继续提问：'
                        }).toString()),
                icon: const Icon(Icons.chat_bubble_outline),
                label: const Text('围绕这篇文献提问')),
            const SizedBox(height: 12),
            ResearchPanel(
                title: mentor ? '学生的阅读笔记' : '我的阅读笔记',
                icon: Icons.edit_note,
                color: ResearchDesign.blue,
                child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (notes.isEmpty) const Text('还没有可查看的阅读笔记。'),
                      for (final row in notes)
                        ListTile(
                            contentPadding: EdgeInsets.zero,
                            leading: const ResearchIcon(Icons.note_alt_outlined,
                                color: ResearchDesign.blue),
                            title: Text(stringRows(row['claims']).firstOrNull ??
                                '阅读笔记'),
                            subtitle: Text(
                                row['status'] == 'confirmed' ? '已确认' : '草稿'),
                            trailing: const Icon(Icons.chevron_right),
                            onTap: () async {
                              await Navigator.push(
                                  context,
                                  MaterialPageRoute(
                                      builder: (_) => ReadingNoteScreen(
                                          paperId: widget.paperId,
                                          noteId: row['id'].toString())));
                              if (mounted) await load();
                            }),
                      if (!mentor)
                        TextButton.icon(
                            onPressed: busy
                                ? null
                                : () async {
                                    await Navigator.push(
                                        context,
                                        MaterialPageRoute(
                                            builder: (_) => ReadingNoteScreen(
                                                paperId: widget.paperId)));
                                    if (mounted) await load();
                                  },
                            icon: const Icon(Icons.add),
                            label: const Text('写阅读笔记')),
                      if (cursor != null)
                        TextButton.icon(
                            onPressed: busy ? null : () => load(more: true),
                            icon: const Icon(Icons.expand_more),
                            label: const Text('加载更多笔记')),
                    ])),
          ],
          if (initialScope == null) ...[
            const ResearchEmpty('请先在资料页选择已绑定的学生，再查看阅读笔记。'),
            if (mentor)
              OutlinedButton.icon(
                  onPressed: () => context.go('/library'),
                  icon: const Icon(Icons.people_outline),
                  label: const Text('返回资料选择学生')),
          ],
          if (busy) const LinearProgressIndicator(),
          if (error != null) ...[
            Text(error!, style: const TextStyle(color: ResearchDesign.red)),
            if (!accessLost)
              TextButton(
                  onPressed: busy ? null : load, child: const Text('重新加载'))
          ],
        ]));
  }
}

class ReadingNoteScreen extends ConsumerStatefulWidget {
  const ReadingNoteScreen({required this.paperId, this.noteId, super.key});
  final String paperId;
  final String? noteId;
  @override
  ConsumerState<ReadingNoteScreen> createState() => _ReadingNoteScreenState();
}

class _ReadingNoteScreenState extends ConsumerState<ReadingNoteScreen> {
  final claims = TextEditingController(), questions = TextEditingController();
  Map<String, dynamic>? record;
  String? noteId, initialScope, error;
  bool busy = false,
      dirty = false,
      accessLost = false,
      conflict = false,
      detailVerified = false,
      filling = false;
  int epoch = 0;
  bool get mentor => ref.read(roleProvider) == UserRole.mentor;
  @override
  void initState() {
    super.initState();
    initialScope = thesisScope(ref);
    noteId = widget.noteId;
    for (final c in [claims, questions]) {
      c.addListener(() {
        if (!filling && mounted) setState(() => dirty = true);
      });
    }
    if (noteId != null) Future.microtask(load);
  }

  @override
  void dispose() {
    epoch++;
    claims.dispose();
    questions.dispose();
    super.dispose();
  }

  bool valid(int ticket) =>
      mounted &&
      !accessLost &&
      ticket == epoch &&
      initialScope != null &&
      initialScope == thesisScope(ref);
  void clear() {
    epoch++;
    filling = true;
    claims.clear();
    questions.clear();
    filling = false;
    setState(() {
      record = null;
      noteId = null;
      dirty = false;
      busy = false;
      accessLost = true;
      error = '访问范围已改变，请返回资料重新选择。';
    });
  }

  void fill(Map<String, dynamic> data) {
    filling = true;
    record = data;
    noteId = data['id']?.toString();
    claims.text = stringRows(data['claims']).join('\n');
    questions.text = stringRows(data['questions']).join('\n');
    filling = false;
    dirty = false;
    conflict = false;
    detailVerified = data['id'] is String && data['version'] is num;
  }

  Future<void> load() async {
    if (busy || accessLost || noteId == null) return;
    final ticket = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final data = await ref.read(apiClientProvider).get('/notes/$noteId');
      if (!valid(ticket)) return;
      if (data['paper_id'] != widget.paperId) {
        throw const ApiFailure('笔记与当前文献不匹配');
      }
      setState(() => fill(data));
    } catch (e) {
      if (valid(ticket)) {
        if (isAccessFailure(e)) {
          clear();
        } else {
          setState(() => error = e.toString());
        }
      }
    } finally {
      if (valid(ticket)) setState(() => busy = false);
    }
  }

  List<String> lines(String text) =>
      text.split('\n').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
  Future<void> save() async {
    if (busy ||
        mentor ||
        conflict ||
        accessLost ||
        initialScope == null ||
        (noteId != null && !detailVerified)) {
      return;
    }
    if (claims.text.trim().isEmpty) {
      setState(() => error = '请至少写下一条阅读结论');
      return;
    }
    if (lines(claims.text).length > 200 || lines(questions.text).length > 200) {
      setState(() => error = '每个栏目最多填写 200 条');
      return;
    }
    final ticket = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final api = ref.read(apiClientProvider);
      final body = {
        'claims': lines(claims.text),
        'questions': lines(questions.text),
        'source_refs': record?['source_refs'] ?? [],
        if (noteId == null) 'paper_id': widget.paperId,
        if (noteId != null) 'expected_version': record!['version']
      };
      final saved = await api.write(
          noteId == null ? '/notes' : '/notes/$noteId', body,
          method: noteId == null ? 'POST' : 'PATCH');
      if (!valid(ticket)) return;
      noteId = noteId ?? saved['id']?.toString();
      detailVerified = false;
      if (noteId == null) throw const ApiFailure('保存响应缺少笔记信息，请刷新资料检查是否已保存');
      final actual = await api.get('/notes/$noteId');
      if (valid(ticket)) setState(() => fill(actual));
    } catch (e) {
      if (valid(ticket)) {
        if (isAccessFailure(e)) {
          clear();
          return;
        }
        setState(() {
          conflict = e is ApiFailure && e.status == 409;
          error =
              conflict ? '笔记已更新，填写内容已保留。请复制所需内容后刷新最新笔记。' : '保存未完成，填写内容已保留：$e';
        });
      }
    } finally {
      if (valid(ticket)) setState(() => busy = false);
    }
  }

  Future<void> confirm() async {
    if (busy ||
        mentor ||
        accessLost ||
        dirty ||
        conflict ||
        !detailVerified ||
        record?['status'] != 'draft') {
      return;
    }
    final ticket = epoch;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final api = ref.read(apiClientProvider);
      await api.post(
          '/notes/$noteId/confirm', {'expected_version': record!['version']});
      if (!valid(ticket)) return;
      final actual = await api.get('/notes/$noteId');
      if (valid(ticket)) setState(() => fill(actual));
    } catch (e) {
      if (valid(ticket)) {
        if (isAccessFailure(e)) {
          clear();
        } else {
          setState(() => error = '确认未完成，填写内容已保留：$e');
        }
      }
    } finally {
      if (valid(ticket)) setState(() => busy = false);
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
    final readOnly = ref.watch(roleProvider) == UserRole.mentor;
    final enabled = initialScope != null && !accessLost && !busy;
    return Scaffold(
        appBar: AppBar(title: const Text('阅读笔记')),
        body: ListView(padding: const EdgeInsets.all(20), children: [
          if (!accessLost) ...[
            ResearchPanel(
                title: record?['status'] == 'confirmed' ? '已确认的阅读记录' : '整理阅读收获',
                icon: Icons.edit_note,
                color: ResearchDesign.blue,
                child: Text(readOnly
                    ? '这是学生保存的阅读结论与待解问题。你可以对照原文查看，再用 AI 检查理解是否有遗漏。'
                    : '结论与问题分别记录，每行一条。保存后核对，再确认供周报与 AI 使用。')),
            ResearchPanel(
                title: '重点结论',
                icon: Icons.lightbulb_outline,
                color: ResearchDesign.teal,
                child: readOnly
                    ? Text(claims.text.isEmpty ? '暂未填写阅读结论。' : claims.text,
                        style: const TextStyle(height: 1.7))
                    : TextField(
                        key: const ValueKey('note-claims'),
                        controller: claims,
                        enabled: enabled,
                        readOnly: readOnly,
                        minLines: 3,
                        maxLines: 8,
                        maxLength: 20000,
                        decoration: const InputDecoration(
                            labelText: '阅读结论', hintText: '写下方法、结果和适用条件，每行一条'))),
            ResearchPanel(
                title: '待解问题',
                icon: Icons.help_outline,
                color: ResearchDesign.orange,
                child: readOnly
                    ? Text(
                        questions.text.isEmpty ? '暂未记录待解问题。' : questions.text,
                        style: const TextStyle(height: 1.7))
                    : TextField(
                        controller: questions,
                        enabled: enabled,
                        readOnly: readOnly,
                        minLines: 2,
                        maxLines: 6,
                        maxLength: 20000,
                        decoration: const InputDecoration(
                            labelText: '还有哪些疑问',
                            hintText: '记录需要继续查证或向导师请教的问题'))),
            if (objectRows(record?['source_refs']).isNotEmpty)
              ResearchPanel(
                  title: '原文依据',
                  icon: Icons.menu_book_outlined,
                  color: ResearchDesign.blue,
                  child: SourceReferences(record!['source_refs'])),
            if (!readOnly) ...[
              FilledButton(
                  onPressed:
                      enabled && !conflict && (noteId == null || detailVerified)
                          ? save
                          : null,
                  child: const Text('保存阅读笔记')),
              if (dirty && record != null) const Text('有未保存的修改，请先保存并核对。'),
              OutlinedButton(
                  onPressed: enabled &&
                          !dirty &&
                          !conflict &&
                          detailVerified &&
                          record?['status'] == 'draft'
                      ? confirm
                      : null,
                  child: const Text('确认阅读笔记')),
            ],
            AiActionPanel(
                title: 'AI 对照阅读笔记',
                kind: 'research_qa',
                paperId: widget.paperId,
                enabled: enabled && record?['status'] == 'confirmed' && !dirty,
                description: record?['status'] == 'confirmed' && !dirty
                    ? '结合原文和已确认笔记，检查理解是否有遗漏。'
                    : '请先保存并确认笔记，再让 AI 对照原文分析。',
                question: '请对照这篇文献与我已确认的阅读笔记，指出理解中的遗漏、限制条件和下一步需要查证的问题。',
                action: '分析已确认笔记'),
          ],
          if (busy) const LinearProgressIndicator(),
          if (error != null) ...[
            Text(error!, style: const TextStyle(color: ResearchDesign.red)),
            if (!accessLost && noteId != null)
              TextButton(
                  onPressed: busy ? null : load, child: const Text('刷新最新笔记'))
          ],
        ]));
  }
}
