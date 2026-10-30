import 'dart:io';
import 'package:atv_spike/atv.dart';

/// Usage: dart run bin/keys.dart <host> [certDir] [KEYCODE...]
Future<void> main(List<String> args) async {
  final host = args.isNotEmpty ? args[0] : '192.168.0.236';
  final dir = args.length > 1 ? args[1] : 'creds';
  final id = ClientIdentity(
      File('$dir/cert.pem').readAsStringSync(), File('$dir/key.pem').readAsStringSync());

  final s = RemoteSession(host, id);
  s.events.listen((e) => stdout.writeln('  << $e'));
  await s.connect();
  stdout.writeln('CONNECTED (no pairing needed)');
  stdout.writeln('  model=${s.deviceModel} vendor=${s.deviceVendor} isOn=${s.isOn}');

  final keys = args.length > 2 ? args.sublist(2) : ['VOLUME_DOWN', 'VOLUME_UP'];
  for (final name in keys) {
    final key = RemoteKeyCode.values.firstWhere((k) => k.name == 'KEYCODE_$name');
    stdout.writeln('  >> $name');
    s.sendKey(key);
    await Future<void>.delayed(const Duration(milliseconds: 1200));
  }
  stdout.writeln('volume=${s.volume}  currentApp=${s.currentApp}');
  await s.close();
  exit(0);
}
