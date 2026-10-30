import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:protobuf/protobuf.dart';

import 'proto/polo.pb.dart';
import 'proto/polo.pbenum.dart';
import 'proto/remotemessage.pb.dart';
import 'proto/remotemessage.pbenum.dart';
import 'src/certs.dart';
import 'src/framing.dart';

export 'proto/remotemessage.pbenum.dart' show RemoteKeyCode, RemoteDirection;
export 'src/certs.dart';

const pairingPort = 6467;
const remotePort = 6466;

/// Features we tell the TV we support: PING|KEY|IME|POWER|VOLUME|APP_LINK.
const activeFeatures = 1 | 2 | 4 | 32 | 64 | 512; // 615

Future<SecureSocket> _connect(String host, int port, ClientIdentity id) {
  final ctx = SecurityContext(withTrustedRoots: false)
    ..useCertificateChainBytes(utf8.encode(id.certPem))
    ..usePrivateKeyBytes(utf8.encode(id.keyPem));
  // The TV's certificate is self-signed, so it can never chain to a root.
  return SecureSocket.connect(host, port,
      context: ctx, onBadCertificate: (_) => true);
}

OuterMessage _outer() => OuterMessage()
  ..protocolVersion = 2
  ..status = OuterMessage_Status.STATUS_OK;

/// Port 6467. Run once per TV; the certificate is trusted from then on.
class PairingSession {
  PairingSession(this.host, this.identity, {this.clientName = 'Flutter Remote'});
  final String host;
  final ClientIdentity identity;
  final String clientName;

  late SecureSocket _sock;
  final _reader = FrameReader();
  final _codeRequested = Completer<void>();
  final _paired = Completer<void>();
  late RSAPublicKeyLike _serverKey;

  void _send(GeneratedMessage m) => _sock.add(FrameReader.frame(m.writeToBuffer()));

  /// Connects and walks the handshake until the TV displays its code.
  Future<void> start() async {
    _sock = await _connect(host, pairingPort, identity);
    _serverKey = RSAPublicKeyLike(publicKeyFromCertDer(
        Uint8List.fromList(_sock.peerCertificate!.der)));
    _sock.listen(_onData,
        onError: (e) => _fail(e), onDone: () => _fail('connection closed'));
    _send(_outer()
      ..pairingRequest = (PairingRequest()
        ..clientName = clientName
        ..serviceName = 'atvremote'));
    return _codeRequested.future;
  }

  /// Submit the code shown on the TV. Throws before touching the network if
  /// the code's own checksum does not match.
  Future<void> finish(String code) async {
    if (code.length != 6) throw ArgumentError('code must be 6 characters');
    final hash = computePairingHash(
      clientKey: publicKeyFromCertPem(identity.certPem),
      serverKey: _serverKey.key,
      code: code.toUpperCase(),
    );
    if (!hash.checksumOk) throw ArgumentError('code checksum mismatch');
    _send(_outer()..secret = (Secret()..secret = hash.digest));
    await _paired.future;
    await _sock.close();
  }

  void _fail(Object e) {
    if (!_codeRequested.isCompleted) _codeRequested.completeError(e);
    if (!_paired.isCompleted) _paired.completeError(e);
  }

  void _onData(List<int> data) {
    for (final raw in _reader.add(data)) {
      final msg = OuterMessage.fromBuffer(raw);
      if (msg.status != OuterMessage_Status.STATUS_OK) {
        _fail('TV returned status ${msg.status}');
        return;
      }
      final reply = _outer();
      if (msg.hasPairingRequestAck()) {
        reply.options = Options()
          ..preferredRole = Options_RoleType.ROLE_TYPE_INPUT
          ..inputEncodings.add(Options_Encoding()
            ..type = Options_Encoding_EncodingType.ENCODING_TYPE_HEXADECIMAL
            ..symbolLength = 6);
      } else if (msg.hasOptions()) {
        reply.configuration = Configuration()
          ..clientRole = Options_RoleType.ROLE_TYPE_INPUT
          ..encoding = (Options_Encoding()
            ..type = Options_Encoding_EncodingType.ENCODING_TYPE_HEXADECIMAL
            ..symbolLength = 6);
      } else if (msg.hasConfigurationAck()) {
        if (!_codeRequested.isCompleted) _codeRequested.complete();
        continue;
      } else if (msg.hasSecretAck()) {
        if (!_paired.isCompleted) _paired.complete();
        continue;
      } else {
        _fail('unexpected pairing message');
        return;
      }
      _send(reply);
    }
  }
}

