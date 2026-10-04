import { STATUS_LABEL } from '../api.js';

/** Shared tooltip: title + one row per series (swatch, label, value). */
export default function ChartTooltip({ active, payload, label, labelFormatter, valueSuffix = '' }) {
  if (!active || !payload?.length) return null;
  const rows = [...payload].reverse(); // match visual stacking order (top first)
  return (
    <div className="tip">
      <div className="tip-title">{labelFormatter ? labelFormatter(label, payload) : label}</div>
      {rows.map((p) => (
        <div className="tip-row" key={p.dataKey}>
          <span><i className="dot" style={{ background: p.color ?? p.fill }} />{STATUS_LABEL[p.dataKey] ?? p.name}</span>
          <strong className="tabular">{p.value}{valueSuffix}</strong>
        </div>
      ))}
    </div>
  );
}
