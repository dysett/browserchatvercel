# Browser Chat

Browser Chat is the web version of the final online-chat project.

## Included

- browser UI with registration, login, chats, groups, themes and admin controls;
- HTTP REST API protected by JWT Bearer tokens;
- authenticated server-sent events for real-time browser updates;
- SQLite/JDBC persistence;
- commit/rollback transactions for multi-step database operations;
- PBKDF2 password hashing with a unique salt;
- TCP protocol core with AES-GCM, CRC16 and big-endian packets;
- UDP heartbeat with ACK, timeout and retry handling;
- multithreaded server and JUnit tests.

The project intentionally has no Swing or console client. The TCP protocol remains in the server core, while users work through the browser UI.

## Requirements

- JDK 17 or newer;
- internet access during the first run to download Maven and dependencies.

## Run

```powershell
.\run-server.cmd
```

Then open `http://localhost:8080/`.

The server also listens on TCP port `5050` and UDP heartbeat port `5051` by default.

## Test

```powershell
mvn test
```

## Vercel Frontend

Vercel hosts the browser interface as a static site. The Java server, TCP/UDP ports and database must run on a separate host.

1. Import this GitHub repository into Vercel.
2. Set the Vercel environment variable `CHAT_API_URL` to the public HTTPS address of the Java server, for example `https://api.example.com`.
3. Deploy the project. Vercel runs `npm run build` and publishes `dist`.
4. On the Java server, set `CHAT_ALLOWED_ORIGIN` to the Vercel production URL, for example `https://browserchatvercel.vercel.app`.

The backend also accepts `HTTP_PORT` or `PORT`, `CHAT_DB_URL`, `CHAT_PACKET_SECRET`, `CHAT_JWT_SECRET`, `TCP_PORT` and `UDP_PORT` as environment variables.
