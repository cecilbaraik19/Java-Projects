import { useState } from 'react';
import { api, todayIso, toIso } from '../api.js';
import { useToast } from './Toast.jsx';
import Modal from './Modal.jsx';

/** Ask a manager to fix check-in/out times for a day. */
export default function CorrectionModal({ row, onClose, onSaved }) {
  const toast = useToast();
  const minDate = (() => { const d = new Date(); d.setDate(d.getDate() - 30); return toIso(d); })();
  const [form, setForm] = useState({
    date: row?.date ?? todayIso(),
    checkIn: row?.checkIn?.slice(0, 5) ?? '09:30',
    checkOut: row?.checkOut?.slice(0, 5) ?? '18:00',
    reason: '',
  });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const save = async (e) => {
    e?.preventDefault();
    setBusy(true); setError('');
    try {
      await api.requestCorrection({ ...form, checkOut: form.checkOut || null });
      toast('Correction sent to your manager for approval');
      onSaved();
    } catch (err) { setError(err.message); }
    setBusy(false);
  };

  return (
    <Modal title="Request a correction" onClose={onClose}
           footer={<><button className="btn" onClick={onClose}>Cancel</button>
             <button className="btn btn-primary" onClick={save} disabled={busy}>{busy ? 'Sending…' : 'Send request'}</button></>}>
      <form className="form-grid" onSubmit={save}>
        <div className="field full"><label htmlFor="cd">Date</label>
          <input id="cd" type="date" className="input" value={form.date} min={minDate} max={todayIso()} onChange={set('date')} /></div>
        <div className="field"><label htmlFor="ci">Actual check-in *</label>
          <input id="ci" type="time" className="input" value={form.checkIn} onChange={set('checkIn')} /></div>
        <div className="field"><label htmlFor="co">Actual check-out</label>
          <input id="co" type="time" className="input" value={form.checkOut} onChange={set('checkOut')} /></div>
        <div className="field full"><label htmlFor="cr">Reason *</label>
          <input id="cr" className="input" value={form.reason} onChange={set('reason')} maxLength={300}
                 placeholder="e.g. Forgot to check out, card reader not working" /></div>
        <button type="submit" hidden />
      </form>
      {error && <div className="error-text">{error}</div>}
    </Modal>
  );
}
