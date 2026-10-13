import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';

/// 后端地址:Android 模拟器用 10.0.2.2 访问宿主机,iOS 模拟器与宿主机共享
/// 网络栈直接用 127.0.0.1;真机/其他环境用 `--dart-define=API_BASE=...` 覆盖
const String _apiBaseOverride = String.fromEnvironment('API_BASE');

String get apiBase {
  if (_apiBaseOverride.isNotEmpty) return _apiBaseOverride;
  if (kIsWeb) return 'http://127.0.0.1:8082';
  return Platform.isAndroid ? 'http://10.0.2.2:8082' : 'http://127.0.0.1:8082';
}

String get wsBase =>
    apiBase.replaceFirst('http://', 'ws://').replaceFirst('https://', 'wss://');
