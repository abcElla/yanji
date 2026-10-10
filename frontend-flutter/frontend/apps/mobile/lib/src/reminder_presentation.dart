List<Map<String, dynamic>> readableRisks(Map dashboard) {
  final tasks = <String, Map>{};
  for (final value
      in dashboard['tasks'] is List ? dashboard['tasks'] as List : const []) {
    if (value is Map && value['id'] is String) tasks[value['id']] = value;
  }
  final seen = <String>{};
  final rows = <Map<String, dynamic>>[];
  for (final value
      in dashboard['risks'] is List ? dashboard['risks'] as List : const []) {
    if (value is! Map) continue;
    final task = value['task_id'];
    final key = task is String ? task : '${value['type']}:${value['message']}';
    if (!seen.add(key)) continue;
    final title = value['task_title'] ?? tasks[task]?['title'];
    rows.add({
      ...Map<String, dynamic>.from(value),
      'title': title is String && title.trim().isNotEmpty ? title : '有任务需要处理'
    });
  }
  return rows;
}
