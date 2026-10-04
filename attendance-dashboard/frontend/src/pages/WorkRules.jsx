import { useCallback, useEffect, useState } from 'react';
import { api, fmtDate } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import Modal from '../components/Modal.jsx';
import { IconEdit, IconPlus, IconTrash } from '../components/Icons.jsx';

const thisYear = new Date().getFullYear();

function HolidayModal({ initial, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({ date: initial.date ?? '', name: initial.name ?? '' });
  const [error, setError] = useState('');
  const save = async (e) => {
    e?.preventDefault();
    try {
      initial.id ? await api.updateHoliday(initial.id, form) : await api.addHoliday(form);
      toast(initial.id ? 'Holiday updated' : `${form.name} added`);
      onSaved();
    } catch (err) { setError(err.message); }
  };
  return (
    <Modal title={initial.id ? 'Edit holiday' : 'Add holiday'} onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn btn-primary" onClick={save}>Save</button></>}>
      <form className="form-grid" onSubmit={save}>
        <div className="field"><label htmlFor="hd">Date</label>
          <input id="hd" type="date" className="input" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} /></div>
        <div className="field"><label htmlFor="hn">Name</label>
          <input id="hn" className="input" maxLength={80} value={form.name} placeholder="e.g. Diwali"
                 onChange={(e) => setForm({ ...form, name: e.target.value })} /></div>
        <button type="submit" hidden />
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

function ShiftModal({ initial, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({
    name: initial.name ?? '', startTime: initial.startTime?.slice(0, 5) ?? '09:00', endTime: initial.endTime?.slice(0, 5) ?? '17:30',
    graceMinutes: initial.graceMinutes ?? 15, standardHours: initial.standardHours ?? 8,
  });
  const [error, setError] = useState('');
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const overnight = form.endTime && form.startTime && form.endTime <= form.startTime;

  const save = async (e) => {
    e?.preventDefault();
    const body = { ...form, graceMinutes: Number(form.graceMinutes), standardHours: Number(form.standardHours) };
    try {
      initial.id ? await api.updateShift(initial.id, body) : await api.createShift(body);
      toast(initial.id ? 'Shift updated' : `${form.name} shift created`);
      onSaved();
    } catch (err) { setError(err.message); }
  };
  return (
    <Modal title={initial.id ? `Edit ${initial.name} shift` : 'New shift'} onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button><button className="btn btn-primary" onClick={save}>Save</button></>}>
      <form className="form-grid" onSubmit={save}>
        <div className="field full"><label htmlFor="sn">Name</label>
          <input id="sn" className="input" maxLength={40} value={form.name} onChange={set('name')} placeholder="e.g. Evening" /></div>
        <div className="field"><label htmlFor="ss">Starts</label>
          <input id="ss" type="time" className="input" value={form.startTime} onChange={set('startTime')} /></div>
        <div className="field"><label htmlFor="se">Ends</label>
          <input id="se" type="time" className="input" value={form.endTime} onChange={set('endTime')} /></div>
        {overnight && <p className="full muted" style={{ margin: '-6px 0 0', fontSize: 12.5 }}>Ends the next morning (night shift).</p>}
        <div className="field"><label htmlFor="sg">Late after (grace, minutes)</label>
          <input id="sg" type="number" min="0" max="180" className="input" value={form.graceMinutes} onChange={set('graceMinutes')} /></div>
        <div className="field"><label htmlFor="sh">Standard hours (overtime above)</label>
          <input id="sh" type="number" min="1" max="16" step="0.5" className="input" value={form.standardHours} onChange={set('standardHours')} /></div>
        <button type="submit" hidden />
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}

export default function WorkRules() {
  const toast = useToast();
  const [year, setYear] = useState(thisYear);
  const [holidays, setHolidays] = useState(null);
  const [shifts, setShifts] = useState(null);
  const [editingHoliday, setEditingHoliday] = useState(null);
  const [editingShift, setEditingShift] = useState(null);

  const loadHolidays = useCallback(() => api.holidays(year).then(setHolidays).catch((e) => toast(e.message, 'error')), [year, toast]);
  const loadShifts = useCallback(() => api.shifts().then(setShifts).catch((e) => toast(e.message, 'error')), [toast]);
  useEffect(() => { loadHolidays(); }, [loadHolidays]);
  useEffect(() => { loadShifts(); }, [loadShifts]);

  const addNational = async () => {
    try {
      const added = await api.addNationalHolidays(year);
      toast(added.length ? `Added ${added.length} national holiday${added.length > 1 ? 's' : ''}` : 'National holidays are already in the list');
      loadHolidays();
    } catch (e) { toast(e.message, 'error'); }
  };
  const removeHoliday = async (h) => {
    try { await api.deleteHoliday(h.id); toast(`${h.name} removed`); loadHolidays(); } catch (e) { toast(e.message, 'error'); }
  };
  const removeShift = async (s) => {
    try { await api.deleteShift(s.id); toast(`${s.name} shift deleted`); loadShifts(); } catch (e) { toast(e.message, 'error'); }
  };
  const today = new Date().toISOString().slice(0, 10);

  return (
    <>
      <div className="page-head">
        <div><h1>Holidays &amp; shifts</h1><p>Holidays are skipped in attendance and leave. Shifts decide when someone is late and what counts as overtime.</p></div>
      </div>

      <div className="grid-2 even">
        <section className="card">
          <div className="card-head">
            <div><h2>Holidays {year}</h2><p>{holidays ? `${holidays.length} day${holidays.length === 1 ? '' : 's'}` : ''}</p></div>
            <div className="head-actions">
              <div className="seg" role="group" aria-label="Year">
                {[thisYear - 1, thisYear, thisYear + 1].map((y) => <button key={y} aria-pressed={year === y} onClick={() => setYear(y)}>{y}</button>)}
              </div>
            </div>
          </div>
          <div className="toolbar" style={{ borderBottom: 0, paddingBottom: 6 }}>
            <button className="btn btn-sm btn-primary" onClick={() => setEditingHoliday({})}><IconPlus />Add holiday</button>
            <button className="btn btn-sm" onClick={addNational}>Add national holidays</button>
            <span className="muted" style={{ fontSize: 12 }}>26 Jan, 15 Aug, 2 Oct, 25 Dec — add festivals like Holi or Diwali yourself</span>
          </div>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Date</th><th>Holiday</th><th className="num"></th></tr></thead>
              <tbody>
                {holidays?.map((h) => (
                  <tr key={h.id} style={h.date < today ? { opacity: 0.6 } : undefined}>
                    <td className="tabular">{fmtDate(h.date, { weekday: 'short', day: 'numeric', month: 'short' })}</td>
                    <td>{h.name}</td>
                    <td className="num">
                      <button className="icon-btn" aria-label={`Edit ${h.name}`} title="Edit" onClick={() => setEditingHoliday(h)}><IconEdit /></button>
                      <button className="icon-btn" aria-label={`Delete ${h.name}`} title="Delete" onClick={() => removeHoliday(h)}><IconTrash /></button>
                    </td>
                  </tr>
                ))}
                {holidays && !holidays.length && <tr><td colSpan="3" className="empty">No holidays for {year} yet</td></tr>}
              </tbody>
            </table>
          </div>
        </section>

        <section className="card">
          <div className="card-head">
            <div><h2>Shifts</h2><p>Assign a shift on the Employees page. Staff without one use the default hours (09:30, 8 h).</p></div>
            <button className="btn btn-sm btn-primary" onClick={() => setEditingShift({})}><IconPlus />New shift</button>
          </div>
          <div className="table-wrap" style={{ marginTop: 8 }}>
            <table>
              <thead><tr><th>Shift</th><th>Hours</th><th className="num">Grace</th><th className="num">Standard</th><th className="num">Staff</th><th className="num"></th></tr></thead>
              <tbody>
                {shifts?.map((s) => (
                  <tr key={s.id}>
                    <td><strong>{s.name}</strong>{s.overnight && <span className="badge NOT_MARKED" style={{ marginLeft: 6 }}>overnight</span>}</td>
                    <td className="tabular">{s.startTime.slice(0, 5)}–{s.endTime.slice(0, 5)}</td>
                    <td className="num">{s.graceMinutes} min</td>
                    <td className="num">{s.standardHours} h</td>
                    <td className="num">{s.employeeCount}</td>
                    <td className="num">
                      <button className="icon-btn" aria-label={`Edit ${s.name}`} title="Edit" onClick={() => setEditingShift(s)}><IconEdit /></button>
                      <button className="icon-btn" aria-label={`Delete ${s.name}`} title={s.employeeCount ? 'In use' : 'Delete'}
                              disabled={s.employeeCount > 0} onClick={() => removeShift(s)}><IconTrash /></button>
                    </td>
                  </tr>
                ))}
                {shifts && !shifts.length && <tr><td colSpan="6" className="empty">No shifts</td></tr>}
              </tbody>
            </table>
          </div>
        </section>
      </div>

      {editingHoliday && <HolidayModal initial={editingHoliday} onClose={() => setEditingHoliday(null)}
                                       onSaved={() => { setEditingHoliday(null); loadHolidays(); }} />}
      {editingShift && <ShiftModal initial={editingShift} onClose={() => setEditingShift(null)}
                                   onSaved={() => { setEditingShift(null); loadShifts(); }} />}
    </>
  );
}
