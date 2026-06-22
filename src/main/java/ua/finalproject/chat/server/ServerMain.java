package ua.finalproject.chat.server;

import ua.finalproject.chat.db.ChatDatabase;
import ua.finalproject.chat.http.ChatHttpServer;
import ua.finalproject.chat.http.JwtTokenService;
import ua.finalproject.chat.udp.UdpHeartbeatServer;

public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = configuredPort(args, 0, "TCP_PORT", 5050);
        String dbUrl = configuredValue(args, 1, "CHAT_DB_URL", "jdbc:sqlite:chat.db");
        String secret = configuredValue(args, 2, "CHAT_PACKET_SECRET", "course-final-chat-secret");
        int httpPort = configuredHttpPort(args);
        String jwtSecret = configuredValue(args, 4, "CHAT_JWT_SECRET", "course-final-chat-jwt-secret");
        int udpPort = configuredPort(args, 5, "UDP_PORT", 5051);
        String allowedOrigin = configuredValue(args, 6, "CHAT_ALLOWED_ORIGIN", "");

        try (ChatDatabase database = new ChatDatabase(dbUrl);
             ChatServer server = new ChatServer(port, database, secret);
             ChatHttpServer httpServer = new ChatHttpServer(
                     httpPort,
                     database,
                     server,
                     new JwtTokenService(jwtSecret),
                     allowedOrigin
             );
             UdpHeartbeatServer udpHeartbeatServer = new UdpHeartbeatServer(udpPort)) {
            server.start();
            httpServer.start();
            udpHeartbeatServer.start();
            System.out.println("Chat server started on TCP port " + server.port());
            System.out.println("UDP heartbeat server started on port " + udpHeartbeatServer.port());
            System.out.println("Browser client is available at http://localhost:" + httpServer.port());
            System.out.println("Press Enter to stop.");
            System.in.read();
        }
    }

    private static int configuredHttpPort(String[] args) {
        if (args.length > 3) {
            return Integer.parseInt(args[3]);
        }
        String httpPort = System.getenv("HTTP_PORT");
        if (httpPort != null && !httpPort.isBlank()) {
            return Integer.parseInt(httpPort);
        }
        String platformPort = System.getenv("PORT");
        return platformPort == null || platformPort.isBlank() ? 8080 : Integer.parseInt(platformPort);
    }

    private static int configuredPort(String[] args, int argumentIndex, String environmentName, int defaultValue) {
        return Integer.parseInt(configuredValue(args, argumentIndex, environmentName, Integer.toString(defaultValue)));
    }

    private static String configuredValue(String[] args, int argumentIndex, String environmentName, String defaultValue) {
        if (args.length > argumentIndex && !args[argumentIndex].isBlank()) {
            return args[argumentIndex];
        }
        String value = System.getenv(environmentName);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
