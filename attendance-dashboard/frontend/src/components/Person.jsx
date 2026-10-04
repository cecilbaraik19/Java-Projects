const initials = (name = '') => name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase();

export default function Person({ name, sub }) {
  return (
    <div className="person">
      <div className="avatar">{initials(name)}</div>
      <div>
        {name}
        {sub && <small>{sub}</small>}
      </div>
    </div>
  );
}
