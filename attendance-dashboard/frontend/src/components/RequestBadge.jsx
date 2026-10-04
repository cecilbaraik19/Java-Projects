import { REQUEST_LABEL } from '../api.js';
import { IconCheck, IconClock, IconMinus, IconX } from './Icons.jsx';

// Reuses the attendance status colours; icon + label so meaning never relies on colour alone.
const STYLE = {
  PENDING: ['LATE', IconClock],
  APPROVED: ['PRESENT', IconCheck],
  REJECTED: ['ABSENT', IconX],
  CANCELLED: ['NOT_MARKED', IconMinus],
};

export default function RequestBadge({ status }) {
  const [cls, Icon] = STYLE[status] ?? STYLE.CANCELLED;
  return <span className={`badge ${cls}`}><Icon />{REQUEST_LABEL[status] ?? status}</span>;
}
