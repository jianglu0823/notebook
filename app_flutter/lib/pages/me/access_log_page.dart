import 'package:flutter/material.dart';
import '../../api/me_api.dart';

/// 我的访问日志(仅本人记录,后端按 owner 隔离)
class AccessLogPage extends StatelessWidget {
  const AccessLogPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('访问日志')),
      body: FutureBuilder<List<dynamic>>(
        future: MeApi.myLogs(),
        builder: (context, snap) {
          final logs = snap.data;
          if (logs == null) {
            return const Center(child: CircularProgressIndicator());
          }
          if (logs.isEmpty) {
            return const Center(child: Text('暂无记录'));
          }
          return ListView.separated(
            itemCount: logs.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (context, i) {
              final l = logs[i];
              final status = l['status'];
              return ListTile(
                dense: true,
                leading: Icon(
                  status != null && status < 400
                      ? Icons.check_circle_outline
                      : Icons.error_outline,
                  color: status != null && status < 400
                      ? Colors.green
                      : Colors.red,
                ),
                title: Text(
                    '${l['action'] ?? ''} ${l['method'] ?? ''} ${l['path'] ?? ''}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis),
                subtitle: Text(
                    '${l['createdAt']?.toString().substring(0, 19) ?? ''} · ${l['deviceType'] ?? ''} · ${l['ip'] ?? ''}'),
              );
            },
          );
        },
      ),
    );
  }
}
