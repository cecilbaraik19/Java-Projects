import { useCallback, useEffect, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, fmtDate, fmtTime, STATUS_LABEL } from '../api.js';
import { useChartColors } from '../theme.jsx';
import ChartTooltip from '../components/ChartTooltip.jsx';
import { useAuth } from '../auth.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import Person from '../components/Person.jsx';

const SERIES = ['PRESENT', 'LATE', 'HALF_DAY', 'ON_LEAVE', 'ABSENT'];

function useNow() {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}

function Stat({ label, value, sub, color }) {
  return (
    <div className="card stat">
      <div className="stat-label">{color && <i className="dot" style={{ background: color }} />}{label}</div>
      <div className="stat-value">{value ?? <span className="skeleton" style={{ display: 'inline-block', width: 50, height: 28 }} />}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}

export default function Dashboard() {
  const c = useChartColors();
  const now = useNow();
  const { user } = useAuth();
  const scope = user.role === 'MANAGER' ? `${user.department ?? 'No'} team · ` : '';
  const [days, setDays] = useState(14);
  const [stats, setStats] = useState(null);
  const [trend, setTrend] = useState([]);
  const [depts, setDepts] = useState([]);
  const [board, setBoard] = useState([]);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const [s, t, d, b] = await Promise.all([api.stats(), api.trend(days), api.deptStats(), api.daily()]);
      setStats(s); setTrend(t); setDepts(d); setBoard(b); setError('');
    } catch (e) {
      if (e.status === 401) return; // handled globally (back to login)
      setError(!e.status || e.status >= 500 ? 'Cannot reach the backend. Is Spring Boot running on port 8080?' : e.message);
    }
  }, [days]);

  useEffect(() => {
    load();
    const id = setInterval(load, 30000); // live refresh
    return () => clearInterval(id);
  }, [load]);

  const checkedIn = stats ? stats.present + stats.late + stats.halfDay : null;
  const recent = board.filter((r) => r.checkIn).sort((a, b) => b.checkIn.localeCompare(a.checkIn)).slice(0, 8);
  const notYet = board.filter((r) => r.status === 'NOT_MARKED');

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Dashboard</h1>
          <p>{scope}{now.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}</p>
        </div>
        <div className="head-actions">
          <span className="clock">{now.toLocaleTimeString('en-IN')}</span>
          <span className="live"><i className="dot" />Live · refreshes every 30s</span>
        </div>
      </div>

      {error && <div className="card card-pad error-text" style={{ marginBottom: 18 }}>{error}</div>}
      {stats && !stats.workingDay && (
        <div className="card card-pad muted" style={{ marginBottom: 18 }}>Today is a weekend — attendance is optional.</div>
      )}

      <div className="stats">
        <Stat label="Total employees" value={stats?.totalEmployees} sub="Active staff" />
        <Stat label="Checked in" value={checkedIn} color={c.PRESENT}
              sub={stats ? `${stats.stillInOffice} still in office` : ''} />
        <Stat label="Late arrivals" value={stats?.late} color={c.LATE}
              sub={stats?.averageCheckIn ? `Avg check-in ${fmtTime(stats.averageCheckIn)}` : 'No check-ins yet'} />
        <Stat label="On leave" value={stats?.onLeave} color={c.ON_LEAVE} sub={stats ? `${stats.halfDay} half day` : ''} />
        <Stat label="Absent / not in" value={stats?.absent} color={c.ABSENT} sub="Not checked in" />
        <Stat label="Attendance rate" value={stats ? `${stats.attendanceRate}%` : null} sub="Excludes staff on leave" />
      </div>

      <div className="grid-2">
        <section className="card">
          <div className="card-head">
            <div>
              <h2>Daily attendance</h2>
              <p>Employees by status, last {days} working days</p>
            </div>
            <div className="seg" role="group" aria-label="Range">
              {[7, 14, 30].map((d) => (
                <button key={d} aria-pressed={days === d} onClick={() => setDays(d)}>{d}d</button>
              ))}
            </div>
          </div>
          <div className="card-pad" style={{ paddingBottom: 0, paddingTop: 12 }}>
            <div className="legend">
              {SERIES.map((k) => <span key={k}><i style={{ background: c[k] }} />{STATUS_LABEL[k]}</span>)}
            </div>
          </div>
          <div className="chart-box">
            <ResponsiveContainer>
              <BarChart data={trend} margin={{ top: 10, right: 8, left: 0, bottom: 0 }} barCategoryGap="22%">
                <CartesianGrid vertical={false} stroke={c.grid} />
                <XAxis dataKey="date" tickFormatter={(d) => fmtDate(d)} tick={{ fill: c.axis, fontSize: 12 }}
                       axisLine={{ stroke: c.grid }} tickLine={false} minTickGap={16} />
                <YAxis allowDecimals={false} tick={{ fill: c.axis, fontSize: 12 }} axisLine={false} tickLine={false} width={32} />
                <Tooltip cursor={{ fill: c.cursor }}
                         content={<ChartTooltip labelFormatter={(d, p) =>
                           `${fmtDate(d, { weekday: 'short', day: 'numeric', month: 'short' })} · ${p?.[0]?.payload.attendanceRate}%`} />} />
                {SERIES.map((k, i) => (
                  <Bar key={k} dataKey={k === 'HALF_DAY' ? 'halfDay' : k === 'ON_LEAVE' ? 'onLeave' : k.toLowerCase()}
                       name={STATUS_LABEL[k]} stackId="a" fill={c[k]} stroke={c.surface} strokeWidth={1.5}
                       radius={i === SERIES.length - 1 ? [4, 4, 0, 0] : 0} isAnimationActive={false} />
                ))}
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>

        <section className="card">
          <div className="card-head">
            <div>
              <h2>By department</h2>
              <p>Attendance rate today</p>
            </div>
          </div>
          <div className="chart-box">
            <ResponsiveContainer>
              <BarChart data={depts} layout="vertical" margin={{ top: 12, right: 40, left: 8, bottom: 0 }} barCategoryGap="30%">
                <CartesianGrid horizontal={false} stroke={c.grid} />
                <XAxis type="number" domain={[0, 100]} tickFormatter={(v) => `${v}%`} tick={{ fill: c.axis, fontSize: 12 }}
                       axisLine={false} tickLine={false} />
                <YAxis type="category" dataKey="department" tick={{ fill: c.axis, fontSize: 12 }} axisLine={false}
                       tickLine={false} width={84} />
                <Tooltip cursor={{ fill: c.cursor }} content={({ active, payload }) => active && payload?.length ? (
                  <div className="tip">
                    <div className="tip-title">{payload[0].payload.department}</div>
                    <div className="tip-row"><span>Rate</span><strong>{payload[0].payload.attendanceRate}%</strong></div>
                    <div className="tip-row"><span>Checked in</span><strong>{payload[0].payload.attended} / {payload[0].payload.total}</strong></div>
                    <div className="tip-row"><span>On leave</span><strong>{payload[0].payload.onLeave}</strong></div>
                  </div>) : null} />
                <Bar dataKey="attendanceRate" radius={[0, 4, 4, 0]} isAnimationActive={false}
                     label={{ position: 'right', fill: c.axis, fontSize: 12, formatter: (v) => `${Math.round(v)}%` }}>
                  {depts.map((d) => <Cell key={d.department} fill={c.accent} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>
      </div>

      <div className="grid-2">
        <section className="card">
          <div className="card-head"><div><h2>Recent check-ins</h2><p>Latest arrivals today</p></div></div>
          <div className="table-wrap" style={{ marginTop: 8 }}>
            <table>
              <thead><tr><th>Employee</th><th>Check in</th><th>Check out</th><th>Status</th></tr></thead>
              <tbody>
                {recent.map((r) => (
                  <tr key={r.employeeId}>
                    <td><Person name={r.employeeName} sub={r.department} /></td>
                    <td className="tabular">{fmtTime(r.checkIn)}</td>
                    <td className="tabular">{fmtTime(r.checkOut)}</td>
                    <td><StatusBadge status={r.status} /></td>
                  </tr>
                ))}
                {!recent.length && <tr><td colSpan="4" className="empty">No check-ins yet today</td></tr>}
              </tbody>
            </table>
          </div>
        </section>

        <section className="card">
          <div className="card-head"><div><h2>Not checked in</h2><p>{notYet.length} employee{notYet.length === 1 ? '' : 's'}</p></div></div>
          <div className="table-wrap" style={{ marginTop: 8, maxHeight: 400 }}>
            <table>
              <tbody>
                {notYet.map((r) => (
                  <tr key={r.employeeId}><td><Person name={r.employeeName} sub={r.department} /></td></tr>
                ))}
                {!notYet.length && <tr><td className="empty">Everyone is accounted for</td></tr>}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </>
  );
}
