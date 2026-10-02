import { Line, LineChart, ResponsiveContainer, YAxis } from 'recharts'

export default function Sparkline({ data = [], dataKey = 'cpu', color = 'var(--accent)', height = 36, domain = [0, 100] }) {
  return (
    <div style={{ height }} className="w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 2, right: 2, left: 2, bottom: 2 }}>
          <YAxis hide domain={domain} />
          <Line type="monotone" dataKey={dataKey} stroke={color} strokeWidth={2} dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
