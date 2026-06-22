package ua.finalproject.chat.http;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

public final class JwtTokenService {
    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final String issuer;
    private final Duration ttl;

    public JwtTokenService(String secret) {
        this(secret, "online-chat-http", Duration.ofHours(8));
    }

    public JwtTokenService(String secret, String issuer, Duration ttl) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret is required");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer is required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("JWT ttl must be positive");
        }
        this.issuer = issuer;
        this.ttl = ttl;
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm).withIssuer(issuer).build();
    }

    public String create(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(issuer)
                .withSubject(username)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(ttl)))
                .sign(algorithm);
    }

    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String username = verifier.verify(token).getSubject();
            return username == null || username.isBlank() ? Optional.empty() : Optional.of(username);
        } catch (JWTVerificationException e) {
            return Optional.empty();
        }
    }
}
