// Serveur MCP minimal — test S5 du protocole T3.
// But : déterminer quelles formes d'URL Edge Gallery accepte réellement
// (127.0.0.1, IP LAN, HTTPS public) et observer un aller-retour d'outil.
// Mode stateless : chaque requête POST reçoit un serveur/transport neufs,
// donc n'importe quel ordre d'appels client est toléré.

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import express from "express";
import { z } from "zod";

const PORT = 8765;

function buildServer() {
  const server = new McpServer({ name: "albugimed-s5", version: "0.1.0" });

  server.tool(
    "ping",
    "Vérifie l'aller-retour outil entre Edge Gallery et l'orchestrateur.",
    {},
    async () => ({
      content: [
        {
          type: "text",
          text: JSON.stringify({ pong: true, at: new Date().toISOString() }),
        },
      ],
    }),
  );

  server.tool(
    "propose_capture_processing",
    "Reçoit une capture brute et renvoie une proposition structurée factice (aucune écriture réelle).",
    { capture: z.string().describe("Texte brut de la capture") },
    async ({ capture }) => ({
      content: [
        {
          type: "text",
          text: JSON.stringify({
            source_text: capture,
            processable: true,
            objects: [
              {
                kind: "unknown",
                explicit_facts: {},
                inferences: [],
                missing_information: ["test S5 : proposition factice, serveur sans logique"],
                confidence: "low",
                requires_user_validation: true,
              },
            ],
          }),
        },
      ],
    }),
  );

  return server;
}

const app = express();
app.use(express.json({ limit: "1mb" }));

app.post("/mcp", async (req, res) => {
  console.log(`[${new Date().toISOString()}] POST /mcp de ${req.ip} — method=${req.body?.method ?? "?"}`);
  try {
    const server = buildServer();
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
      enableJsonResponse: true,
    });
    res.on("close", () => {
      transport.close();
      server.close();
    });
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    console.error("Erreur:", error);
    if (!res.headersSent) {
      res.status(500).json({
        jsonrpc: "2.0",
        error: { code: -32603, message: "Internal server error" },
        id: null,
      });
    }
  }
});

// Stateless : pas de flux serveur→client ni de session à clôturer.
app.get("/mcp", (_req, res) => res.status(405).set("Allow", "POST").end());
app.delete("/mcp", (_req, res) => res.status(405).set("Allow", "POST").end());

app.listen(PORT, "0.0.0.0", () => {
  console.log(`Serveur MCP S5 en écoute sur 0.0.0.0:${PORT} (endpoint : /mcp)`);
});
