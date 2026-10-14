// Small helper for talking to the Spring Boot backend.

async function request(path, options = {}) {
  const res = await fetch(`/api${path}`, options)
  if (res.status === 204) return null
  let body = null
  try {
    body = await res.json()
  } catch {
    /* not JSON */
  }
  if (!res.ok) {
    throw new Error(body?.error || `Request failed (${res.status})`)
  }
  return body
}

export const api = {
  status: () => request('/status'),
  listDocuments: () => request('/documents'),
  getDocument: (id) => request(`/documents/${id}`),
  deleteDocument: (id) => request(`/documents/${id}`, { method: 'DELETE' }),
  uploadDocument: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request('/documents', { method: 'POST', body: form })
  },
  ask: (question, documentIds) =>
    request('/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, documentIds }),
    }),
  history: () => request('/history'),
  clearHistory: () => request('/history', { method: 'DELETE' }),
}
