import { DurableObject } from "cloudflare:workers";

// ---------------------------------------------------------------------------
// Environment
// ---------------------------------------------------------------------------

interface Env {
  SYNC_ROOMS: DurableObjectNamespace<SyncRoom>;
  FILE_BUCKET: R2Bucket;
}

// ---------------------------------------------------------------------------
// WebSocket signal payload types
// ---------------------------------------------------------------------------

interface EncryptedPayload {
  type:
    | "SYNC"
    | "OTP"
    | "GET_LATEST"
    | "LATEST_DATA"
    | "PING"
    | "PONG"
    | "ACK"
    | "FILE_OFFER"
    | "FILE_ACCEPT"
    | "FILE_REJECT"
    | "FILE_LAN_READY"
    | "FILE_INTERNET_READY"
    | "FILE_PROGRESS"
    | "FILE_COMPLETE"
    | "FILE_ERROR";
  roomId: string;
  iv?: string;
  ciphertext?: string;
  hash?: string;
  originDeviceId?: string;
  timestamp?: number;
  // File transfer signal fields (plaintext metadata — file bytes are always E2EE)
  transferId?: string;
  fileName?: string;
  fileSize?: number;
  mimeType?: string;
  token?: string;       // Short-lived per-transfer access token
  ip?: string;
  port?: number;
  downloadUrl?: string;
  bytesReceived?: number;
  reason?: string;
}

// ---------------------------------------------------------------------------
// Durable Object: SyncRoom (WebSocket relay — unchanged logic)
// ---------------------------------------------------------------------------

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

      if (data.type === "SYNC" || data.type === "OTP") {
        if (!data.ciphertext || !data.iv) return;

        const broadcastPayload: EncryptedPayload = {
          type: data.type,
          roomId: data.roomId,
          iv: data.iv,
          ciphertext: data.ciphertext,
          hash: data.hash,
          originDeviceId: data.originDeviceId,
          timestamp: data.timestamp || Date.now()
        };

        // Only store standard clipboard in catchup buffer (do not persist OTP in catchup)
        if (data.type === "SYNC") {
          this.lastBuffer = broadcastPayload;
        }

        // Broadcast to all other connected sockets in this room
        const sockets = this.ctx.getWebSockets();
        for (const targetWs of sockets) {
          if (targetWs !== ws) {
            try {
              targetWs.send(JSON.stringify(broadcastPayload));
            } catch (err) {
              // Socket might be closing
            }
          }
        }

        // Acknowledge sender if hash exists
        if (data.hash) {
          ws.send(JSON.stringify({ type: "ACK", hash: data.hash }));
        }
        return;
      }

      // File transfer signals — relay as-is to all other peers in this room
      const FILE_SIGNAL_TYPES: EncryptedPayload["type"][] = [
        "FILE_OFFER",
        "FILE_ACCEPT",
        "FILE_REJECT",
        "FILE_LAN_READY",
        "FILE_INTERNET_READY",
        "FILE_PROGRESS",
        "FILE_COMPLETE",
        "FILE_ERROR",
      ];

      if (FILE_SIGNAL_TYPES.includes(data.type)) {
        const sockets = this.ctx.getWebSockets();
        for (const targetWs of sockets) {
          if (targetWs !== ws) {
            try {
              targetWs.send(message);
            } catch (err) {
              // Socket might be closing
            }
          }
        }
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

// ---------------------------------------------------------------------------
// R2 file transfer helpers
// ---------------------------------------------------------------------------

const FILE_TTL_MS = 60 * 60 * 1000; // 1 hour

function corsHeaders(): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-Transfer-Token, Content-Length",
  };
}

function jsonResponse(body: object, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...corsHeaders() },
  });
}