/// Wrapper so certs.dart's pointycastle type does not leak into the API surface.
class RSAPublicKeyLike {
  RSAPublicKeyLike(this.key);
  final dynamic key;
}

class VolumeInfo {
  VolumeInfo(this.level, this.max, this.muted);
  final int level, max;
  final bool muted;
  @override
  String toString() => 'level $level/$max${muted ? " (muted)" : ""}';
}

/// Port 6466. The live session: keys out, state in.
class RemoteSession {
  RemoteSession(this.host, this.identity);
  final String host;
  final ClientIdentity identity;

  late SecureSocket _sock;
  final _reader = FrameReader();
  final _started = Completer<void>();

  String deviceModel = '', deviceVendor = '', deviceVersion = '';
  bool isOn = false;
  String currentApp = '';
  VolumeInfo? volume;

  final _events = StreamController<String>.broadcast();
  Stream<String> get events => _events.stream;

  void _send(GeneratedMessage m) => _sock.add(FrameReader.frame(m.writeToBuffer()));

  Future<void> connect() async {
    _sock = await _connect(host, remotePort, identity);
    _sock.listen(_onData, onError: (e) {
      if (!_started.isCompleted) _started.completeError(e);
    }, onDone: () {
      if (!_started.isCompleted) _started.completeError('closed before start');
    });
    return _started.future;
  }

  void sendKey(RemoteKeyCode key,
      {RemoteDirection direction = RemoteDirection.SHORT}) {
    _send(RemoteMessage()
      ..remoteKeyInject = (RemoteKeyInject()
        ..keyCode = key
        ..direction = direction));
  }

  void launchApp(String appLink) {
    _send(RemoteMessage()
      ..remoteAppLinkLaunchRequest = (RemoteAppLinkLaunchRequest()..appLink = appLink));
  }

  Future<void> close() => _sock.close();

  void _onData(List<int> data) {
    for (final raw in _reader.add(data)) {
      final msg = RemoteMessage.fromBuffer(raw);
      final reply = RemoteMessage();
      if (msg.hasRemoteConfigure()) {
        deviceModel = msg.remoteConfigure.deviceInfo.model;
        deviceVendor = msg.remoteConfigure.deviceInfo.vendor;
        deviceVersion = msg.remoteConfigure.deviceInfo.appVersion;
        reply.remoteConfigure = RemoteConfigure()
          ..code1 = activeFeatures
          ..deviceInfo = (RemoteDeviceInfo()
            ..unknown1 = 1
            ..unknown2 = '1'
            ..packageName = 'atvremote'
            ..appVersion = '1.0.0');
      } else if (msg.hasRemoteSetActive()) {
        reply.remoteSetActive = RemoteSetActive()..active = activeFeatures;
      } else if (msg.hasRemoteStart()) {
        isOn = msg.remoteStart.started;
        _events.add('start: isOn=$isOn');
        if (!_started.isCompleted) _started.complete();
        continue;
      } else if (msg.hasRemotePingRequest()) {
        // Keepalive. Miss these and the TV drops the connection.
        reply.remotePingResponse = RemotePingResponse()
          ..val1 = msg.remotePingRequest.val1;
      } else if (msg.hasRemoteSetVolumeLevel()) {
        final v = msg.remoteSetVolumeLevel;
        volume = VolumeInfo(v.volumeLevel, v.volumeMax, v.volumeMuted);
        _events.add('volume: $volume');
        continue;
      } else if (msg.hasRemoteImeKeyInject()) {
        currentApp = msg.remoteImeKeyInject.appInfo.appPackage;
        _events.add('app: $currentApp');
        continue;
      } else if (msg.hasRemoteError()) {
        _events.add('error: ${msg.remoteError}');
        continue;
      } else {
        continue;
      }
      _send(reply);
    }
  }
}
