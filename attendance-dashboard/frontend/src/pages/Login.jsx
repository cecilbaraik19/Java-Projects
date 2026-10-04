import { useState } from 'react';
import { useAuth } from '../auth.jsx';
import { useTheme } from '../theme.jsx';
import { IconClock, IconEye, IconEyeOff, IconMoon, IconSun } from '../components/Icons.jsx';

export default function Login() {
  const { login, expired } = useAuth();
  const { theme, toggle } = useTheme();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError('');
    try {
      await login(username, password);
    } catch (err) {
      setError(!err.status || err.status >= 500
        ? 'Cannot reach the server. Is the backend running on port 8080?' : err.message);
      setPassword('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <button className="icon-btn login-theme" onClick={toggle} title="Toggle theme" aria-label="Toggle theme">
        {theme === 'dark' ? <IconSun /> : <IconMoon />}
      </button>

      <form className="card login-card" onSubmit={submit}>
        <div className="brand" style={{ padding: '0 0 6px' }}>
          <div className="brand-mark"><IconClock width="17" height="17" /></div>
          <span>AttendTrack</span>
        </div>
        <div>
          <h1>Sign in</h1>
          <p className="muted" style={{ margin: '4px 0 0' }}>Employee Attendance Dashboard</p>
        </div>

        {expired && !error && <div className="notice">Your session has ended. Please sign in again.</div>}

        <div className="field">
          <label htmlFor="u">Username</label>
          <input id="u" className="input" autoComplete="username" autoFocus required maxLength={40}
                 value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="p">Password</label>
          <div className="input-wrap">
            <input id="p" className="input" type={show ? 'text' : 'password'} autoComplete="current-password"
                   required maxLength={128} value={password} onChange={(e) => setPassword(e.target.value)} />
            <button type="button" className="icon-btn" onClick={() => setShow((s) => !s)}
                    aria-label={show ? 'Hide password' : 'Show password'}>
              {show ? <IconEyeOff /> : <IconEye />}
            </button>
          </div>
        </div>

        {error && <div className="error-text" role="alert" style={{ marginTop: 0 }}>{error}</div>}

        <button className="btn btn-primary" type="submit" disabled={busy}
                style={{ justifyContent: 'center', padding: '10px 14px' }}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>

        <details className="demo-accounts">
          <summary>Demo accounts</summary>
          <table>
            <tbody>
              <tr><td>admin</td><td>Admin@123</td><td className="muted">everything</td></tr>
              <tr><td>manager</td><td>Manager@123</td><td className="muted">Engineering team</td></tr>
              <tr><td>employee</td><td>Employee@123</td><td className="muted">own attendance</td></tr>
            </tbody>
          </table>
        </details>
      </form>
    </div>
  );
}
