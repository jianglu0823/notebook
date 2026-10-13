import 'dart:convert';
import 'package:http/http.dart' as http;
import 'auth.dart';
import 'config.dart';

class ApiException implements Exception {
  final int status;
  final String body;
  ApiException(this.status, this.body);
  @override
  String toString() => 'HTTP $status: $body';
}

/// 统一 HTTP 入口:JSON 编解码、401 全局登出
class ApiClient {
  static Future<dynamic> get(String path) =>
      _send('GET', path).then(_decode);

  static Future<dynamic> post(String path, [Object? body]) =>
      _send('POST', path, body: body).then(_decode);

  static Future<dynamic> put(String path, [Object? body]) =>
      _send('PUT', path, body: body).then(_decode);

  static Future<dynamic> patch(String path, [Object? body]) =>
      _send('PATCH', path, body: body).then(_decode);

  static Future<dynamic> del(String path, [Object? body]) =>
      _send('DELETE', path, body: body).then(_decode);

  static Future<http.Response> _send(String method, String path,
      {Object? body}) async {
    final uri = Uri.parse('$apiBase$path');
    final req = http.Request(method, uri);
    req.headers.addAll(Auth.headers);
    if (body != null) req.body = jsonEncode(body);
    final streamed =
        await http.Client().send(req).timeout(const Duration(seconds: 30));
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    return resp;
  }

  /// multipart 上传(fileBytes + 文件名),fields 为额外表单字段
  static Future<dynamic> upload(String path, String fieldName,
      List<int> bytes, String filename,
      [Map<String, String>? fields]) async {
    final req =
        http.MultipartRequest('POST', Uri.parse('$apiBase$path'));
    req.headers.addAll(Auth.authOnlyHeaders);
    if (fields != null) req.fields.addAll(fields);
    req.files.add(http.MultipartFile.fromBytes(fieldName, bytes,
        filename: filename));
    final streamed =
        await req.send().timeout(const Duration(seconds: 60));
    final resp = await http.Response.fromStream(streamed);
    _check(resp);
    return _decode(resp);
  }

  static void _check(http.Response resp) {
    if (resp.statusCode == 401 || resp.statusCode == 403) {
      Auth.clear();
      Auth.onUnauthorized?.call();
    }
    if (resp.statusCode >= 400) {
      throw ApiException(resp.statusCode, resp.body);
    }
  }

  static dynamic _decode(http.Response resp) =>
      resp.body.isEmpty ? null : jsonDecode(utf8.decode(resp.bodyBytes));
}
