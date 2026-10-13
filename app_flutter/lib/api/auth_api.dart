import '../core/api_client.dart';

class AuthApi {
  static Future<Map<String, dynamic>> login(String u, String p) async =>
      await ApiClient.post('/api/auth/login', {'username': u, 'password': p});

  static Future<Map<String, dynamic>> register(String u, String p) async =>
      await ApiClient.post(
          '/api/auth/register', {'username': u, 'password': p});

  static Future<Map<String, dynamic>> me() async => await ApiClient.get('/api/auth/me');
}
