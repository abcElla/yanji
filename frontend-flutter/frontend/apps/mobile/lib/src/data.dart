import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:uuid/uuid.dart';
import 'models.dart';

const apiBaseUrl = String.fromEnvironment('API_BASE_URL',
    defaultValue: 'http://192.168.8.230:8000');
final apiClientProvider = Provider<ApiClient>((ref) {
  final api = ApiClient(apiBaseUrl);
  api.onSessionExpired = () {
    ref.read(sessionProvider.notifier).expire();
  };
  ref.onDispose(() => api.dio.close());
  return api;
});

class ApiFailure implements Exception {
  const ApiFailure(this.message,
      {this.code = '',
      this.status,
      this.retryable = false,
      this.details = const {},
      this.requestId});
  final String message;
  final String code;
  final int? status;
  final bool retryable;
  final Map<String, dynamic> details;
  final String? requestId;
  @override
  String toString() => message;
}

bool isAccessFailure(Object error) =>
    error is ApiFailure &&
    ([401, 403, 404].contains(error.status) ||
        error.code.startsWith('SESSION_') ||
        error.code.contains('FORBIDDEN') ||
        error.code.contains('NOT_FOUND'));

Future<void> verifyReadableRunSources(ApiClient api, dynamic data) async {
  final refs = <String, Map<String, dynamic>>{};
  void collect(dynamic node) {
    if (node is Map) {
      if (node['source_id'] is String &&
          node['source_revision'] is num &&
          node['locator'] is Map) {
        final key =
            '${node['source_id']}:${node['source_revision']}:${jsonEncode(node['locator'])}';
        refs[key] = Map<String, dynamic>.from(node);
      }
      for (final child in node.values) {
        collect(child);
      }
    } else if (node is List) {
      for (final child in node) {
        collect(child);
      }
    }
  }

  collect(data);
  for (final reference in refs.values) {
    try {
      await api.get('/sources/${reference['source_id']}', query: {
        'revision': reference['source_revision'],
        'locator': jsonEncode(reference['locator']),
      });
    } on ApiFailure catch (e) {
      if (isAccessFailure(e)) {
        throw const ApiFailure('本次分析包含当前账号无权查看的材料，请使用当前身份重新发起分析',
            status: 403, code: 'RUN_SOURCE_FORBIDDEN');
      }
      rethrow;
    }
  }
}

class ApiClient {
  ApiClient(String url)
      : dio = Dio(BaseOptions(
          baseUrl: '${url.replaceFirst(RegExp(r'/+$'), '')}/api/v1',
          connectTimeout: const Duration(seconds: 10),
          receiveTimeout: const Duration(seconds: 30),
          headers: const {
            'X-Client-Platform': 'android',
            'X-App-Version': '0.1.0',
            'X-App-Build': '1'
          },
        ));
  final Dio dio;
  String? userScope;
  void Function()? onSessionExpired;
  void setAccessToken(String? token) {
    if (token == null) {
      dio.options.headers.remove('Authorization');
    } else {
      dio.options.headers['Authorization'] = 'Bearer $token';
    }
  }

  Future<Map<String, dynamic>> get(String path,
          {Map<String, dynamic>? query}) =>
      request('GET', path, query: query);
  Future<Map<String, dynamic>> post(String path,
          [Map<String, dynamic>? body]) =>
      request('POST', path, body: body);
  Future<Map<String, dynamic>> request(String method, String path,
      {dynamic body, Map<String, dynamic>? query, String? key}) async {
    final data =
        await requestData(method, path, body: body, query: query, key: key);
    if (data is! Map) {
      throw const ApiFailure('后端返回的数据格式不符合接口约定');
    }
    return Map<String, dynamic>.from(data);
  }

