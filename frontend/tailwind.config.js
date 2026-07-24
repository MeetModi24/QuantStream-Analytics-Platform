/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Deep blue-black professional trading palette (see FRONTEND-RESEARCH-NOTES).
        bg: "#0A0E1A",
        surface: "#111827",
        "surface-2": "#161F30",
        border: "#1F2937",
        "text-primary": "#F9FAFB",
        "text-secondary": "#9CA3AF",
        "text-muted": "#6B7280",
        // Semantic
        buy: "#10B981",
        sell: "#EF4444",
        hold: "#F59E0B",
        info: "#3B82F6",
        // Chart accents
        "candle-up": "#26A69A",
        "candle-down": "#EF5350",
        accent: "#5B8DEF",
      },
      fontFamily: {
        sans: ["Inter", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "sans-serif"],
        mono: ["JetBrains Mono", "SFMono-Regular", "Menlo", "monospace"],
      },
      fontSize: {
        "2xs": "0.6875rem",
      },
      borderRadius: {
        card: "0.5rem",
      },
      transitionDuration: {
        DEFAULT: "200ms",
      },
      keyframes: {
        "fade-in": {
          "0%": { opacity: "0", transform: "translateY(-2px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        "pulse-dot": {
          "0%, 100%": { opacity: "1" },
          "50%": { opacity: "0.35" },
        },
      },
      animation: {
        "fade-in": "fade-in 200ms ease-out",
        "pulse-dot": "pulse-dot 1.6s ease-in-out infinite",
      },
    },
  },
  plugins: [],
};
