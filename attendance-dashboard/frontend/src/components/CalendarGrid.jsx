import { useEffect, useState } from 'react';
import { fmtTime, STATUS_LABEL, todayIso } from '../api.js';

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const EXTRA_LABEL = { HOLIDAY: 'Holiday', WEEKEND: 'Weekend', FUTURE: '', NONE: 'Not joined' };
const LEGEND = ['PRESENT', 'LATE', 'HALF_DAY', 'ON_LEAVE', 'ABSENT', 'HOLIDAY'];

const monthKey = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
const shift = (key, delta) => {
  const [y, m] = key.split('-').map(Number);
  return monthKey(new Date(y, m - 1 + delta, 1));
};

/**
 * Month grid of attendance. `load(month)` must return { days: [{date, status, ...}], totals }.
 */
export default function CalendarGrid({ load }) {
  const [month, setMonth] = useState(monthKey(new Date()));
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const isCurrent = month === monthKey(new Date());

  useEffect(() => {
    let alive = true;
    setData(null); setError('');
    load(month).then((d) => alive && setData(d)).catch((e) => alive && setError(e.message));
    return () => { alive = false; };
  }, [month, load]);

  const [y, m] = month.split('-').map(Number);
  const title = new Date(y, m - 1, 1).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  const lead = (new Date(y, m - 1, 1).getDay() + 6) % 7; // Monday-first offset
  const today = todayIso();
  const t = data?.totals ?? {};

  return (
    <div className="calendar">
      <div className="cal-head">
        <button className="btn btn-sm" onClick={() => setMonth((k) => shift(k, -1))} aria-label="Previous month">‹</button>
        <strong>{title}</strong>
        <button className="btn btn-sm" onClick={() => setMonth((k) => shift(k, 1))} disabled={isCurrent} aria-label="Next month">›</button>
        {!isCurrent && <button className="btn btn-sm" onClick={() => setMonth(monthKey(new Date()))}>This month</button>}
        <span className="cal-totals muted">
          {(t.PRESENT ?? 0) + (t.LATE ?? 0) + (t.HALF_DAY ?? 0)} day{(t.PRESENT ?? 0) + (t.LATE ?? 0) + (t.HALF_DAY ?? 0) === 1 ? '' : 's'} in · {t.LATE ?? 0} late · {t.ABSENT ?? 0} absent · {t.ON_LEAVE ?? 0} leave
        </span>
      </div>

      <div className="cal-grid" role="grid" aria-label={`Attendance for ${title}`}>
        {WEEKDAYS.map((d) => <div key={d} className="cal-wd" role="columnheader">{d}</div>)}
        {Array.from({ length: lead }, (_, i) => <div key={`e${i}`} className="cal-cell empty" />)}
        {data?.days.map((d) => {
          const day = Number(d.date.slice(8));
          const label = STATUS_LABEL[d.status] ?? EXTRA_LABEL[d.status] ?? d.status;
          const tip = [d.holidayName, label, d.checkIn && `In ${fmtTime(d.checkIn)}`, d.checkOut && `Out ${fmtTime(d.checkOut)}`,
            d.hoursWorked ? `${d.hoursWorked} h` : null, d.overtimeHours ? `+${d.overtimeHours} h overtime` : null, d.note]
            .filter(Boolean).join(' · ');
          return (
            <div key={d.date} className={`cal-cell s-${d.status}${d.date === today ? ' today' : ''}`} title={tip} role="gridcell" aria-label={`${d.date}: ${tip || label}`}>
              <span className="cal-day">{day}</span>
              {d.checkIn && <span className="cal-time tabular">{fmtTime(d.checkIn)}</span>}
              {!d.checkIn && d.status === 'HOLIDAY' && <span className="cal-time">{d.holidayName}</span>}
              {!d.checkIn && ['ABSENT', 'ON_LEAVE'].includes(d.status) && <span className="cal-time">{label}</span>}
              {d.overtimeHours > 0 && <span className="cal-ot">+{d.overtimeHours}h</span>}
            </div>
          );
        })}
        {!data && !error && Array.from({ length: 30 }, (_, i) => <div key={`s${i}`} className="cal-cell skeleton" />)}
      </div>
      {error && <div className="error-text">{error}</div>}

      <div className="legend" style={{ marginTop: 10 }}>
        {LEGEND.map((s) => <span key={s}><i className={`cal-swatch s-${s}`} />{STATUS_LABEL[s] ?? EXTRA_LABEL[s]}</span>)}
      </div>
    </div>
  );
}