  Future<dynamic> requestData(String method, String path,
      {dynamic body, Map<String, dynamic>? query, String? key}) async {
    try {
      final response = await dio.request(path,
          data: body,
          queryParameters: query,
          options: Options(
              method: method,
              headers: key == null ? null : {'Idempotency-Key': key}));
      if (response.data is! Map || response.data['code'] is! num) {
        throw const ApiFailure('后端响应缺少 code/data/message，请确认接口版本');
      }
      final envelope = Map<String, dynamic>.from(response.data as Map);
      if (envelope['code'] != 0) {
        final failure = _failure(envelope, response.statusCode,
            response.headers.value('X-Request-Id'));
        if (failure.code.startsWith('SESSION_') && path != '/auth/login') {
          onSessionExpired?.call();
        }
        throw failure;
      }
      return envelope['data'];
    } on DioException catch (e) {
      if (e.response?.statusCode == 401 && path != '/auth/login') {
        onSessionExpired?.call();
      }
      if (e.response?.data is Map) {
        throw _failure(Map<String, dynamic>.from(e.response!.data),
            e.response?.statusCode, e.response?.headers.value('X-Request-Id'));
      }
      throw ApiFailure(
          e.response == null
              ? '无法连接后端，请检查 EasyTier 连接后重试'
              : '请求失败（HTTP ${e.response?.statusCode}）',
          status: e.response?.statusCode);
    }
  }

  ApiFailure _failure(
      Map<String, dynamic> envelope, int? status, String? requestId) {
    final error = envelope['data'] is Map ? envelope['data'] as Map : const {};
    return ApiFailure(envelope['message']?.toString() ?? '请求失败，请重试',
        code: error['error_code']?.toString() ?? '',
        status: status,
        retryable: error['retryable'] == true,
        details: error['details'] is Map
            ? Map<String, dynamic>.from(error['details'])
            : const {},
        requestId: requestId);
  }

  Future<List<Map<String, dynamic>>> list(String path,
      {Map<String, dynamic>? query}) async {
    final collection = const {
          '/tasks': 'tasks',
          '/experiments': 'experiments',
          '/students': 'students',
          '/papers': 'papers',
          '/theses': 'items',
          '/weekly-reports': 'reports',
          '/notifications': 'items',
          '/proposals': 'proposals',
          '/stages': 'stages',
          '/mentors': 'mentors',
          '/mentor-applications/pending': 'applications',
        }[path] ??
        (path.endsWith('/events') ? 'events' : null);
    if (collection == null) throw const ApiFailure('未配置该接口的列表字段');
    final items = <Map<String, dynamic>>[];
    String? cursor;
    final seen = <String>{};
    do {
      final page = await get(path, query: {
        ...?query,
        if (!const ['/proposals', '/mentor-applications/pending']
            .contains(path))
          'limit': 100,
        if (cursor != null) 'cursor': cursor
      });
      // The deployed notification route uses `notifications`; the published
      // contract uses `items`. Keep both shapes while the server is aligned.
      final pageCollection = path == '/notifications' &&
              page[collection] == null &&
              page['notifications'] is List
          ? 'notifications'
          : collection;
      if (page[pageCollection] is! List) {
        throw ApiFailure('后端列表缺少 $collection 字段');
      }
      items.addAll((page[pageCollection] as List)
          .map((v) => Map<String, dynamic>.from(v as Map)));
      cursor = page['next_cursor'] as String?;
      if (cursor != null && !seen.add(cursor)) {
        throw const ApiFailure('后端分页游标重复，请联系后端检查');
      }
    } while (cursor != null);
    return items;
  }

  Future<List<Map<String, dynamic>>> runEvents(String id) async {
    final events = <Map<String, dynamic>>[];
    var seq = 0;
    while (true) {
      final page = await get('/agent-runs/$id/events',
          query: {'after_seq': seq, 'limit': 100});
      if (page['events'] is! List) throw const ApiFailure('后端进度缺少 events 字段');
      final rows = (page['events'] as List)
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
      events.addAll(rows);
      if (rows.isEmpty) break;
      final next = (page['next_seq'] as num?)?.toInt();
      if (next == null) throw const ApiFailure('后端进度缺少 next_seq 字段');
      if (next <= seq) throw const ApiFailure('后端事件序号未推进，请重试');
      seq = next;
    }
    return events;
  }

