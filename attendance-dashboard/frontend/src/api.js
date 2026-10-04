// Thin wrapper around fetch for the Spring Boot REST API.
async function request(path, options = {}) {
  const res = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const err = await res.json();
      if (err.message) message = err.message;
    } catch { /* not JSON */ }
    throw new Error(message);
  }
  return res.status === 204 ? null : res.json();
}

export const api = {
  // employees
  employees: (activeOnly = false) => request(`/employees?activeOnly=${activeOnly}`),
  departments: () => request('/employees/departments'),
  createEmployee: (data) => request('/employees', { method: 'POST', body: data }),
  updateEmployee: (id, data) => request(`/employees/${id}`, { method: 'PUT', body: data }),
  deleteEmployee: (id) => request(`/employees/${id}`, { method: 'DELETE' }),
  employeeHistory: (id, from, to) => request(`/employees/${id}/attendance?from=${from}&to=${to}`),

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
  summary: (from, to) => request(`/reports/summary?from=${from}&to=${to}`),
  summaryCsvUrl: (from, to) => `/api/reports/summary.csv?from=${from}&to=${to}`,
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

export const STATUS_LABEL = {
  PRESENT: 'Present', LATE: 'Late', HALF_DAY: 'Half day', ABSENT: 'Absent',
  ON_LEAVE: 'On leave', NOT_MARKED: 'Not marked',
};
