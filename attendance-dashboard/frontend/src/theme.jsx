import { createContext, useContext, useEffect, useState } from 'react';

const ThemeContext = createContext(null);

function initialTheme() {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

export function ThemeProvider({ children }) {
  const [theme, setTheme] = useState(initialTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('theme', theme); } catch { /* storage blocked */ }
  }, [theme]);

  const toggle = () => setTheme((t) => (t === 'dark' ? 'light' : 'dark'));
  return <ThemeContext.Provider value={{ theme, setTheme, toggle }}>{children}</ThemeContext.Provider>;
}

export const useTheme = () => useContext(ThemeContext);

/**
 * Chart colors per theme. SVG attributes can't read CSS variables, so charts take hex.
 * Status hues are fixed across themes; ink/grid colors are stepped for each surface.
 */
const CHART = {
  light: {
    PRESENT: '#0ca30c', LATE: '#fab219', HALF_DAY: '#ec835a', ABSENT: '#d03b3b', ON_LEAVE: '#2a78d6',
    accent: '#2a78d6', grid: '#e1e0d9', axis: '#898781', surface: '#ffffff', cursor: 'rgba(11,11,11,0.05)',
  },
  dark: {
    PRESENT: '#0ca30c', LATE: '#fab219', HALF_DAY: '#ec835a', ABSENT: '#d03b3b', ON_LEAVE: '#3987e5',
    accent: '#3987e5', grid: '#2c2c2a', axis: '#898781', surface: '#1a1a19', cursor: 'rgba(255,255,255,0.06)',
  },
};

export function useChartColors() {
  const { theme } = useTheme();
  return CHART[theme];
}
