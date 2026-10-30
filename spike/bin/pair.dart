import 'dart:io';
import 'package:atv_spike/atv.dart';

/// Usage: dart run bin/pair.dart <host> [certDir]
Future<void> main(List<String> args) async {
  final host = args.isNotEmpty ? args[0] : '192.168.0.236';
  final dir = Directory(args.length > 1 ? args[1] : 'creds')..createSync(recursive: true);
  final certFile = File('${dir.path}/cert.pem');
  final keyFile = File('${dir.path}/key.pem');

  ClientIdentity id;
  if (certFile.existsSync() && keyFile.existsSync()) {
    id = ClientIdentity(certFile.readAsStringSync(), keyFile.readAsStringSync());
    stdout.writeln('using existing certificate');
  } else {
    final sw = Stopwatch()..start();
    id = generateClientIdentity();
    sw.stop();
    certFile.writeAsStringSync(id.certPem);
    keyFile.writeAsStringSync(id.keyPem);
    stdout.writeln('generated RSA-2048 cert in ${sw.elapsedMilliseconds} ms');
  }

  final session = PairingSession(host, id, clientName: 'Flutter Remote Spike');
  await session.start();
  stdout.writeln('TV is displaying a 6-character code. Enter it:');
  final codeFile = File('${dir.path}/code.txt');
  String code = '';
  if (stdin.hasTerminal) {
    // Returns null immediately when there is no interactive input (e.g. nohup).
    code = stdin.readLineSync()?.trim() ?? '';
  }
  if (code.isEmpty) {
    // Non-interactive: wait for the code to be dropped into creds/code.txt.
    for (var i = 0; i < 300 && code.isEmpty; i++) {
      if (codeFile.existsSync()) code = codeFile.readAsStringSync().trim();
      if (code.isEmpty) await Future<void>.delayed(const Duration(seconds: 1));
    }
    if (code.isEmpty) throw StateError('no code supplied');
    stdout.writeln('got code $code');
  }
  await session.finish(code);
  stdout.writeln('PAIRED');
  exit(0);
}
