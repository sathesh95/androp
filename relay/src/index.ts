import { DurableObject } from "cloudflare:workers";

interface Env {
  SYNC_ROOMS: DurableObjectNamespace<SyncRoom>;
}

interface EncryptedPayload {
  type: "SYNC" | "GET_LATEST" | "LATEST_DATA" | "PING" | "PONG" | "ACK";
  roomId: string;
  iv?: string;
  ciphertext?: string;
  hash?: string;
  originDeviceId?: string;
  timestamp?: number;
}

export class SyncRoom extends DurableObject {
  private lastBuffer: EncryptedPayload | null = null;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
  }

  async fetch(request: Request): Promise<Response> {
    const upgradeHeader = request.headers.get("Upgrade");
    if (!upgradeHeader || upgradeHeader !== "websocket") {
      return new Response("Expected Upgrade: websocket", { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    // Accept WebSocket into hibernation state
    this.ctx.acceptWebSocket(server);

    return new Response(null, {
      status: 101,
      webSocket: client,
    });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;

    try {
      const data: EncryptedPayload = JSON.parse(message);

      if (data.type === "PING") {
        ws.send(JSON.stringify({ type: "PONG", timestamp: Date.now() }));
        return;
      }

      if (data.type === "GET_LATEST") {
        if (this.lastBuffer) {
          ws.send(JSON.stringify({
            ...this.lastBuffer,
            type: "LATEST_DATA"
          }));
        }
        return;
      }

      if (data.type === "SYNC") {
        if (!data.ciphertext || !data.iv || !data.hash) return;

        // Store encrypted blob in memory catchup buffer (Max 24 hour freshness)
        this.lastBuffer = {
          type: "SYNC",
          roomId: data.roomId,
          iv: data.iv,
          ciphertext: data.ciphertext,
          hash: data.hash,
          originDeviceId: data.originDeviceId,
          timestamp: data.timestamp || Date.now()
        };

        // Broadcast to all other connected sockets in this room
        const sockets = this.ctx.getWebSockets();
        for (const targetWs of sockets) {
          if (targetWs !== ws) {
            try {
              targetWs.send(JSON.stringify(this.lastBuffer));
            } catch (err) {
              // Socket might be closing
            }
          }
        }

        // Acknowledge sender
        ws.send(JSON.stringify({ type: "ACK", hash: data.hash }));
      }
    } catch (e) {
      console.error("Invalid message format received", e);
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    ws.close(code, reason);
  }

  async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
    ws.close(1011, "WebSocket error");
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // Health check endpoint
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response(JSON.stringify({ status: "ok", service: "clipboard-sync-relay", version: "1.0.0" }), {
        headers: { "Content-Type": "application/json" }
      });
    }

    // WebSocket endpoint: /ws?room=<ROOM_ID>
    if (url.pathname === "/ws") {
      const roomId = url.searchParams.get("room");
      if (!roomId || roomId.length < 8) {
        return new Response("Missing or invalid 'room' parameter", { status: 400 });
      }

      const id = env.SYNC_ROOMS.idFromName(roomId);
      const roomStub = env.SYNC_ROOMS.get(id);
      return roomStub.fetch(request);
    }

    return new Response("Not Found", { status: 404 });
  }
};
