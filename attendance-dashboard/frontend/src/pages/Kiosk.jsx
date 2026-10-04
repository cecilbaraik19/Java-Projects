import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import QRCode from 'qrcode';
import { api } from '../api.js';
import { useTheme } from '../theme.jsx';
import { IconClock, IconX } from '../components/Icons.jsx';

/**
 * Full-screen display for a tablet/monitor at the office entrance.
 * Shows a QR code that changes every few seconds; employees scan it to check in.
 */
export default function Kiosk() {
  const { theme } = useTheme();
  const [code, setCode] = useState(null);
  const [left, setLeft] = useState(0);
  const [error, setError] = useState('');
  const [now, setNow] = useState(new Date());
  const canvas = useRef(null);
  const expiresAt = useRef(0);

  // Fetch a fresh code whenever the current one expires
  useEffect(() => {
    let timer;
    const load = async () => {
      try {
        const c = await api.kioskCode();
        setCode(c);
        setError('');
        expiresAt.current = Date.now() + c.secondsLeft * 1000;
        timer = setTimeout(load, c.secondsLeft * 1000 + 300);
      } catch (e) {
        setError(e.message);
        timer = setTimeout(load, 5000);
      }
    };
    load();
    return () => clearTimeout(timer);
  }, []);

  // Countdown + clock
  useEffect(() => {
    const id = setInterval(() => {
      setNow(new Date());
      setLeft(Math.max(0, Math.ceil((expiresAt.current - Date.now()) / 1000)));
    }, 250);
    return () => clearInterval(id);
  }, []);

  // Draw the QR (link opens the check-in page with the code filled in)
  useEffect(() => {
    if (!code || !canvas.current) return;
    const url = `${window.location.origin}/checkin?code=${code.code}`;
    QRCode.toCanvas(canvas.current, url, {
      width: 340, margin: 2, errorCorrectionLevel: 'M',
      color: { dark: '#0b0b0b', light: '#ffffff' }, // always dark-on-white so every phone camera can read it
    }).catch((e) => setError(e.message));
  }, [code, theme]);

  const pct = code ? (left / code.rotationSeconds) * 100 : 0;

  return (
    <div className="kiosk">
      <Link to="/" className="icon-btn kiosk-exit" title="Exit kiosk" aria-label="Exit kiosk"><IconX /></Link>
      <div className="kiosk-brand"><div className="brand-mark"><IconClock width="17" height="17" /></div>AttendTrack</div>
      <div className="kiosk-time tabular">{now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })}</div>
      <div className="kiosk-date">{now.toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })}</div>

      <div className="kiosk-qr card">
        <canvas ref={canvas} aria-label="Check-in QR code" />
        <div className="kiosk-bar" aria-hidden="true"><span style={{ width: `${pct}%` }} /></div>
        <div className="kiosk-code tabular" aria-live="polite">
          {code ? `${code.code.slice(0, 3)} ${code.code.slice(3)}` : '··· ···'}
        </div>
        <div className="muted">New code in {left}s</div>
      </div>

      <p className="kiosk-help">Scan with your phone camera to check in or out,<br />or type the code on your <strong>My Attendance</strong> page.</p>
      {error && <div className="error-text">{error}</div>}
    </div>
  );
}
