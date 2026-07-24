import { useEffect, useRef } from "react";
import {
  createChart,
  type CandlestickData,
  type IChartApi,
  type ISeriesApi,
  type SeriesMarker,
  type Time,
  type UTCTimestamp,
} from "lightweight-charts";
import type { CandleRow, SignalRow } from "@/lib/types";

const CHART_THEME = {
  layout: {
    background: { color: "transparent" },
    textColor: "#9CA3AF",
    fontFamily: "Inter, sans-serif",
  },
  grid: {
    vertLines: { color: "rgba(31,41,55,0.5)" },
    horzLines: { color: "rgba(31,41,55,0.5)" },
  },
  rightPriceScale: { borderColor: "#1F2937" },
  timeScale: { borderColor: "#1F2937", timeVisible: true, secondsVisible: false },
  crosshair: { mode: 0 as const },
};

function toTime(iso: string): UTCTimestamp {
  return Math.floor(new Date(iso).getTime() / 1000) as UTCTimestamp;
}

/** Candlestick chart of microprice OHLC with BUY/SELL/CLOSE markers overlaid. */
export function PriceChart({
  candles,
  signals,
  height = 380,
}: {
  candles: CandleRow[];
  signals: SignalRow[];
  height?: number;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<"Candlestick"> | null>(null);

  // Create chart once.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = createChart(el, { ...CHART_THEME, height, autoSize: true });
    const series = chart.addCandlestickSeries({
      upColor: "#26A69A",
      downColor: "#EF5350",
      borderUpColor: "#26A69A",
      borderDownColor: "#EF5350",
      wickUpColor: "#26A69A",
      wickDownColor: "#EF5350",
    });
    chartRef.current = chart;
    seriesRef.current = series;
    return () => {
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, [height]);

  // Push data whenever candles/signals change.
  useEffect(() => {
    const series = seriesRef.current;
    if (!series) return;

    // De-dupe by time (SAMPLE BY can share the last open bucket) and sort ascending.
    const byTime = new Map<number, CandlestickData<Time>>();
    for (const c of candles) {
      const t = toTime(c.ts);
      byTime.set(t, { time: t, open: c.open, high: c.high, low: c.low, close: c.close });
    }
    const data = [...byTime.values()].sort((a, b) => (a.time as number) - (b.time as number));
    series.setData(data);

    // Markers: one per signal, snapped onto the candle timeline.
    const markers: SeriesMarker<Time>[] = signals
      .slice()
      .sort((a, b) => toTime(a.ts) - toTime(b.ts))
      .map((s) => ({
        time: toTime(s.ts),
        position: s.action === "SELL" ? "aboveBar" : "belowBar",
        color: s.action === "BUY" ? "#10B981" : s.action === "SELL" ? "#EF4444" : "#F59E0B",
        shape: s.action === "SELL" ? "arrowDown" : s.action === "BUY" ? "arrowUp" : "circle",
        text: s.action[0],
      }));
    series.setMarkers(markers);

    chartRef.current?.timeScale().fitContent();
  }, [candles, signals]);

  return <div ref={containerRef} style={{ height }} className="w-full" />;
}
