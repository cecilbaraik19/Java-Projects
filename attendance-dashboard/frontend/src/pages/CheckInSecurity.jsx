import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, fmtDateTime, getPosition } from '../api.js';
import { useToast } from '../components/Toast.jsx';
import { IconLock } from '../components/Icons.jsx';

function Rule({ title, desc, checked, onChange, children }) {
  return (
    <section className="card rule">
      <label className="rule-head">
        <div>
          <h2>{title}</h2>
          <p className="muted">{desc}</p>
        </div>
        <span className="switch">
          <input type="checkbox" role="switch" checked={checked} onChange={(e) => onChange(e.target.checked)} />
          <span aria-hidden="true" />
        </span>
      </label>
      <div className="rule-body">{children}</div>
    </section>
  );
}

export default function CheckInSecurity() {
  const toast = useToast();
  const [form, setForm] = useState(null);
  const [meta, setMeta] = useState(null);
  const [myIp, setMyIp] = useState('');
  const [busy, setBusy] = useState(false);
  const [locating, setLocating] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.checkInSettings().then((s) => {
      setForm({ ...s, officeLatitude: s.officeLatitude ?? '', officeLongitude: s.officeLongitude ?? '', allowedNetworks: s.allowedNetworks ?? '' });
      setMeta(s);
    }).catch((e) => toast(e.message, 'error'));
    api.clientIp().then((r) => setMyIp(r.ip)).catch(() => {});
  }, [toast]);

  if (!form) return <div className="empty">Loading…</div>;
  const set = (k) => (v) => setForm((f) => ({ ...f, [k]: v }));
  const num = (v) => (v === '' || v === null ? null : Number(v));

  const useMyLocation = async () => {
    setLocating(true);
    try {
      const p = await getPosition();
      setForm((f) => ({ ...f, officeLatitude: p.latitude.toFixed(6), officeLongitude: p.longitude.toFixed(6) }));
      toast(`Location captured (±${Math.round(p.accuracy)} m)`);
    } catch (e) { toast(e.message, 'error'); }
    setLocating(false);
  };

  const addMyIp = () => {
    if (!myIp) return;
    const list = form.allowedNetworks.split(/[,\s]+/).filter(Boolean);
    if (!list.includes(myIp)) set('allowedNetworks')([...list, myIp].join(', '));
  };

  const save = async () => {
    setBusy(true); setError('');
    try {
      const s = await api.saveCheckInSettings({
        requireQr: form.requireQr, qrRotationSeconds: Number(form.qrRotationSeconds),
        requireLocation: form.requireLocation, officeLatitude: num(form.officeLatitude), officeLongitude: num(form.officeLongitude),
        radiusMeters: Number(form.radiusMeters), requireNetwork: form.requireNetwork, allowedNetworks: form.allowedNetworks,
      });
      setMeta(s);
      setForm((f) => ({ ...f, allowedNetworks: s.allowedNetworks ?? '' }));
      toast('Check-in rules saved');
    } catch (e) { setError(e.message); }
    setBusy(false);
  };

  const active = [form.requireQr && 'QR code', form.requireLocation && 'location', form.requireNetwork && 'office network'].filter(Boolean);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Check-in security</h1>
          <p>Rules for employees checking themselves in. Attendance marked by managers or admins is not affected.</p>
        </div>
        <div className="head-actions">
          <Link to="/kiosk" className="btn">Open kiosk screen</Link>
          <button className="btn btn-primary" onClick={save} disabled={busy}>{busy ? 'Saving…' : 'Save rules'}</button>
        </div>
      </div>

      <div className="card card-pad notice-row">
        <IconLock width="18" height="18" />
        <span>
          {active.length ? <>Self check-in currently requires <strong>{active.join(' + ')}</strong>.</> : <>No extra checks — anyone logged in can check in from anywhere.</>}
          {' '}Combining two or more rules makes it much harder to check in for a friend.
        </span>
      </div>
      {error && <div className="card card-pad error-text" style={{ marginBottom: 14 }}>{error}</div>}

      <Rule title="Rotating QR code" checked={form.requireQr} onChange={set('requireQr')}
            desc="A screen at the office shows a code that changes regularly. Employees scan it (or type it) to check in, so they must be able to see the screen.">
        <div className="form-grid">
          <div className="field">
            <label htmlFor="rot">Code changes every</label>
            <select id="rot" className="select" value={form.qrRotationSeconds} onChange={(e) => set('qrRotationSeconds')(e.target.value)}>
              {[15, 30, 60, 120, 300].map((s) => <option key={s} value={s}>{s < 60 ? `${s} seconds` : `${s / 60} minute${s > 60 ? 's' : ''}`}</option>)}
            </select>
          </div>
          <p className="muted" style={{ margin: 0, fontSize: 12.5, alignSelf: 'end' }}>
            The previous code stays valid for one more period. After 5 wrong codes an employee is blocked for 10 minutes.
          </p>
        </div>
      </Rule>

      <Rule title="Office location (geofence)" checked={form.requireLocation} onChange={set('requireLocation')}
            desc="The phone's GPS position must be within the radius of the office. Works best combined with the QR code or network rule, since GPS can be faked on rooted phones.">
        <div className="form-grid three">
          <div className="field"><label htmlFor="lat">Latitude</label>
            <input id="lat" className="input" inputMode="decimal" value={form.officeLatitude} onChange={(e) => set('officeLatitude')(e.target.value)} placeholder="23.344100" /></div>
          <div className="field"><label htmlFor="lng">Longitude</label>
            <input id="lng" className="input" inputMode="decimal" value={form.officeLongitude} onChange={(e) => set('officeLongitude')(e.target.value)} placeholder="85.309600" /></div>
          <div className="field"><label htmlFor="rad">Radius (metres)</label>
            <input id="rad" type="number" min="20" max="5000" className="input" value={form.radiusMeters} onChange={(e) => set('radiusMeters')(e.target.value)} /></div>
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginTop: 12, flexWrap: 'wrap' }}>
          <button className="btn btn-sm" onClick={useMyLocation} disabled={locating}>{locating ? 'Locating…' : 'Use my current location'}</button>
          {form.officeLatitude && form.officeLongitude && (
            <a className="btn btn-sm" target="_blank" rel="noreferrer noopener"
               href={`https://www.openstreetmap.org/?mlat=${form.officeLatitude}&mlon=${form.officeLongitude}#map=17/${form.officeLatitude}/${form.officeLongitude}`}>
              View on map
            </a>
          )}
          <span className="muted" style={{ fontSize: 12.5 }}>Stand inside the office when capturing the location.</span>
        </div>
      </Rule>

      <Rule title="Office network" checked={form.requireNetwork} onChange={set('requireNetwork')}
            desc="Only allow check-in from the office Wi-Fi / internet connection. Enter public IPs or ranges (CIDR), separated by commas.">
        <div className="field">
          <label htmlFor="nets">Allowed IP addresses / ranges</label>
          <textarea id="nets" className="input" rows="2" value={form.allowedNetworks} onChange={(e) => set('allowedNetworks')(e.target.value)}
                    placeholder="e.g. 203.0.113.10, 192.168.1.0/24" style={{ resize: 'vertical', fontFamily: 'ui-monospace, Consolas, monospace' }} />
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', marginTop: 10, flexWrap: 'wrap' }}>
          <span className="muted" style={{ fontSize: 12.5 }}>The server sees this computer as <code>{myIp || '…'}</code></span>
          {myIp && <button className="btn btn-sm" onClick={addMyIp}>Add this IP</button>}
        </div>
      </Rule>

      {meta?.updatedAt && <p className="muted" style={{ fontSize: 12.5 }}>Last changed {fmtDateTime(meta.updatedAt)} by {meta.updatedBy}</p>}
    </>
  );
}
