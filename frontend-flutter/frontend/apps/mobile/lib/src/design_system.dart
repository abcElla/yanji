import 'package:flutter/material.dart';

abstract final class ResearchDesign {
  static const ink = Color(0xFF171A2B);
  static const purple = Color(0xFF5740F7);
  static const teal = Color(0xFF42AE94);
  static const orange = Color(0xFFF09A43);
  static const blue = Color(0xFF6883E8);
  static const red = Color(0xFFED7779);
  static const muted = Color(0xFF737789);
  static const background = Color(0xFFF7F8FC);
  static const border = Color(0xFFE6E8F0);
  static ThemeData theme() => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
            seedColor: purple, primary: purple, surface: Colors.white),
        scaffoldBackgroundColor: background,
        textTheme: ThemeData.light()
            .textTheme
            .apply(bodyColor: ink, displayColor: ink)
            .copyWith(
                headlineSmall: const TextStyle(
                    fontSize: 23,
                    fontWeight: FontWeight.w700,
                    height: 1.35,
                    color: ink),
                titleLarge: const TextStyle(
                    fontSize: 18, fontWeight: FontWeight.w700, color: ink),
                bodyMedium:
                    const TextStyle(fontSize: 14, height: 1.6, color: ink)),
        appBarTheme: const AppBarTheme(
            backgroundColor: background,
            foregroundColor: ink,
            elevation: 0,
            scrolledUnderElevation: 0,
            centerTitle: false),
        cardTheme: const CardThemeData(
            color: Colors.white,
            elevation: 0,
            margin: EdgeInsets.symmetric(vertical: 6),
            shape: RoundedRectangleBorder(
                side: BorderSide(color: border),
                borderRadius: BorderRadius.all(Radius.circular(16)))),
        dividerTheme: const DividerThemeData(color: border, thickness: 1),
        listTileTheme: const ListTileThemeData(
            contentPadding: EdgeInsets.symmetric(horizontal: 18, vertical: 6)),
        filledButtonTheme: FilledButtonThemeData(
            style: FilledButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 20, vertical: 15),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12)),
                textStyle: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w600))),
        outlinedButtonTheme: OutlinedButtonThemeData(
            style: OutlinedButton.styleFrom(
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
                side: const BorderSide(color: border),
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12)))),
        navigationBarTheme: const NavigationBarThemeData(
            backgroundColor: Colors.white,
            indicatorColor: Color(0xFFF0EDFF),
            height: 72),
        inputDecorationTheme: InputDecorationTheme(
            filled: true,
            fillColor: Colors.white,
            contentPadding: const EdgeInsets.all(16),
            border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: const BorderSide(color: border)),
            enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
                borderSide: const BorderSide(color: border))),
      );
}

class ResearchHero extends StatelessWidget {
  const ResearchHero(
      {required this.title,
      required this.description,
      required this.action,
      required this.onTap,
      super.key});
  final String title, description, action;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: const Color(0xFFE1DAFF)),
            gradient: const LinearGradient(
                colors: [Color(0xFFF0EDFF), Color(0xFFFCFBFF)])),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Row(children: [
            Icon(Icons.auto_awesome, color: ResearchDesign.purple, size: 18),
            SizedBox(width: 8),
            Text('AI 科研导师',
                style: TextStyle(
                    color: ResearchDesign.purple, fontWeight: FontWeight.w600))
          ]),
          const SizedBox(height: 10),
          Text(title,
              style: const TextStyle(
                  fontSize: 18,
                  height: 1.4,
                  color: ResearchDesign.ink,
                  fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          Text(description,
              style: const TextStyle(color: ResearchDesign.muted, height: 1.7)),
          const SizedBox(height: 16),
          TextButton(
              onPressed: onTap,
              style: TextButton.styleFrom(
                  foregroundColor: ResearchDesign.purple,
                  backgroundColor: const Color(0xFFE9E3FF)),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                Text(action),
                const SizedBox(width: 12),
                const Icon(Icons.arrow_forward, size: 16)
              ])),
        ]),
      );
}

class ResearchIcon extends StatelessWidget {
  const ResearchIcon(this.icon,
      {this.color = ResearchDesign.purple, super.key});
  final IconData icon;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
          color: color.withValues(alpha: .12),
          borderRadius: BorderRadius.circular(12)),
      child: Icon(icon, color: color, size: 21));
}

class ResearchPanel extends StatelessWidget {
  const ResearchPanel(
      {required this.title,
      required this.child,
      this.icon = Icons.description_outlined,
      this.color = ResearchDesign.purple,
      this.action,
      super.key});
  final String title;
  final Widget child;
  final Widget? action;
  final IconData icon;
  final Color color;
  @override
  Widget build(BuildContext context) => Card(
      child: Padding(
          padding: const EdgeInsets.all(16),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              ResearchIcon(icon, color: color),
              const SizedBox(width: 12),
              Expanded(
                  child: Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(title,
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.w700)))),
              if (action != null) action!
            ]),
            const SizedBox(height: 12),
            child
          ])));
}

class ResearchEmpty extends StatelessWidget {
  const ResearchEmpty(this.message,
      {this.icon = Icons.inbox_outlined, super.key});
  final String message;
  final IconData icon;
  @override
  Widget build(BuildContext context) => Card(
      child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(children: [
            ResearchIcon(icon, color: ResearchDesign.blue),
            const SizedBox(height: 12),
            Text(message,
                textAlign: TextAlign.center,
                style: const TextStyle(color: ResearchDesign.muted))
          ])));
}
