import { useEffect, useMemo, useState } from 'react';
import { api, toIso, todayIso } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import Person from '../components/Person.jsx';
import { IconDownload } from '../components/Icons.jsx';

function presetRange(key) {
  const now = new Date();
  const y = now.getFullYear(), m = now.getMonth();
  switch (key) {
    case 'month': return [toIso(new Date(y, m, 1)), todayIso()];
    case 'last-month': return [toIso(new Date(y, m - 1, 1)), toIso(new Date(y, m, 0))];
    case '7': { const d = new Date(); d.setDate(d.getDate() - 6); return [toIso(d), todayIso()]; }
    default: { const d = new Date(); d.setDate(d.getDate() - 29); return [toIso(d), todayIso()]; }
  }
}

const COLUMNS = [
  { key: 'employeeName', label: 'Employee' },
  { key: 'workingDays', label: 'Days', num: true },
  { key: 'present', label: 'Present', num: true },
  { key: 'late', label: 'Late', num: true },
  { key: 'halfDay', label: 'Half day', num: true },
  { key: 'onLeave', label: 'Leave', num: true },
  { key: 'absent', label: 'Absent', num: true },
  { key: 'totalHours', label: 'Hours', num: true },
  { key: 'attendanceRate', label: 'Attendance', num: true },
];

export default function Reports() {
  const toast = useToast();
  const [preset, setPreset] = useState('30');
  const [[from, to], setRange] = useState(presetRange('30'));
  const [rows, setRows] = useState([]);
  const [sort, setSort] = useState({ key: 'attendanceRate', dir: 'desc' });
  const [dept, setDept] = useState('');

  useEffect(() => {
    if (from && to && from <= to) api.summary(from, to).then(setRows).catch((e) => toast(e.message, 'error'));
  }, [from, to, toast]);

  const choose = (p) => { setPreset(p); setRange(presetRange(p)); };
  const departments = [...new Set(rows.map((r) => r.department))].sort();

  const sorted = useMemo(() => {
    const list = rows.filter((r) => !dept || r.department === dept);
    const { key, dir } = sort;
    return [...list].sort((a, b) => {
      const v = typeof a[key] === 'string' ? a[key].localeCompare(b[key]) : a[key] - b[key];
      return dir === 'asc' ? v : -v;
    });
  }, [rows, sort, dept]);

  const totals = sorted.reduce((t, r) => ({
    late: t.late + r.late, absent: t.absent + r.absent, hours: t.hours + r.totalHours, rate: t.rate + r.attendanceRate,
  }), { late: 0, absent: 0, hours: 0, rate: 0 });

  const toggleSort = (key) => setSort((s) => ({ key, dir: s.key === key && s.dir === 'desc' ? 'asc' : 'desc' }));

  return (
    <>
      <div className="page-head">
        <div><h1>Reports</h1><p>Per-employee attendance summary</p></div>
        <a className="btn" href={api.summaryCsvUrl(from, to)} download><IconDownload />Export CSV</a>
      </div>

      <div className="stats">
        <div className="card stat"><div className="stat-label">Average attendance</div>
          <div className="stat-value">{sorted.length ? (totals.rate / sorted.length).toFixed(1) : 0}%</div></div>
        <div className="card stat"><div className="stat-label">Late arrivals</div><div className="stat-value">{totals.late}</div></div>
        <div className="card stat"><div className="stat-label">Absences</div><div className="stat-value">{totals.absent}</div></div>
        <div className="card stat"><div className="stat-label">Hours logged</div><div className="stat-value">{Math.round(totals.hours).toLocaleString('en-IN')}</div></div>
      </div>

      <section className="card">
        <div className="toolbar">
          <div className="seg" role="group" aria-label="Period">
            {[['7', 'Last 7 days'], ['30', 'Last 30 days'], ['month', 'This month'], ['last-month', 'Last month']].map(([k, l]) => (
              <button key={k} aria-pressed={preset === k} onClick={() => choose(k)}>{l}</button>
            ))}
          </div>
          <input type="date" className="input" value={from} max={to} aria-label="From"
                 onChange={(e) => { setPreset(''); setRange([e.target.value, to]); }} />
          <span className="muted">to</span>
          <input type="date" className="input" value={to} min={from} max={todayIso()} aria-label="To"
                 onChange={(e) => { setPreset(''); setRange([from, e.target.value]); }} />
          <select className="select" value={dept} onChange={(e) => setDept(e.target.value)} aria-label="Department">
            <option value="">All departments</option>
            {departments.map((d) => <option key={d}>{d}</option>)}
          </select>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                {COLUMNS.map((c) => (
                  <th key={c.key} className={c.num ? 'num' : ''} style={{ cursor: 'pointer' }} onClick={() => toggleSort(c.key)}
                      aria-sort={sort.key === c.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
                    {c.label}{sort.key === c.key ? (sort.dir === 'asc' ? ' ↑' : ' ↓') : ''}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.map((r) => (
                <tr key={r.employeeId}>
                  <td><Person name={r.employeeName} sub={`${r.employeeCode} · ${r.department}`} /></td>
                  <td className="num">{r.workingDays}</td>
                  <td className="num">{r.present}</td>
                  <td className="num">{r.late}</td>
                  <td className="num">{r.halfDay}</td>
                  <td className="num">{r.onLeave}</td>
                  <td className="num">{r.absent}</td>
                  <td className="num">{r.totalHours.toFixed(1)}</td>
                  <td className="num">
                    <span className="bar-track"><span className="bar-fill" style={{ width: `${r.attendanceRate}%`, display: 'block' }} /></span>
                    {r.attendanceRate.toFixed(1)}%
                  </td>
                </tr>
              ))}
              {!sorted.length && <tr><td colSpan={COLUMNS.length} className="empty">No data for this period</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
