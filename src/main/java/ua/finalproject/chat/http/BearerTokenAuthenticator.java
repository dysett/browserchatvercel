package ua.finalproject.chat.http;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.util.List;

final class BearerTokenAuthenticator extends Authenticator {
    private final JwtTokenService tokenService;

    BearerTokenAuthenticator(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Result authenticate(HttpExchange exchange) {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            return new Success(new HttpPrincipal("cors-preflight", "chat"));
        }
        List<String> values = exchange.getRequestHeaders().get("Authorization");
        if (values == null || values.isEmpty()) {
            return new Failure(401);
        }
        String value = values.get(0);
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return new Failure(401);
        }
        return tokenService.verify(value.substring(7).trim())
                .<Result>map(username -> new Success(new HttpPrincipal(username, "chat")))
                .orElseGet(() -> new Failure(401));
    }
}
