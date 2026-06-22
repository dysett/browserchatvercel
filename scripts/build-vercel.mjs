import { cp, mkdir, rm, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectDirectory = resolve(scriptDirectory, "..");
const sourceDirectory = join(projectDirectory, "src", "main", "resources", "web");
const outputDirectory = join(projectDirectory, "dist");
const apiUrl = String(process.env.CHAT_API_URL || "").trim().replace(/\/+$/, "");

await rm(outputDirectory, { recursive: true, force: true });
await mkdir(outputDirectory, { recursive: true });
await cp(sourceDirectory, outputDirectory, { recursive: true });
await writeFile(join(outputDirectory, "config.js"), `window.CHAT_API_URL = ${JSON.stringify(apiUrl)};\n`);