  Future<Map<String, dynamic>> write(String path, Map<String, dynamic> body,
      {String method = 'POST'}) async {
    // Only these implemented routes support replaying the original result.
    final supportsReplay = path == '/agent-runs' ||
        path == '/theses' ||
        RegExp(r'^/theses/[^/]+/revisions(?:/[^/]+/feedback)?$')
            .hasMatch(path) ||
        path == '/guidance' ||
        RegExp(r'^/(tasks/[^/]+/transition|stages/[^/]+/submit|weekly-reports/[^/]+/submit|agent-runs/[^/]+/retry|proposals/[^/]+/approve)$')
            .hasMatch(path);
    if (!supportsReplay) return request(method, path, body: body);
    const storage = FlutterSecureStorage();
    final intent = sha256.convert(utf8.encode(
        '${dio.options.baseUrl}:$userScope:$method:$path:${jsonEncode(body)}'));
    final slot = 'pending_$intent';
    final key = await storage.read(key: slot) ?? const Uuid().v4();
    await storage.write(key: slot, value: key);
    late final Map<String, dynamic> result;
    try {
      result = await request(method, path, body: body, key: key);
    } on ApiFailure catch (e) {
      if (e.status != null && e.status! >= 400 && e.status! < 500) {
        await storage.delete(key: slot);
      }
      rethrow;
    }
    await storage.delete(key: slot);
    return result;
  }
}

class Session {
  const Session(
      {required this.user,
      required this.roles,
      this.memberships = const [],
      this.mentorships = const [],
      this.onboardingStatus = 'choose_mentor',
      this.collaborationEnabled = false});
  final Map<String, dynamic> user;
  final List<String> roles;
  final List<dynamic> memberships;
  final List<dynamic> mentorships;
  final String onboardingStatus;
  final bool collaborationEnabled;
  String get accountRole => user['account_role']?.toString() ?? '';
  String get id => user['id'].toString();
  // A mentor may lose A while retaining B. Identity alone is not an access scope.
  String get authorizationKey {
    dynamic canonical(dynamic value) {
      if (value is Map) {
        final keys = value.keys.map((k) => k.toString()).toList()..sort();
        return {for (final key in keys) key: canonical(value[key])};
      }
      if (value is List) {
        return value.map((v) => jsonEncode(canonical(v))).toList()..sort();
      }
      return value;
    }

    return sha256
        .convert(utf8.encode(jsonEncode(canonical({
          'id': id,
          'roles': roles,
          'enabled': collaborationEnabled,
          'memberships': memberships,
          'mentorships': mentorships,
        }))))
        .toString();
  }

  String get name =>
      user['display_name']?.toString() ?? user['username']?.toString() ?? '';
  bool get hasBusinessRole =>
      roles.contains('student') || roles.contains('mentor');
  factory Session.fromJson(Map<String, dynamic> data) {
    final user = Map<String, dynamic>.from(data['user'] as Map);
    return Session(
        user: user,
        roles: List<String>.from(
            data['allowed_roles'] ?? user['allowed_roles'] ?? []),
        memberships: data['memberships'] as List? ?? [],
        mentorships: data['mentorships'] as List? ?? [],
        onboardingStatus:
            data['onboarding_status'] as String? ?? 'choose_mentor',
        collaborationEnabled: data['collaboration_enabled'] == true);
  }
}

String? collaborationScope(
    Session? session, UserRole role, String? selectedStudent) {
  final student = role == UserRole.student ? session?.id : selectedStudent;
  return session?.collaborationEnabled == true && student != null
      ? '${session!.authorizationKey}|${role.name}|$student'
      : null;
}

final sessionProvider =
    StateNotifierProvider<SessionController, AsyncValue<Session?>>(
        (ref) => SessionController(ref));

class SessionController extends StateNotifier<AsyncValue<Session?>> {
  SessionController(this.ref) : super(const AsyncData(null));
  final Ref ref;
  static const storage = FlutterSecureStorage();
  Future<void> initialize() async {
    var installation = await storage.read(key: 'installation_id');
    installation ??= const Uuid().v4();
    await storage.write(key: 'installation_id', value: installation);
    ref.read(apiClientProvider).dio.options.headers['X-Installation-Id'] =
        installation;
  }

  Future<void> restore() async {
    state = const AsyncLoading();
    try {
      await initialize();
      final token = await storage.read(key: 'access_token');
      if (token == null) {
        state = const AsyncData(null);
        return;
      }
      ref.read(apiClientProvider).setAccessToken(token);
      await loadMe();
    } catch (e, st) {
      state = AsyncError(e, st);
    }
  }