// ---------------------------------------------------------------------------
// Worker entry point
// ---------------------------------------------------------------------------

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // CORS pre-flight
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    // Health check endpoint
    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response(
        JSON.stringify({ status: "ok", service: "clipboard-sync-relay", version: "1.1.0" }),
        { headers: { "Content-Type": "application/json" } }
      );
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

    // -----------------------------------------------------------------------
    // File Transfer: Upload encrypted file to R2
    //
    // PUT /file/:transferId?token=<TOKEN>
    //   Body: raw encrypted file bytes (streamed, never buffered in Worker)
    //   Headers: Content-Type (original mime), X-File-Name, X-File-Size
    //
    // The token is stored as R2 custom metadata. The receiver must present
    // the same token to download. Token is shared only via E2EE WebSocket.
    // -----------------------------------------------------------------------
    if (url.pathname.startsWith("/file/") && request.method === "PUT") {
      const transferId = url.pathname.slice("/file/".length).split("/")[0];
      const token = url.searchParams.get("token");

      if (!transferId || !token || token.length < 16) {
        return jsonResponse({ error: "Missing or invalid transferId or token" }, 400);
      }

      if (!request.body) {
        return jsonResponse({ error: "Request body is empty" }, 400);
      }

      const fileName = request.headers.get("X-File-Name") ?? "file";
      const fileSize = request.headers.get("X-File-Size") ?? "0";
      const contentType = request.headers.get("Content-Type") ?? "application/octet-stream";

      // Stream directly to R2 — Worker never buffers the bytes
      await env.FILE_BUCKET.put(transferId, request.body, {
        httpMetadata: { contentType },
        customMetadata: {
          token,
          fileName,
          fileSize,
          uploadedAt: String(Date.now()),
        },
      });

      return jsonResponse({ ok: true, transferId });
    }

    // -----------------------------------------------------------------------
    // File Transfer: Download encrypted file from R2
    //
    // GET /file/:transferId?token=<TOKEN>
    //   Returns: raw encrypted file bytes (streamed from R2)
    // -----------------------------------------------------------------------
    if (url.pathname.startsWith("/file/") && request.method === "GET") {
      const transferId = url.pathname.slice("/file/".length).split("/")[0];
      const token = url.searchParams.get("token");

      if (!transferId || !token) {
        return jsonResponse({ error: "Missing transferId or token" }, 400);
      }

      const object = await env.FILE_BUCKET.get(transferId);

      if (!object) {
        return jsonResponse({ error: "Transfer not found or already deleted" }, 404);
      }

      // Verify access token
      if (object.customMetadata?.token !== token) {
        return jsonResponse({ error: "Invalid token" }, 403);
      }

      // Enforce TTL — reject expired files
      const uploadedAt = Number(object.customMetadata?.uploadedAt ?? 0);
      if (Date.now() - uploadedAt > FILE_TTL_MS) {
        // Clean up expired object
        await env.FILE_BUCKET.delete(transferId);
        return jsonResponse({ error: "Transfer expired" }, 410);
      }

      const fileName = object.customMetadata?.fileName ?? "file";

      // Stream R2 object body directly to client
      return new Response(object.body, {
        status: 200,
        headers: {
          "Content-Type": object.httpMetadata?.contentType ?? "application/octet-stream",
          "Content-Length": String(object.size),
          "Content-Disposition": `attachment; filename="${encodeURIComponent(fileName)}"`,
          "X-File-Name": fileName,
          "X-Transfer-Id": transferId,
          ...corsHeaders(),
        },
      });
    }

    // -----------------------------------------------------------------------
    // File Transfer: Delete file from R2 after successful transfer
    //
    // DELETE /file/:transferId?token=<TOKEN>
    // -----------------------------------------------------------------------
    if (url.pathname.startsWith("/file/") && request.method === "DELETE") {
      const transferId = url.pathname.slice("/file/".length).split("/")[0];
      const token = url.searchParams.get("token");

      if (!transferId || !token) {
        return jsonResponse({ error: "Missing transferId or token" }, 400);
      }

      const object = await env.FILE_BUCKET.head(transferId);

      if (object && object.customMetadata?.token !== token) {
        return jsonResponse({ error: "Invalid token" }, 403);
      }

      await env.FILE_BUCKET.delete(transferId);
      return jsonResponse({ ok: true, deleted: transferId });
    }

    return new Response("Not Found", { status: 404 });
  }
};

