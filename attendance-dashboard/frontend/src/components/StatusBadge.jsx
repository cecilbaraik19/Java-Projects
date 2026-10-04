import { STATUS_LABEL } from '../api.js';
import { IconAlert, IconCalendar, IconCheck, IconClock, IconMinus, IconX } from './Icons.jsx';

// Icon + label so status is never conveyed by color alone.
const ICON = {
  PRESENT: IconCheck, LATE: IconClock, HALF_DAY: IconAlert,
  ABSENT: IconX, ON_LEAVE: IconCalendar, NOT_MARKED: IconMinus,
};

export default function StatusBadge({ status }) {
  const Icon = ICON[status] ?? IconMinus;
  return (
    <span className={`badge ${status}`}>
      <Icon />
      {STATUS_LABEL[status] ?? status}
    </span>
  );
}
