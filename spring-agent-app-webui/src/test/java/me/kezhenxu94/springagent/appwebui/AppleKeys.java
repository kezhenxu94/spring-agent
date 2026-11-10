package me.kezhenxu94.springagent.appwebui;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Stands in for Apple: an RSA key of its own, published as a JWK set on a local port, and identity
 * tokens signed with it the way Sign in with Apple signs them.
 */
final class AppleKeys {

  private final RSAKey key;
  private final HttpServer server;

  private AppleKeys(final RSAKey key, final HttpServer server) {
    this.key = key;
    this.server = server;
  }

  static AppleKeys start() {
    try {
      final var key = new RSAKeyGenerator(2048).keyID("test-key").generate();
      final var json = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/auth/keys",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (var out = exchange.getResponseBody()) {
              out.write(json);
            }
          });
      server.start();
      return new AppleKeys(key, server);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  String jwksUri() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/keys";
  }

  String identityToken(final String audience, final String subject) {
    try {
      final var now = Instant.now();
      final var claims =
          new JWTClaimsSet.Builder()
              .issuer("https://appleid.apple.com")
              .audience(audience)
              .subject(subject)
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(600)))
              .build();
      final var jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void stop() {
    server.stop(0);
  }
}
