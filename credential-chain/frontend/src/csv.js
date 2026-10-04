// Small CSV helpers for bulk issuing (no library needed).

/** Parses CSV text into an array of rows (arrays of strings). Handles quotes, commas and newlines in quotes. */
export function parseCsv(text) {
  const rows = []
  let row = []
  let field = ''
  let inQuotes = false
  const s = text.replace(/^﻿/, '') // strip Excel's BOM
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (inQuotes) {
      if (c === '"' && s[i + 1] === '"') { field += '"'; i++ }
      else if (c === '"') inQuotes = false
      else field += c
    } else if (c === '"') inQuotes = true
    else if (c === ',' || c === ';' || c === '\t') { row.push(field); field = '' }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && s[i + 1] === '\n') i++
      row.push(field); field = ''
      if (row.some((v) => v.trim() !== '')) rows.push(row)
      row = []
    } else field += c
  }
  row.push(field)
  if (row.some((v) => v.trim() !== '')) rows.push(row)
  return rows
}

export function toCsv(rows) {
  return rows.map((r) => r.map((v) => {
    const t = v == null ? '' : String(v)
    return /[",\n]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t
  }).join(',')).join('\r\n')
}

export function downloadText(text, filename, type = 'text/csv') {
  const blob = new Blob([text], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

// Accepted column names (case/space insensitive) -> field
const ALIASES = {
  credentialtype: 'credentialType', type: 'credentialType', certificatetype: 'credentialType', degree: 'credentialType',
  studentname: 'studentName', name: 'studentName', student: 'studentName',
  studentid: 'studentId', rollid: 'studentId', rollno: 'studentId', rollnumber: 'studentId', roll: 'studentId', id: 'studentId', enrollmentno: 'studentId',
  program: 'program', programme: 'program', course: 'program',
  major: 'major', specialisation: 'major', specialization: 'major',
  grade: 'grade', cgpa: 'grade', result: 'grade',
  issuedate: 'issueDate', date: 'issueDate', dateofissue: 'issueDate',
}

export const TEMPLATE = [
  ['credentialType', 'studentName', 'studentId', 'program', 'major', 'grade', 'issueDate'],
  ["Bachelor's Degree", 'Ananya Singh', '2022-BSCIT/031', 'B.Sc. Information Technology', 'Cyber Security', '8.4 CGPA', '2025-06-30'],
  ["Bachelor's Degree", 'Rahul Kumar', '2022-BSCIT/032', 'B.Sc. Information Technology', 'Cloud Computing', '7.9 CGPA', '2025-06-30'],
  ['Certificate', 'Sneha Oraon', '2024-BCA/015', 'Ethical Hacking Workshop', '', 'A', '2026-02-15'],
]

/** Accepts yyyy-mm-dd, dd-mm-yyyy or dd/mm/yyyy (Indian format). Returns yyyy-mm-dd or null. */
function normaliseDate(v) {
  const t = v.trim()
  if (!t) return ''
  let m = t.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/)
  let y, mo, d
  if (m) [, y, mo, d] = m
  else if ((m = t.match(/^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})$/))) [, d, mo, y] = m
  else return null
  const iso = `${y}-${mo.padStart(2, '0')}-${d.padStart(2, '0')}`
  const date = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(date.getTime()) || date.getDate() !== Number(d)) return null
  return iso
}

/** Turns parsed CSV rows into credential requests + per-row errors. */
export function toRequests(rows) {
  if (rows.length < 2) return { error: 'The file needs a header row and at least one student row.' }
  const header = rows[0].map((h) => ALIASES[h.toLowerCase().replace(/[^a-z]/g, '')])
  const required = ['credentialType', 'studentName', 'studentId', 'program', 'grade']
  const missing = required.filter((f) => !header.includes(f))
  if (missing.length) return { error: `Missing column(s): ${missing.join(', ')}. Download the template to see the format.` }
  if (rows.length - 1 > 500) return { error: 'Please upload at most 500 students at a time.' }

  const today = new Date().toLocaleDateString('en-CA')
  const items = rows.slice(1).map((r, i) => {
    const req = { credentialType: '', studentName: '', studentId: '', program: '', major: '', grade: '', issueDate: '' }
    header.forEach((f, c) => { if (f) req[f] = (r[c] || '').trim() })
    const errors = required.filter((f) => !req[f]).map((f) => `${f} is empty`)
    const date = normaliseDate(req.issueDate)
    if (date === null) errors.push('issueDate must be yyyy-mm-dd or dd-mm-yyyy')
    else {
      req.issueDate = date
      if (date && date > today) errors.push('issueDate is in the future')
    }
    return { row: i + 2, req, errors }
  })
  return { items }
}
