import { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import Modal from '../components/Modal.jsx';

const KINDS = {
  MISSING_CHECKIN: 'Missing check-in', WEEKLY_SUMMARY: 'Weekly summary', LEAVE_REQUEST: 'Leave request',
  LEAVE_DECISION: 'Leave decision', CORRECTION_DECISION: 'Correction decision',
};
const STATUS_CLASS = { SENT: 'PRESENT', LOGGED: 'NOT_MARKED', FAILED: 'ABSENT' };

function cronText(cron) {
  // "0 30 10 * * MON-FRI" -> "10:30, MON-FRI"
  const p = (cron || '').split(/\s+/);
  if (p.length < 6) return cron;
  return `${p[2].padStart(2, '0')}:${p[1].padStart(2, '0')}${p[5] !== '*' ? `, ${p[5]}` : ' daily'}`;
}

export default function Notifications() {
  const toast = useToast();
  const [settings, setSettings] = useState(null);
  const [rows, setRows] = useState(null);
  const [kind, setKind] = useState('');
  const [open, setOpen] = useState(null);
  const [busy, setBusy] = useState('');

  const load = useCallback(() => api.notifications(kind).then(setRows).catch((e) => toast(e.message, 'error')), [kind, toast]);
  useEffect(() => { load(); }, [load]);
  useEffect(() => { api.notificationSettings().then(setSettings).catch(() => {}); }, []);

  const run = async (fn, what) => {
    setBusy(what);
    try { const r = await fn(); toast(r.message); load(); } catch (e) { toast(e.message, 'error'); }
    setBusy('');
  };

  return (
    <>
      <div className="page-head">
        <div><h1>Notifications</h1><p>Every alert email the system sends, newest first</p></div>
        <div className="head-actions">
          <button className="btn" disabled={!!busy} onClick={() => run(api.runMissingCheckIn, 'm')}>{busy === 'm' ? 'Sending…' : 'Send missing check-in reminders now'}</button>
          <button className="btn" disabled={!!busy} onClick={() => run(api.runWeeklySummary, 'w')}>{busy === 'w' ? 'Sending…' : 'Send weekly summary now'}</button>
        </div>
      </div>

      {settings && (
        <div className="card card-pad notice-row" style={{ flexDirection: 'column', gap: 6 }}>
          <div>
            Email delivery: {settings.smtpConfigured
              ? <strong>SMTP configured</strong>
              : <><strong>not configured</strong> — messages are only saved here (status “Logged”). Add <code>spring.mail.*</code> settings to send real emails.</>}
          </div>
          <div className="muted" style={{ fontSize: 13 }}>
            Missing check-in reminder: {settings.alertsEnabled ? cronText(settings.missingCheckInCron) : 'off'} ·
            Weekly summary to managers: {settings.alertsEnabled ? cronText(settings.weeklySummaryCron) : 'off'}
            {settings.adminEmail ? ` (and ${settings.adminEmail})` : ''} ·
            Late pattern threshold: {settings.lateThreshold} days
          </div>
        </div>
      )}

      <section className="card">
        <div className="toolbar">
          <select className="select" value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Type">
            <option value="">All types</option>
            {Object.entries(KINDS).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
          </select>
          <span className="muted" style={{ fontSize: 12.5 }}>{rows ? `${rows.length} shown` : ''}</span>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>When</th><th>To</th><th>Type</th><th>Subject</th><th>Status</th></tr></thead>
            <tbody>
              {rows?.map((n) => (
                <tr key={n.id} onClick={() => setOpen(n)} style={{ cursor: 'pointer' }}>
                  <td className="muted tabular">{new Date(n.createdAt).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}</td>
                  <td>{n.recipientName}<div className="muted" style={{ fontSize: 12 }}>{n.recipient}</div></td>
                  <td>{KINDS[n.kind] ?? n.kind}</td>
                  <td style={{ whiteSpace: 'normal', minWidth: 260 }}>{n.subject}</td>
                  <td><span className={`badge ${STATUS_CLASS[n.status]}`} title={n.error ?? ''}>{n.status === 'LOGGED' ? 'Logged' : n.status === 'SENT' ? 'Sent' : 'Failed'}</span></td>
                </tr>
              ))}
              {rows && !rows.length && <tr><td colSpan="5" className="empty">No notifications yet. Use the buttons above to try the alerts.</td></tr>}
              {!rows && <tr><td colSpan="5" className="empty">Loading…</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      {open && (
        <Modal title={open.subject} onClose={() => setOpen(null)}>
          <p className="muted" style={{ marginTop: 0, fontSize: 13 }}>To {open.recipientName} &lt;{open.recipient}&gt;</p>
          <pre className="mail-body">{open.body}</pre>
          {open.error && <div className="error-text">{open.error}</div>}
        </Modal>
      )}
    </>
  );
}
