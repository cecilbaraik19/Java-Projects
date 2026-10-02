import { Box, Container, Database, HardDrive, Monitor, Server, Zap } from 'lucide-react'

const ICONS = { VM: Server, DATABASE: Database, CONTAINER: Container, STORAGE: HardDrive, FUNCTION: Zap, LOCAL_HOST: Monitor }

export default function ResourceIcon({ type, size = 18 }) {
  const Icon = ICONS[type] || Box
  return (
    <span className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-surface-2 text-accent">
      <Icon size={size} />
    </span>
  )
}

const PROVIDER_STYLE = {
  AWS: 'AWS', AZURE: 'Azure', GCP: 'GCP', LOCAL: 'Local',
}

export function ProviderTag({ provider }) {
  return (
    <span className="rounded-md border border-line px-1.5 py-0.5 text-[11px] font-semibold tracking-wide text-ink-2">
      {PROVIDER_STYLE[provider] || provider}
    </span>
  )
}
