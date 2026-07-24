import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// The FastAPI dashboard-api serves both REST (/api) and the live WebSocket (/ws).
// Vite dev-proxies both to it so the browser talks to a single origin in dev.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8000", changeOrigin: true },
      "/ws": { target: "ws://localhost:8000", ws: true },
    },
  },
});
