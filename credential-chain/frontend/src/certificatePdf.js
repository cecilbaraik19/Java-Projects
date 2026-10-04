import { jsPDF } from 'jspdf'
import QRCode from 'qrcode'

/**
 * Builds a printable A4 (landscape) certificate PDF with a QR code that
 * opens the public Verify page for this credential.
 *
 * @param view  the CredentialView returned by GET /api/credentials/{id}
 */
export async function downloadCertificatePdf(view) {
  const c = view.credential
  const verifyUrl = `${window.location.origin}/verify/${c.credentialId}`
  const qr = await QRCode.toDataURL(verifyUrl, { margin: 1, width: 400, color: { dark: '#1e1b4b', light: '#ffffff' } })

  const doc = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' })
  const W = 297
  const H = 210
  const indigo = [79, 70, 229]
  const ink = [30, 27, 75]
  const grey = [100, 106, 130]

  // ---- background & double border ----
  doc.setFillColor(250, 250, 255)
  doc.rect(0, 0, W, H, 'F')
  doc.setDrawColor(...indigo)
  doc.setLineWidth(1.6)
  doc.rect(8, 8, W - 16, H - 16)
  doc.setLineWidth(0.4)
  doc.rect(12, 12, W - 24, H - 24)

  // ---- header ----
  doc.setTextColor(...indigo)
  doc.setFont('helvetica', 'bold')
  doc.setFontSize(22)
  doc.text(c.issuerName, W / 2, 34, { align: 'center' })

  doc.setTextColor(...grey)
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(11)
  doc.text('BLOCKCHAIN-VERIFIED ACADEMIC CREDENTIAL', W / 2, 42, { align: 'center', charSpace: 0.6 })
  doc.setCharSpace(0)

  doc.setDrawColor(...indigo)
  doc.setLineWidth(0.5)
  doc.line(W / 2 - 40, 47, W / 2 + 40, 47)

  // ---- title ----
  doc.setTextColor(...ink)
  doc.setFont('times', 'bold')
  doc.setFontSize(30)
  doc.text(c.credentialType, W / 2, 63, { align: 'center' })

  doc.setFont('helvetica', 'normal')
  doc.setFontSize(13)
  doc.setTextColor(...grey)
  doc.text('This is to certify that', W / 2, 77, { align: 'center' })

  // ---- student name ----
  doc.setFont('times', 'bolditalic')
  doc.setFontSize(34)
  doc.setTextColor(...ink)
  doc.text(c.studentName, W / 2, 93, { align: 'center' })

  doc.setFont('helvetica', 'normal')
  doc.setFontSize(12)
  doc.setTextColor(...grey)
  doc.text(`Student ID: ${c.studentId}`, W / 2, 101, { align: 'center' })

  // ---- body ----
  doc.setFontSize(13)
  doc.setTextColor(...ink)
  const body = `has successfully completed ${c.program}` + (c.major ? ` with specialisation in ${c.major}` : '') + '.'
  doc.text(doc.splitTextToSize(body, 200), W / 2, 112, { align: 'center' })

  // ---- detail boxes ----
  const boxes = [
    ['GRADE', c.grade],
    ['ISSUED ON', c.issueDate],
    ['BLOCK', view.blockIndex != null ? `#${view.blockIndex}` : 'Pending'],
  ]
  const boxW = 52
  const startX = 30
  boxes.forEach(([label, value], i) => {
    const x = startX + i * (boxW + 6)
    doc.setFillColor(238, 240, 255)
    doc.roundedRect(x, 126, boxW, 20, 2, 2, 'F')
    doc.setFont('helvetica', 'normal')
    doc.setFontSize(8)
    doc.setTextColor(...grey)
    doc.text(label, x + boxW / 2, 132, { align: 'center' })
    doc.setFont('helvetica', 'bold')
    doc.setFontSize(12)
    doc.setTextColor(...ink)
    doc.text(String(value), x + boxW / 2, 140, { align: 'center' })
  })

  // ---- credential id + hash ----
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(8)
  doc.setTextColor(...grey)
  doc.text('CREDENTIAL ID', 30, 156)
  doc.text('SHA-256 FINGERPRINT (stored on the blockchain)', 30, 168)
  doc.setFont('courier', 'bold')
  doc.setFontSize(11)
  doc.setTextColor(...ink)
  doc.text(c.credentialId, 30, 162)
  doc.setFontSize(8)
  doc.text(view.credentialHash || '', 30, 174)

  doc.setFont('helvetica', 'italic')
  doc.setFontSize(8)
  doc.setTextColor(...grey)
  doc.text(`Digitally signed by ${c.issuerName} (${c.issuerId}) with ECDSA P-256.`, 30, 184)
  doc.text('Scan the QR code or visit the link to verify this certificate on the blockchain.', 30, 189)

  // ---- QR code ----
  const qrSize = 48
  const qrX = W - 30 - qrSize
  const qrY = 124
  doc.setDrawColor(...indigo)
  doc.setLineWidth(0.4)
  doc.roundedRect(qrX - 3, qrY - 3, qrSize + 6, qrSize + 6, 2, 2)
  doc.addImage(qr, 'PNG', qrX, qrY, qrSize, qrSize)
  doc.setFont('helvetica', 'bold')
  doc.setFontSize(9)
  doc.setTextColor(...indigo)
  doc.text('SCAN TO VERIFY', qrX + qrSize / 2, qrY + qrSize + 9, { align: 'center' })
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(6)
  doc.setTextColor(...grey)
  doc.text(verifyUrl, qrX + qrSize / 2, qrY + qrSize + 14, { align: 'center' })

  // ---- REVOKED stamp ----
  if (view.status === 'REVOKED') {
    doc.setTextColor(220, 38, 38)
    doc.setDrawColor(220, 38, 38)
    doc.setFont('helvetica', 'bold')
    doc.setFontSize(64)
    doc.setGState(new doc.GState({ opacity: 0.35 }))
    doc.text('REVOKED', W / 2, 120, { align: 'center', angle: 18 })
    doc.setGState(new doc.GState({ opacity: 1 }))
    if (view.revocationReason) {
      doc.setFontSize(10)
      doc.text(`Revoked: ${view.revocationReason}`, W / 2, 196, { align: 'center' })
    }
  }

  doc.save(`${c.credentialId}.pdf`)
}
