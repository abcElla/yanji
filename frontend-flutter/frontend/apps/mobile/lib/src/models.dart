enum UserRole { student, mentor }

enum TaskStatus { todo, inProgress, blocked, submitted, completed, unsupported }

class ResearchTask {
  const ResearchTask({
    required this.id,
    required this.title,
    required this.type,
    required this.status,
    required this.dueLabel,
    required this.description,
    this.blockingReason,
    this.dependencyIds = const [],
    this.criteria = const [],
    this.version = 1,
    this.stageId,
    this.createdBy,
  });

  final String id;
  final String? stageId;
  final String? createdBy;
  final String title;
  final String type;
  final TaskStatus status;
  final String dueLabel;
  final String description;
  final String? blockingReason;
  final List<String> dependencyIds;
  final List<String> criteria;
  final int version;

  factory ResearchTask.fromJson(Map<String, dynamic> json) => ResearchTask(
        id: json['id'] as String,
        stageId: json['stage_id'] as String?,
        createdBy: json['created_by'] as String?,
        title: json['title'] as String? ?? '',
        type: const {
              'reading': '阅读',
              'experiment': '实验',
              'writing': '写作',
              'other': '其他'
            }[json['type']] ??
            '其他',
        status: const {
              'todo': TaskStatus.todo,
              'in_progress': TaskStatus.inProgress,
              'blocked': TaskStatus.blocked,
              'submitted': TaskStatus.submitted,
              'completed': TaskStatus.completed
            }[json['status']] ??
            TaskStatus.unsupported,
        dueLabel: json['due_at'] == null
            ? '未设置截止日期'
            : '${json['due_at'].toString().split('T').first} 截止',
        description: json['description'] as String? ?? '',
        blockingReason: json['blocking_reason'] as String?,
        dependencyIds: List<String>.from(json['dependency_ids'] ?? []),
        criteria: List<String>.from(json['acceptance_criteria'] ?? []),
        version: (json['version'] as num?)?.toInt() ?? 1,
      );
}

class EvidenceRef {
  const EvidenceRef({
    required this.title,
    required this.location,
    required this.quote,
  });

  final String title;
  final String location;
  final String quote;
}

class StudentSummary {
  const StudentSummary({
    required this.id,
    required this.name,
    required this.stage,
    required this.progress,
    required this.risk,
  });

  final String id;
  final String name;
  final String stage;
  final double progress;
  final String risk;
}

class AppNotice {
  const AppNotice(
      {required this.title, required this.body, required this.time});
  final String title;
  final String body;
  final String time;
}
