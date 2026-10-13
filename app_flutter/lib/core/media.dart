import 'auth.dart';
import 'config.dart';

/// 媒体 URL 拼接:AuthFilter 支持 ?token= 兜底,图片/音频/视频统一走这里
String mediaUrl(String path) {
  final sep = path.contains('?') ? '&' : '?';
  return '$apiBase$path${sep}token=${Uri.encodeComponent(Auth.token ?? "")}';
}
