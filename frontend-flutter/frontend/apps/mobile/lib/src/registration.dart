import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'data.dart';
import 'design_system.dart';

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});
  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final form = GlobalKey<FormState>();
  final username = TextEditingController();
  final displayName = TextEditingController();
  final password = TextEditingController();
  final confirmation = TextEditingController();
  String role = 'student';
  bool loading = false;
  String? error;

  @override
  void dispose() {
    for (final controller in [
      username,
      displayName,
      password,
      confirmation,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> register() async {
    if (loading || !form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final result = await ref.read(apiClientProvider).post('/auth/register', {
        'username': username.text.trim(),
        'password': password.text,
        'display_name': displayName.text.trim(),
        'role': role,
      });
      if (result['user_id'] == null) {
        throw const ApiFailure('注册响应缺少用户信息，请联系后端确认账号是否已创建');
      }
      if (mounted) {
        setState(() => loading = false);
        context.pop(username.text.trim());
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          loading = false;
          error = e.toString();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
        canPop: !loading,
        child: Scaffold(
          appBar: AppBar(title: const Text('注册账号')),
          body: SafeArea(
              child: Center(
                  child: SingleChildScrollView(
            padding: const EdgeInsets.all(28),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: Form(
                key: form,
                autovalidateMode: AutovalidateMode.onUserInteraction,
                child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const ResearchPanel(
                          title: '创建研迹账号',
                          icon: Icons.person_add_outlined,
                          color: ResearchDesign.blue,
                          child: Text('选择身份，开始整理自己的科研任务与材料。')),
                      const SizedBox(height: 8),
                      const Text('选择注册身份。学生注册后申请导师，导师注册后自动创建实验室。'),
                      const SizedBox(height: 24),
                      SegmentedButton<String>(
                          key: const Key('register_role'),
                          segments: const [
                            ButtonSegment(value: 'student', label: Text('学生')),
                            ButtonSegment(value: 'mentor', label: Text('导师')),
                          ],
                          selected: {role},
                          onSelectionChanged: loading
                              ? null
                              : (value) => setState(() => role = value.first)),
                      const SizedBox(height: 16),
                      TextFormField(
                          key: const Key('register_username'),
                          controller: username,
                          enabled: !loading,
                          decoration: const InputDecoration(
                              labelText: '账号', helperText: '4–32 位英文字母、数字或下划线'),
                          validator: (v) => RegExp(r'^[A-Za-z0-9_]{4,32}$')
                                  .hasMatch(v?.trim() ?? '')
                              ? null
                              : '账号须为 4–32 位字母、数字或下划线'),
                      const SizedBox(height: 16),
                      TextFormField(
                          key: const Key('register_name'),
                          controller: displayName,
                          enabled: !loading,
                          decoration: const InputDecoration(
                              labelText: '姓名', helperText: '1–50 个字符'),
                          validator: (v) => (v?.trim().isEmpty ?? true) ||
                                  v!.trim().runes.length > 50
                              ? '请填写 1–50 个字符的姓名'
                              : null),
                      const SizedBox(height: 16),
                      TextFormField(
                          key: const Key('register_password'),
                          controller: password,
                          enabled: !loading,
                          obscureText: true,
                          decoration: const InputDecoration(
                              labelText: '密码', helperText: '6–12 个字符'),
                          validator: (v) =>
                              (v?.runes.length ?? 0) < 6 || v!.runes.length > 12
                                  ? '密码长度须为 6–12 个字符'
                                  : null),
                      const SizedBox(height: 16),
                      TextFormField(
                          key: const Key('register_confirmation'),
                          controller: confirmation,
                          enabled: !loading,
                          obscureText: true,
                          decoration: const InputDecoration(labelText: '确认密码'),
                          validator: (v) =>
                              v != password.text || (v?.isEmpty ?? true)
                                  ? '两次输入的密码不一致'
                                  : null),
                      const SizedBox(height: 16),
                      if (error != null)
                        Padding(
                            padding: const EdgeInsets.only(top: 16),
                            child: Text(error!,
                                style: const TextStyle(color: Colors.red))),
                      const SizedBox(height: 24),
                      FilledButton(
                          key: const Key('register_submit'),
                          onPressed: loading ? null : register,
                          child: loading
                              ? const SizedBox.square(
                                  dimension: 22,
                                  child:
                                      CircularProgressIndicator(strokeWidth: 2))
                              : const Text('提交注册')),
                      TextButton(
                          onPressed: loading ? null : () => context.pop(),
                          child: const Text('已有账号？返回登录')),
                    ]),
              ),
            ),
          ))),
        ),
      );
}
