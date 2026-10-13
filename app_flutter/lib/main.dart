import 'package:flutter/material.dart';
import 'core/auth.dart';
import 'pages/home_page.dart';
import 'pages/login_page.dart';

final navigatorKey = GlobalKey<NavigatorState>();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Auth.load();
  // 401 全局弹回登录页(ApiClient 触发)
  Auth.onUnauthorized = () {
    navigatorKey.currentState?.pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const LoginPage()), (_) => false);
  };
  runApp(const NblmApp());
}

/// 去掉 overscroll 拉伸/辉光指示,内容不被形变
class _NoStretchScrollBehavior extends MaterialScrollBehavior {
  const _NoStretchScrollBehavior();

  @override
  Widget buildOverscrollIndicator(
          BuildContext context, Widget child, ScrollableDetails details) =>
      child;
}

class NblmApp extends StatelessWidget {
  const NblmApp({super.key});

  @override
  Widget build(BuildContext context) {
    // 品牌色:暖棕(鹿匠),M3 动态派生整套配色
    final scheme = ColorScheme.fromSeed(seedColor: const Color(0xFF8B5E3C));
    return MaterialApp(
      title: '鹿匠笔记',
      debugShowCheckedModeBanner: false,
      navigatorKey: navigatorKey,
      // 全局去掉 Android 12+ 拉伸过度滚动效果,滚动时页面文字不再被拉伸变形
      scrollBehavior: const _NoStretchScrollBehavior(),
      theme: ThemeData(
        colorScheme: scheme,
        useMaterial3: true,
        cardTheme: CardThemeData(
          elevation: 0,
          margin: EdgeInsets.zero,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
            side: BorderSide(color: scheme.outlineVariant),
          ),
        ),
        appBarTheme: const AppBarTheme(scrolledUnderElevation: 0.5),
      ),
      home: Auth.token != null ? const HomePage() : const LoginPage(),
    );
  }
}
