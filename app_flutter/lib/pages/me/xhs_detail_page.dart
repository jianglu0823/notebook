import 'dart:async';
import 'dart:convert';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import '../../api/studio_api.dart';
import '../../core/media.dart';

/// 小红书五步工作台:方向→标题→素材→文案→配图,每步触发异步阶段后轮询
class XhsDetailPage extends StatefulWidget {
  const XhsDetailPage({super.key, required this.projectId});
  final int projectId;

  @override
  State<XhsDetailPage> createState() => _XhsDetailPageState();
}

class _XhsDetailPageState extends State<XhsDetailPage> {
  Map<String, dynamic>? _p;
  String? _waitingFor; // 轮询目标 status
  Timer? _timer;

  static const _styles = {
    'ZHONGCAO': '种草安利',
    'DUSHE': '毒舌吐槽',
    'GANHUO': '干货教程',
    'ZHIYU': '治愈温柔',
  };

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final p = await StudioApi.xhsGet(widget.projectId);
      if (!mounted) return;
      setState(() => _p = p);
      // 刚进页面:刚创建的项目还在跑标题,自动轮询
      if (p['status'] == 'NEW' && (p['errorMsg'] == null)) {
        _startPolling('TITLES_DONE');
      }
    } catch (_) {}
  }

  void _startPolling(String targetStatus) {
    _timer?.cancel();
    setState(() => _waitingFor = targetStatus);
    _timer = Timer.periodic(const Duration(seconds: 3), (_) async {
      try {
        final p = await StudioApi.xhsGet(widget.projectId);
        if (!mounted) return;
        setState(() => _p = p);
        if (p['status'] == targetStatus || p['errorMsg'] != null) {
          _timer?.cancel();
          setState(() => _waitingFor = null);
        }
      } catch (_) {}
    });
  }

  List<String> _jsonList(dynamic s) {
    if (s == null) return [];
    try {
      return (jsonDecode(s as String) as List).cast<String>();
    } catch (_) {
      return [];
    }
  }

  Future<void> _run(Future<Map<String, dynamic>> Function() action,
      String targetStatus) async {
    try {
      final p = await action();
      if (!mounted) return;
      setState(() => _p = p);
      _startPolling(targetStatus);
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('操作失败')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = _p;
    return Scaffold(
      appBar: AppBar(title: const Text('小红书项目')),
      body: p == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(padding: const EdgeInsets.all(16), children: [
              if (_waitingFor != null)
                const Padding(
                  padding: EdgeInsets.only(bottom: 12),
                  child: Row(children: [
                    SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2)),
                    SizedBox(width: 8),
                    Text('AI 生成中,请稍候…'),
                  ]),
                ),
              if (p['errorMsg'] != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Text('生成失败:${p['errorMsg']}',
                      style: const TextStyle(color: Colors.red)),
                ),
              _step1(p),
              _step2(p),
              _step3(p),
              _step4(p),
            ]),
    );
  }

  Widget _stepCard(int no, String title, bool done, Widget child) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            CircleAvatar(
                radius: 12,
                backgroundColor:
                    done ? Colors.green : Colors.grey.shade300,
                child: done
                    ? const Icon(Icons.check, size: 14, color: Colors.white)
                    : Text('$no', style: const TextStyle(fontSize: 12))),
            const SizedBox(width: 8),
            Text(title,
                style: const TextStyle(fontWeight: FontWeight.bold)),
          ]),
          const SizedBox(height: 8),
          child,
        ]),
      ),
    );
  }

  Widget _step1(Map<String, dynamic> p) => _stepCard(
      1, '方向', true, Text(p['direction'] as String? ?? ''));

  Widget _step2(Map<String, dynamic> p) {
    final titles = _jsonList(p['titleOptions']);
    final chosen = p['chosenTitle'] as String?;
    final done = p['status'] != 'NEW' && chosen != null;
    return _stepCard(
      2,
      '标题',
      done,
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (chosen != null)
          Text('已选:$chosen',
              style: const TextStyle(fontWeight: FontWeight.bold)),
        if (titles.isNotEmpty && p['status'] == 'TITLES_DONE')
          for (final t in titles)
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: Text(t),
              trailing: chosen == t
                  ? const Icon(Icons.check_circle, color: Colors.green)
                  : const Icon(Icons.circle_outlined),
              onTap: _waitingFor != null
                  ? null
                  : () => _run(
                      () => StudioApi.xhsResearch(widget.projectId, t),
                      'RESEARCH_DONE'),
            ),
        if (titles.isEmpty && chosen == null)
          const Text('候选标题生成中…',
              style: TextStyle(color: Colors.grey)),
      ]),
    );
  }

  Widget _step3(Map<String, dynamic> p) {
    final research = p['research'] as String?;
    final copy = p['copyText'] as String?;
    final style = p['style'] as String?;
    final researched = p['status'] == 'RESEARCH_DONE' ||
        p['status'] == 'COPY_DONE' ||
        p['status'] == 'IMAGES_DONE' ||
        p['status'] == 'VIDEO_DONE';
    return _stepCard(
      3,
      '素材与文案',
      copy != null,
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (research != null)
          ExpansionTile(
            tilePadding: EdgeInsets.zero,
            title: const Text('素材(已收集)', style: TextStyle(fontSize: 14)),
            children: [
              Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(research))
            ],
          ),
        if (researched && copy == null)
          Wrap(spacing: 8, children: [
            for (final e in _styles.entries)
              ActionChip(
                label: Text(e.value),
                onPressed: _waitingFor != null
                    ? null
                    : () => _run(
                        () => StudioApi.xhsCopy(widget.projectId, e.key),
                        'COPY_DONE'),
              ),
          ]),
        if (copy != null) ...[
          if (style != null)
            Text('风格:${_styles[style] ?? style}',
                style: const TextStyle(fontSize: 12, color: Colors.grey)),
          const SizedBox(height: 4),
          Text(copy),
        ],
        if (!researched && copy == null)
          const Text('选定标题后自动收集素材',
              style: TextStyle(color: Colors.grey)),
      ]),
    );
  }

  Widget _step4(Map<String, dynamic> p) {
    final images = _jsonList(p['imagePaths']);
    final canGen = p['status'] == 'COPY_DONE';
    return _stepCard(
      4,
      '配图',
      images.isNotEmpty,
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (canGen)
          FilledButton.tonalIcon(
            onPressed: _waitingFor != null
                ? null
                : () => _run(() => StudioApi.xhsImages(widget.projectId),
                    'IMAGES_DONE'),
            icon: const Icon(Icons.image_outlined),
            label: const Text('生成配图'),
          ),
        if (images.isNotEmpty)
          GridView.count(
            crossAxisCount: 3,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            mainAxisSpacing: 6,
            crossAxisSpacing: 6,
            children: [
              for (var i = 0; i < images.length; i++)
                ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: CachedNetworkImage(
                    imageUrl: mediaUrl(
                        '/api/studio/xhs/${widget.projectId}/images/$i'),
                    fit: BoxFit.cover,
                    errorWidget: (_, _, _) =>
                        const Icon(Icons.broken_image_outlined),
                  ),
                ),
            ],
          ),
        if (images.isEmpty && !canGen)
          const Text('生成文案后可生成配图',
              style: TextStyle(color: Colors.grey)),
      ]),
    );
  }
}
