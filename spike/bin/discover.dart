import 'dart:io';
import 'package:multicast_dns/multicast_dns.dart';

const service = '_androidtvremote2._tcp.local';

/// Pure-Dart mDNS browse. The same code path runs on iOS and Android.
Future<void> main() async {
  final client = MDnsClient();
  await client.start();
  var found = 0;
  await for (final ptr in client.lookup<PtrResourceRecord>(
      ResourceRecordQuery.serverPointer(service),
      timeout: const Duration(seconds: 6))) {
    await for (final srv in client.lookup<SrvResourceRecord>(
        ResourceRecordQuery.service(ptr.domainName))) {
      await for (final ip in client.lookup<IPAddressResourceRecord>(
          ResourceRecordQuery.addressIPv4(srv.target))) {
        found++;
        final name = ptr.domainName.split('.').first.replaceAll(r'\032', ' ');
        stdout.writeln('FOUND "$name"  ${ip.address.address}:${srv.port}');
      }
    }
  }
  client.stop();
  stdout.writeln(found == 0 ? 'no TVs found' : 'done ($found)');
  exit(0);
}
