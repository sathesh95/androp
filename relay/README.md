# Cloudflare Worker Encrypted Relay

A zero-knowledge, end-to-end encrypted WebSocket relay running on Cloudflare Workers using the WebSocket Hibernation API ($0 cost / zero idle CPU).

## Architecture & Security
- **Zero-Knowledge**: The worker only routes opaque `{ iv, ciphertext, hash }` payloads. It never possesses the decryption key.
- **WebSocket Hibernation**: Sockets are put to sleep by the Cloudflare edge runtime until a frame arrives, keeping CPU time and cost at $0.
- **Ephemeral Catch-Up Buffer**: Remembers the latest 1 encrypted clipboard item in memory so newly woken devices sync immediately without waiting for a new copy event.

## Deployment (1-Step)

```bash
cd relay
npm install
npx wrangler deploy
```

Once deployed, wrangler will output your worker URL, e.g.:
`wss://clipboard-sync-relay.<your-subdomain>.workers.dev/ws?room=<ROOM_ID>`
