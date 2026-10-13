import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:just_audio/just_audio.dart';
import '../../api/gen_api.dart';
import '../../core/media.dart';

/// 生成物 Tab:文档(摘要/学习指南/FAQ) + 播客
class GenTab extends StatefulWidget {
  const GenTab({super.key, required this.nbId});
  final int nbId;

  @override
  State<GenTab> createState() => _GenTabState();
}

class _GenTabState extends State<GenTab> {
  List<dynamic>? _docs;
  List<dynamic>? _podcasts;
  Timer? _poll;
  bool _generating = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final docs = await GenApi.list(widget.nbId);
      final pods = await PodcastApi.list(widget.nbId);
      if (!mounted) return;
      setState(() {
        _docs = docs;
        _podcasts = pods;
      });
      _poll?.cancel();
      final pending = [...docs, ...pods].any((d) =>
          d['status'] == 'PENDING' ||
          d['status'] == 'SCRIPTING' ||
          d['status'] == 'SYNTHESIZING');
      if (pending) _poll = Timer(const Duration(seconds: 3), _load);
    } catch (_) {
      if (mounted) setState(() { _docs ??= []; _podcasts ??= []; });
    }
  }

  Future<void> _gen(String what, [String? kind]) async {
    if (_generating) return;
    setState(() => _generating = true);
    try {
      if (what == 'podcast') {
        await PodcastApi.generate(widget.nbId);
      } else {
        await GenApi.generate(widget.nbId, kind!);
      }
      _load();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('生成失败')));
      }
    } finally {
      if (mounted) setState(() => _generating = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_docs == null || _podcasts == null) {
      return const Center(child: CircularProgressIndicator());
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _genBtn('摘要', () => _gen('doc', 'SUMMARY')),
              _genBtn('学习指南', () => _gen('doc', 'STUDY_GUIDE')),
              _genBtn('FAQ', () => _gen('doc', 'FAQ')),
              _genBtn('播客', () => _gen('podcast'),
                  icon: Icons.podcasts),
            ],
          ),
          const SizedBox(height: 16),
          if (_docs!.isEmpty && _podcasts!.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 60),
              child: Center(child: Text('点上面按钮生成内容')),
            ),
          for (final p in _podcasts!) _podcastTile(p),
          for (final d in _docs!) _docTile(d),
        ],
      ),
    );
  }

  Widget _genBtn(String label, VoidCallback onTap, {IconData? icon}) {
    return FilledButton.tonalIcon(
      onPressed: _generating ? null : onTap,
      icon: Icon(icon ?? Icons.article_outlined, size: 18),
      label: Text(label),
    );
  }

  String _kindText(String k) => switch (k) {
        'SUMMARY' => '摘要',
        'STUDY_GUIDE' => '学习指南',
        'FAQ' => 'FAQ',
        _ => k,
      };

  Widget _statusChip(String status) {
    final (text, done) = switch (status) {
      'DONE' => ('已完成', true),
      'FAILED' => ('失败', true),
      'PENDING' => ('排队中', false),
      'SCRIPTING' => ('写稿中', false),
      'SYNTHESIZING' => ('合成中', false),
      _ => (status, false),
    };
    return done
        ? Text(text,
            style: TextStyle(
                fontSize: 12,
                color: status == 'FAILED' ? Colors.red : Colors.green))
        : Row(mainAxisSize: MainAxisSize.min, children: [
            const SizedBox(
                width: 12,
                height: 12,
                child: CircularProgressIndicator(strokeWidth: 2)),
            const SizedBox(width: 4),
            Text(text, style: const TextStyle(fontSize: 12)),
          ]);
  }

  Widget _docTile(Map<String, dynamic> d) {
    return Card(
      child: ListTile(
        leading: const Icon(Icons.article_outlined),
        title: Text(_kindText(d['kind'] as String? ?? '')),
        subtitle: _statusChip(d['status'] as String? ?? ''),
        onTap: d['status'] == 'DONE'
            ? () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => _DocViewPage(
                    title: _kindText(d['kind'] as String? ?? ''),
                    content: d['content'] as String? ?? '')))
            : null,
      ),
    );
  }

  Widget _podcastTile(Map<String, dynamic> p) {
    return Card(
      child: ListTile(
        leading: const Icon(Icons.podcasts),
        title: Text(p['title'] as String? ?? '音频概览'),
        subtitle: _statusChip(p['status'] as String? ?? ''),
        onTap: p['status'] == 'DONE'
            ? () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => _PodcastPlayerPage(
                    title: p['title'] as String? ?? '音频概览',
                    url: mediaUrl(
                        '/api/notebooks/${widget.nbId}/podcasts/${p['id']}/audio'))))
            : null,
      ),
    );
  }
}

class _DocViewPage extends StatelessWidget {
  const _DocViewPage({required this.title, required this.content});
  final String title;
  final String content;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: MarkdownBody(data: content),
      ),
    );
  }
}

class _PodcastPlayerPage extends StatefulWidget {
  const _PodcastPlayerPage({required this.title, required this.url});
  final String title;
  final String url;

  @override
  State<_PodcastPlayerPage> createState() => _PodcastPlayerPageState();
}

class _PodcastPlayerPageState extends State<_PodcastPlayerPage> {
  final _player = AudioPlayer();
  String? _error;

  @override
  void initState() {
    super.initState();
    _player.setUrl(widget.url).catchError((_) {
      if (mounted) setState(() => _error = '音频加载失败');
      return Duration.zero;
    });
  }

  @override
  void dispose() {
    _player.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: Center(
        child: _error != null
            ? Text(_error!)
            : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  StreamBuilder<PlayerState>(
                    stream: _player.playerStateStream,
                    builder: (_, snap) {
                      final playing = snap.data?.playing ?? false;
                      return IconButton.filled(
                        iconSize: 64,
                        icon: Icon(
                            playing ? Icons.pause : Icons.play_arrow),
                        onPressed: () =>
                            playing ? _player.pause() : _player.play(),
                      );
                    },
                  ),
                  const SizedBox(height: 24),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 32),
                    child: StreamBuilder<Duration>(
                      stream: _player.positionStream,
                      builder: (_, snap) {
                        final pos = snap.data ?? Duration.zero;
                        final dur = _player.duration ?? Duration.zero;
                        return Column(children: [
                          Slider(
                            value: pos.inSeconds
                                .toDouble()
                                .clamp(0, dur.inSeconds.toDouble()),
                            max: dur.inSeconds.toDouble() > 0
                                ? dur.inSeconds.toDouble()
                                : 1,
                            onChanged: (v) => _player
                                .seek(Duration(seconds: v.round())),
                          ),
                          Text(
                              '${_fmt(pos)} / ${_fmt(dur)}'),
                        ]);
                      },
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  String _fmt(Duration d) =>
      '${d.inMinutes}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';
}