  Future<void> loadMe() async {
    final me = await ref.read(apiClientProvider).get('/me');
    final session = Session.fromJson(me);
    final preferred = state.valueOrNull == null
        ? session.accountRole
        : ref.read(roleProvider).name;
    ref.read(roleProvider.notifier).state = session.roles.contains('mentor') &&
            (preferred == 'mentor' || !session.roles.contains('student'))
        ? UserRole.mentor
        : UserRole.student;
    ref.read(apiClientProvider).userScope = session.id;
    state = AsyncData(session);
  }

  Future<void> login(String username, String password) async {
    if (username.trim().isEmpty || password.isEmpty) {
      throw const ApiFailure('请输入账号和密码');
    }
    await initialize();
    final api = ref.read(apiClientProvider);
    final response = await api.post('/auth/login', {
      'username': username.trim(),
      'password': password,
      'installation_id': api.dio.options.headers['X-Installation-Id'],
      'device_label': '研迹 Android'
    });
    final token = response['access_token'] as String?;
    if (token == null || token.isEmpty) {
      throw const ApiFailure('登录响应缺少 access_token');
    }
    await storage.write(key: 'access_token', value: token);
    api.setAccessToken(token);
    try {
      await loadMe();
    } catch (_) {
      await storage.delete(key: 'access_token');
      api.setAccessToken(null);
      rethrow;
    }
    clearSelection(ref);
  }

  Future<void> logout() async {
    await ref.read(apiClientProvider).post('/auth/logout');
    await expire();
  }

  Future<void> expire() async {
    await storage.delete(key: 'access_token');
    ref.read(apiClientProvider).setAccessToken(null);
    ref.read(apiClientProvider).userScope = null;
    state = const AsyncData(null);
    clearSelection(ref);
  }
}

final roleProvider = StateProvider<UserRole>((ref) => UserRole.student);
final selectedStudentProvider = StateProvider<String?>((ref) => null);

final assistantStagesProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, String>((ref, studentId) async {
  final session = ref.watch(sessionProvider).valueOrNull;
  final role = ref.watch(roleProvider);
  final selected = ref.watch(selectedStudentProvider);
  if (session?.collaborationEnabled != true ||
      (role == UserRole.student && studentId != session!.id) ||
      (role == UserRole.mentor && studentId != selected)) {
    return [];
  }
  return ref
      .read(apiClientProvider)
      .list('/stages', query: {'student_id': studentId});
});
final selectedTaskProvider = StateProvider<ResearchTask?>((ref) {
  ref.watch(sessionProvider.select((s) => s.valueOrNull?.authorizationKey));
  ref.watch(selectedStudentProvider);
  ref.watch(roleProvider);
  return null;
});
final selectedReportProvider = StateProvider<String?>((ref) {
  ref.watch(sessionProvider.select((s) => s.valueOrNull?.authorizationKey));
  ref.watch(selectedStudentProvider);
  ref.watch(roleProvider);
  return null;
});
final agentInputProvider = StateProvider<Map<String, dynamic>?>((ref) {
  ref.watch(sessionProvider.select((s) => s.valueOrNull?.authorizationKey));
  ref.watch(roleProvider);
  ref.watch(selectedStudentProvider);
  return null;
});
final dashboardProvider = FutureProvider<Map<String, dynamic>>((ref) async {
  if (ref.watch(sessionProvider).valueOrNull == null) return {};
  return ref.watch(apiClientProvider).get('/dashboard', query: {
    'role': ref.watch(roleProvider).name,
  });
});
final studentsProvider =
    FutureProvider<List<Map<String, dynamic>>>((ref) async {
  if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true ||
      !ref.watch(sessionProvider).valueOrNull!.roles.contains('mentor')) {
    return [];
  }
  return ref.watch(apiClientProvider).list('/students');
});
final tasksProvider = FutureProvider<List<ResearchTask>>((ref) async {
  if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true) {
    return [];
  }
  final selected = ref.watch(selectedStudentProvider);
  if (ref.watch(roleProvider) == UserRole.mentor && selected == null) {
    return [];
  }
  final rows = await ref
      .watch(apiClientProvider)
      .list('/tasks', query: {if (selected != null) 'student_id': selected});
  return rows.map(ResearchTask.fromJson).toList();
});
final papersProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true) {
    return [];
  }
  return ref.watch(apiClientProvider).list('/papers');
});
final reportsProvider = FutureProvider<List<Map<String, dynamic>>>((ref) async {
  if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true) {
    return [];
  }
  final selected = ref.watch(selectedStudentProvider);
  if (ref.watch(roleProvider) == UserRole.mentor && selected == null) {
    return [];
  }
  return ref.watch(apiClientProvider).list('/weekly-reports', query: {
    if (selected != null) 'student_id': selected,
  });
});
final notificationsProvider =
    FutureProvider<List<Map<String, dynamic>>>((ref) async {
  if (ref.watch(sessionProvider).valueOrNull == null) return [];
  return ref.watch(apiClientProvider).list('/notifications');
});
final taskProvider =
    FutureProvider.family<ResearchTask, String>((ref, id) async {
  if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true) {
    throw const ApiFailure('导师批准入组申请后可使用任务功能');
  }
  return ResearchTask.fromJson(
      await ref.watch(apiClientProvider).get('/tasks/$id'));
});
final taskEventsProvider =
    FutureProvider.family<List<Map<String, dynamic>>, String>((ref, id) async {
  if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true) {
    return [];
  }
  return ref.watch(apiClientProvider).list('/tasks/$id/events');
});
final reportProvider =
    FutureProvider.family<Map<String, dynamic>, String>((ref, id) async {
  if (ref.watch(sessionProvider).valueOrNull?.collaborationEnabled != true) {
    throw const ApiFailure('导师批准入组申请后可使用周报功能');
  }
  return ref.watch(apiClientProvider).get('/weekly-reports/$id');
});
const mentorPageSize = int.fromEnvironment('MENTOR_PAGE_SIZE', defaultValue: 5);
final mentorsProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, ({String keyword, String? cursor})>(
        (ref, page) async {
  if (ref.watch(sessionProvider).valueOrNull == null) {
    return {'mentors': [], 'next_cursor': null};
  }
  return ref.watch(apiClientProvider).get('/mentors', query: {
    'keyword': page.keyword.trim(),
    'limit': mentorPageSize.clamp(1, 100),
    if (page.cursor != null) 'cursor': page.cursor,
  });
});
final myMentorApplicationProvider =
    FutureProvider<Map<String, dynamic>?>((ref) async {
  if (ref.watch(sessionProvider).valueOrNull == null) return null;
  final result =
      await ref.watch(apiClientProvider).get('/mentor-applications/me');
  return result['application'] is Map
      ? Map<String, dynamic>.from(result['application'])
      : null;
});
final pendingMentorApplicationsProvider =
    FutureProvider<List<Map<String, dynamic>>>((ref) async {
  final session = ref.watch(sessionProvider).valueOrNull;
  if (session?.collaborationEnabled != true ||
      !session!.roles.contains('mentor')) {
    return [];
  }
  return ref.watch(apiClientProvider).list('/mentor-applications/pending');
});
// Business providers watch sessionProvider and refresh automatically. The
// session controller must not invalidate its own dependants (Riverpod cycle).
void clearSelection(Ref ref) {
  ref.read(selectedStudentProvider.notifier).state = null;
  ref.read(selectedTaskProvider.notifier).state = null;
  ref.read(selectedReportProvider.notifier).state = null;
  ref.read(agentInputProvider.notifier).state = null;
}

String currentWeek() {
  final now = DateTime.now().toUtc().add(const Duration(hours: 8));
  final monday = DateTime.utc(now.year, now.month, now.day)
      .subtract(Duration(days: now.weekday - 1));
  return monday.toIso8601String().split('T').first;
}

Map<String, dynamic> reportItemPatch(
    Map<String, dynamic> report, String section, String text,
    {String? itemId}) {
  const sections = ['done_items', 'issues', 'next_plan', 'help_needed'];
  if (!sections.contains(section)) throw const ApiFailure('未知周报栏目');
  return {
    'expected_version': report['version'],
    for (final key in sections)
      key: [
        for (final item in report[key] as List? ?? [])
          if (key == section && itemId != null && item['id'] == itemId)
            {
              ...Map<String, dynamic>.from(item as Map),
              'text': text,
              'origin': ['agent', 'human_edited'].contains(item['origin'])
                  ? 'human_edited'
                  : 'human',
            }
          else
            item,
        if (key == section && itemId == null)
          {
            'id': const Uuid().v4(),
            'text': text,
            'origin': 'human',
            'source_refs': []
          },
      ],
  };
}
