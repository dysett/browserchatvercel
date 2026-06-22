import { createServer, request } from "node:http";

const targetPort = Number(process.env.CHAT_SERVER_PORT || 8080);
const proxyPort = Number(process.env.CHAT_PROXY_PORT || 8090);

const proxy = createServer((incoming, outgoing) => {
    const chunks = [];
    incoming.on("data", (chunk) => chunks.push(chunk));
    incoming.on("end", () => {
        const body = Buffer.concat(chunks);
        const headers = { ...incoming.headers };
        delete headers.host;
        delete headers["transfer-encoding"];
        headers["content-length"] = String(body.length);

        const target = request({
            hostname: "127.0.0.1",
            port: targetPort,
            path: incoming.url,
            method: incoming.method,
            headers
        }, (response) => {
            outgoing.writeHead(response.statusCode || 502, response.headers);
            response.pipe(outgoing);
        });

        target.on("error", () => {
            outgoing.writeHead(502, { "Content-Type": "application/json" });
            outgoing.end('{"error":"Local chat server is unavailable"}');
        });
        target.end(body);
    });
});

proxy.listen(proxyPort, "127.0.0.1", () => {
    console.log(`Demo proxy listens on http://127.0.0.1:${proxyPort}`);
});
