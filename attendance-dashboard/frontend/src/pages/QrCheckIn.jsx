import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, buildProof, fmtTime, todayIso } from '../api.js';
import { useAuth } from '../auth.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import { IconCheck, IconLogIn, IconLogOut } from '../components/Icons.jsx';

/** Opened by scanning the kiosk QR code: /checkin?code=123456 */
export default function QrCheckIn() {
  const [params] = useSearchParams();
  const code = (params.get('code') || '').replace(/\D/g, '').slice(0, 6);
  const { user } = useAuth();
  const [policy, setPolicy] = useState(null);
  const [today, setToday] = useState(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [done, setDone] = useState(null);

  useEffect(() => {
    Promise.all([api.checkInPolicy(), api.myAttendance(todayIso(), todayIso())])
      .then(([p, rows]) => { setPolicy(p); setToday(rows[0] ?? null); })
      .catch((e) => { setError(e.message); setToday(null); });
  }, []);

  const action = !today?.checkIn ? 'in' : !today?.checkOut ? 'out' : null;

  const go = async () => {
    setBusy(true); setError('');
    try {
      const proof = await buildProof(policy, code);
      const r = action === 'in' ? await api.myCheckIn(proof) : await api.myCheckOut(proof);
      setDone(r);
    } catch (e) { setError(e.message); }
    setBusy(false);
  };

  return (
    <div className="login-page">
      <div className="card login-card" style={{ textAlign: 'center' }}>
        <h1>{done ? (action === 'in' ? 'Checked in' : 'Checked out') : 'QR check-in'}</h1>
        <p className="muted" style={{ margin: 0 }}>{user.employeeName}</p>

        {done ? (
          <>
            <div className="done-mark"><IconCheck /></div>
            <div style={{ fontSize: 28, fontWeight: 650 }} className="tabular">
              {fmtTime(action === 'in' ? done.checkIn : done.checkOut)}
            </div>
            <div><StatusBadge status={done.status} /></div>
            <p className="muted" style={{ fontSize: 12.5, margin: 0 }}>{action === 'in' ? done.checkInVerification : done.checkOutVerification}</p>
          </>
        ) : today === undefined ? (
          <p className="muted">Loading…</p>
        ) : !action ? (
          <p>You have already checked in ({fmtTime(today.checkIn)}) and out ({fmtTime(today.checkOut)}) today.</p>
        ) : today?.status === 'ON_LEAVE' ? (
          <p>You are on leave today.</p>
        ) : (
          <>
            {!code && <div className="notice">No code found in the link. Scan the QR code on the office screen again.</div>}
            {policy?.requireLocation && <p className="muted" style={{ margin: 0, fontSize: 13 }}>Your location will be checked against the office.</p>}
            <button className="btn btn-primary btn-lg" style={{ justifyContent: 'center' }} disabled={busy || (!code && policy?.requireQr)} onClick={go}>
              {action === 'in' ? <><IconLogIn />{busy ? 'Checking in…' : 'Check in now'}</> : <><IconLogOut />{busy ? 'Checking out…' : 'Check out now'}</>}
            </button>
          </>
        )}

        {error && <div className="error-text" role="alert" style={{ marginTop: 0 }}>{error}</div>}
        <Link to="/my" className="muted" style={{ fontSize: 13 }}>Go to My Attendance</Link>
      </div>
    </div>
  );
}
