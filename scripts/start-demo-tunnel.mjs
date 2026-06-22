import localtunnel from "localtunnel";

const port = Number(process.env.CHAT_TUNNEL_PORT || 8090);
const subdomain = process.env.CHAT_TUNNEL_SUBDOMAIN || "browserchatonl-demo-202606221650";
let stopped = false;
let retryScheduled = false;

function retry() {
    if (stopped || retryScheduled) {
        return;
    }
    retryScheduled = true;
    console.log("Tunnel connection closed. Reconnecting in 3 seconds...");
    setTimeout(() => {
        retryScheduled = false;
        void connect();
    }, 3_000);
}

async function connect() {
    try {
        const tunnel = await localtunnel({ port, subdomain, local_host: "127.0.0.1" });
        console.log(`Tunnel is available at ${tunnel.url}`);
        tunnel.on("close", retry);
        tunnel.on("error", retry);
    } catch (error) {
        console.error("Tunnel connection failed:", error.message);
        retry();
    }
}

process.on("SIGINT", () => {
    stopped = true;
    process.exit(0);
});

setInterval(() => {}, 60_000);
void connect();
