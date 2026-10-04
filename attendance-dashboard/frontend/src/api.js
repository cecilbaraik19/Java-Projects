// Thin wrapper around fetch for the Spring Boot REST API, with JWT auth.

const TOKEN_KEY = 'auth_token';

export function getToken() {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}
export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* storage blocked */ }
}

function authHeaders() {
  const t = getToken();
  return t ? { Authorization: `Bearer ${t}` } : {};
}

async function request(path, options = {}) {
  const res = await fetch(`/api${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...authHeaders(), ...(options.headers || {}) },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const err = await res.json();
      if (err.message) message = err.message;
    } catch { /* not JSON */ }
    // Session expired / revoked: tell the app to show the login screen
    if (res.status === 401 && !path.startsWith('/auth/login')) {
      window.dispatchEvent(new CustomEvent('auth:expired'));
    }
    const error = new Error(message);
    error.status = res.status;
    throw error;
  }
  return res.status === 204 ? null : res.json();
}

/** Downloads a file from an authenticated endpoint (plain <a href> can't send the token). */
async function download(path, filename) {
  const res = await fetch(`/api${path}`, { headers: authHeaders() });
  if (!res.ok) throw new Error(`Download failed (${res.status})`);
  const url = URL.createObjectURL(await res.blob());
  const a = Object.assign(document.createElement('a'), { href: url, download: filename });
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

const qs = (params) => new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')).toString();

export const api = {
  // auth
  login: (username, password) => request('/auth/login', { method: 'POST', body: { username, password } }),
  me: () => request('/auth/me'),
  changePassword: (currentPassword, newPassword) =>
    request('/auth/change-password', { method: 'POST', body: { currentPassword, newPassword } }),
  myAttendance: (from, to) => request(`/me/attendance?${qs({ from, to })}`),
  myCheckIn: (proof = {}) => request('/me/check-in', { method: 'POST', body: proof }),
  myCheckOut: (proof = {}) => request('/me/check-out', { method: 'POST', body: proof }),
  checkInPolicy: () => request('/me/checkin-policy'),

  // check-in security (admin) + kiosk
  checkInSettings: () => request('/settings/checkin'),
  saveCheckInSettings: (data) => request('/settings/checkin', { method: 'PUT', body: data }),
  clientIp: () => request('/settings/client-ip'),
  kioskCode: () => request('/kiosk/code'),
  myLeaveBalance: (year) => request(`/me/leave-balance?${qs({ year })}`),
  myLeave: () => request('/me/leave-requests'),
  applyLeave: (data) => request('/me/leave-requests', { method: 'POST', body: data }),
  cancelLeave: (id) => request(`/me/leave-requests/${id}/cancel`, { method: 'POST' }),
  myCorrections: () => request('/me/corrections'),
  requestCorrection: (data) => request('/me/corrections', { method: 'POST', body: data }),
  cancelCorrection: (id) => request(`/me/corrections/${id}/cancel`, { method: 'POST' }),

  // approvals (admin / manager)
  approvalCounts: () => request('/approvals/count'),
  leaveQueue: (status = 'PENDING') => request(`/approvals/leave?status=${status}`),
  reviewLeave: (id, approve, comment) => request(`/approvals/leave/${id}`, { method: 'POST', body: { approve, comment } }),
  correctionQueue: (status = 'PENDING') => request(`/approvals/corrections?status=${status}`),
  reviewCorrection: (id, approve, comment) => request(`/approvals/corrections/${id}`, { method: 'POST', body: { approve, comment } }),

  // users (admin)
  users: () => request('/users'),
  createUser: (data) => request('/users', { method: 'POST', body: data }),
  updateUser: (id, data) => request(`/users/${id}`, { method: 'PUT', body: data }),
  resetPassword: (id, newPassword) => request(`/users/${id}/reset-password`, { method: 'POST', body: { newPassword } }),
  deleteUser: (id) => request(`/users/${id}`, { method: 'DELETE' }),

  // audit log (admin)
  audit: (params) => request(`/audit?${qs(params)}`),

  // employees
  employees: (activeOnly = false) => request(`/employees?activeOnly=${activeOnly}`),
  departments: () => request('/employees/departments'),
  createEmployee: (data) => request('/employees', { method: 'POST', body: data }),
  updateEmployee: (id, data) => request(`/employees/${id}`, { method: 'PUT', body: data }),
  deleteEmployee: (id) => request(`/employees/${id}`, { method: 'DELETE' }),
  employeeHistory: (id, from, to) => request(`/employees/${id}/attendance?${qs({ from, to })}`),

  // attendance
  daily: (date) => request(`/attendance${date ? `?date=${date}` : ''}`),
  checkIn: (employeeId) => request('/attendance/check-in', { method: 'POST', body: { employeeId } }),
  checkOut: (employeeId) => request('/attendance/check-out', { method: 'POST', body: { employeeId } }),
  saveRecord: (data) => request('/attendance', { method: 'PUT', body: data }),
  deleteRecord: (id) => request(`/attendance/${id}`, { method: 'DELETE' }),

  // reports
  stats: (date) => request(`/reports/stats${date ? `?date=${date}` : ''}`),
  trend: (days = 14) => request(`/reports/trend?days=${days}`),
  deptStats: (date) => request(`/reports/departments${date ? `?date=${date}` : ''}`),
  summary: (from, to) => request(`/reports/summary?${qs({ from, to })}`),
  downloadSummaryCsv: (from, to) => download(`/reports/summary.csv?${qs({ from, to })}`, `attendance_${from}_to_${to}.csv`),
};

// ---------- small shared helpers ----------
export const todayIso = () => toIso(new Date());
export function toIso(d) {
  const z = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  return z.toISOString().slice(0, 10);
}
export const fmtTime = (t) => (t ? t.slice(0, 5) : '—');
export const fmtDate = (iso, opts = { day: 'numeric', month: 'short' }) =>
  new Date(iso + 'T00:00:00').toLocaleDateString('en-IN', opts);
export const fmtDateTime = (iso) =>
  iso ? new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—';

export const STATUS_LABEL = {
  PRESENT: 'Present', LATE: 'Late', HALF_DAY: 'Half day', ABSENT: 'Absent',
  ON_LEAVE: 'On leave', NOT_MARKED: 'Not marked',
};

export const ROLE_LABEL = { ADMIN: 'Admin', MANAGER: 'Manager', EMPLOYEE: 'Employee' };

export const LEAVE_LABEL = { CASUAL: 'Casual', SICK: 'Sick', EARNED: 'Earned', UNPAID: 'Unpaid' };
export const REQUEST_LABEL = { PENDING: 'Pending', APPROVED: 'Approved', REJECTED: 'Rejected', CANCELLED: 'Cancelled' };

/** Mon–Fri days between two ISO dates (inclusive) – a preview; the server is the source of truth. */
export function countWeekdays(fromIso, toIso) {
  if (!fromIso || !toIso || toIso < fromIso) return 0;
  let n = 0;
  for (let d = new Date(fromIso + 'T00:00:00'); toIso >= toIsoDate(d); d.setDate(d.getDate() + 1)) {
    const wd = d.getDay();
    if (wd !== 0 && wd !== 6) n++;
  }
  return n;
}
function toIsoDate(d) { return toIso(d); }

/** Current GPS position as { latitude, longitude, accuracy } (asks the browser for permission). */
export function getPosition() {
  return new Promise((resolve, reject) => {
    if (!('geolocation' in navigator)) { reject(new Error('This browser cannot share your location')); return; }
    if (!window.isSecureContext) {
      reject(new Error('Location only works on https:// or localhost. Use "npm run dev:phone" for phones.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude, accuracy: p.coords.accuracy }),
      (err) => reject(new Error(err.code === 1
        ? 'Location permission was denied. Allow location for this site and try again.'
        : 'Could not get your location. Turn on GPS / location services and try again.')),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 },
    );
  });
}

/**
 * Builds the proof for a self check-in/out from the active policy:
 * asks for GPS when required and attaches the QR code if given.
 */
export async function buildProof(policy, qrCode) {
  const proof = {};
  if (policy?.requireLocation) Object.assign(proof, await getPosition());
  if (qrCode) proof.qrCode = qrCode;
  return proof;
}

/** "09:15–18:10", or "09:15–?" when there is no check-out. */
export const fmtSpan = (a, b) => `${fmtTime(a)}–${b ? fmtTime(b) : '?'}`;

/** Today if it is a weekday, otherwise the next Monday. */
export function nextWorkdayIso() {
  const d = new Date();
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1);
  return toIso(d);
}
