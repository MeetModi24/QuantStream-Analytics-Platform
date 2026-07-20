import { useEffect, useRef } from 'react';
import { createChart, ColorType } from 'lightweight-charts';
import type { Candle } from '@/lib/api';

interface CandlestickChartProps {
  data: Candle[];
  symbol: string;
}

export function CandlestickChart({ data, symbol }: CandlestickChartProps) {
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<any>(null);
  const candlestickSeriesRef = useRef<any>(null);

  useEffect(() => {
    if (!chartContainerRef.current) return;

    const chart = createChart(chartContainerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: '#111827' },
        textColor: '#9CA3AF',
      },
      grid: {
        vertLines: { color: '#1F2937' },
        horzLines: { color: '#1F2937' },
      },
      width: chartContainerRef.current.clientWidth,
      height: 500,
      timeScale: {
        timeVisible: true,
        secondsVisible: false,
        borderColor: '#1F2937',
      },
      rightPriceScale: {
        borderColor: '#1F2937',
      },
      crosshair: {
        vertLine: {
          color: '#374151',
          labelBackgroundColor: '#374151',
        },
        horzLine: {
          color: '#374151',
          labelBackgroundColor: '#374151',
        },
      },
    });

    const candlestickSeries = chart.addCandlestickSeries({
      upColor: '#26A69A',
      downColor: '#EF5350',
      borderVisible: false,
      wickUpColor: '#26A69A',
      wickDownColor: '#EF5350',
    });

    chartRef.current = chart;
    candlestickSeriesRef.current = candlestickSeries;

    const handleResize = () => {
      if (chartContainerRef.current && chartRef.current) {
        chartRef.current.applyOptions({
          width: chartContainerRef.current.clientWidth,
        });
      }
    };

    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      chart.remove();
    };
  }, []);

  useEffect(() => {
    if (!candlestickSeriesRef.current || !data.length) return;

    // Convert to chart format and remove duplicates by timestamp
    const chartDataMap = new Map();
    data.forEach((candle) => {
      const time = Math.floor(new Date(candle.timestamp).getTime() / 1000);
      // Keep only the latest candle for each timestamp
      chartDataMap.set(time, {
        time,
        open: candle.open,
        high: candle.high,
        low: candle.low,
        close: candle.close,
      });
    });

    // Convert to array and sort by time (ascending)
    const chartData = Array.from(chartDataMap.values()).sort((a, b) => a.time - b.time);

    candlestickSeriesRef.current.setData(chartData);

    if (chartRef.current?.timeScale) {
      chartRef.current.timeScale().fitContent();
    }
  }, [data]);

  return (
    <div className="relative w-full">
      <div className="absolute left-3 top-3 z-10 rounded-lg bg-card/80 px-3 py-2 backdrop-blur">
        <p className="text-sm font-semibold text-foreground">{symbol}</p>
        <p className="text-xs text-muted-foreground">1m Candles</p>
      </div>
      <div ref={chartContainerRef} className="w-full rounded-lg" />
      <div className="mt-2 flex items-center justify-end gap-2 text-xs text-muted-foreground">
        <span>Powered by</span>
        <a
          href="https://www.tradingview.com/"
          target="_blank"
          rel="noopener noreferrer"
          className="text-primary hover:underline"
        >
          TradingView
        </a>
      </div>
    </div>
  );
}
