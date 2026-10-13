import 'package:shared_preferences/shared_preferences.dart';

class Auth {
  static const _kToken = 'token';
  static const _kUsername = 'username';
  static String? token;
  static String? username;

  /// 401 时由 main.dart 注入,用于全局弹回登录页
  static void Function()? onUnauthorized;

  static Future<void> load() async {
    final sp = await SharedPreferences.getInstance();
    token = sp.getString(_kToken);
    username = sp.getString(_kUsername);
  }

  static Future<void> save(String t, String u) async {
    token = t;
    username = u;
    final sp = await SharedPreferences.getInstance();
    await sp.setString(_kToken, t);
    await sp.setString(_kUsername, u);
  }

  static Future<void> clear() async {
    token = null;
    username = null;
    final sp = await SharedPreferences.getInstance();
    await sp.remove(_kToken);
    await sp.remove(_kUsername);
  }

  static Map<String, String> get headers => {
        'Content-Type': 'application/json',
        if (token != null) 'Authorization': 'Bearer $token',
      };

  /// multipart 用:不能带 Content-Type,否则会覆盖 boundary
  static Map<String, String> get authOnlyHeaders => {
        if (token != null) 'Authorization': 'Bearer $token',
      };
}
