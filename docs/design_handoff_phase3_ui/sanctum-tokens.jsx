// Sanctum Machina — design tokens.
// Шрифты и цвета для «оккультного техно-минимализма».

const SANCTUM_LIGHT = {
  name: 'light',
  // Пергаментная база
  bg:         '#f4efe6',        // основной off-white, тёплый, низкой насыщенности
  bgSunk:     '#ebe4d6',        // для панелей/drawer
  bgRaised:   '#fbf7ee',        // поднятые поверхности / input
  ink:        '#1a1714',        // основной текст
  inkMuted:   '#5a504a',        // подписи, второстепенное
  inkDim:     '#8a7f75',        // метаданные
  hair:       'rgba(26,23,20,0.08)',  // hairline-разделители
  hairStrong: 'rgba(26,23,20,0.16)',
  userBubble: '#e5ddcd',        // серый bubble USER — чуть теплее фона
  accent:     'oklch(0.58 0.14 40)', // киноварь/латунь
  accentInk:  '#fbf7ee',
  danger:     'oklch(0.56 0.16 28)',
  incognitoBg:'#1b1a18',        // тёмная плашка поверх светлой темы
  incognitoInk:'#e8dfcb',
  incognitoEdge:'oklch(0.62 0.16 42)',
};

const SANCTUM_DARK = {
  name: 'dark',
  bg:         '#15130f',
  bgSunk:     '#0d0b09',
  bgRaised:   '#1e1b16',
  ink:        '#e8dfcb',
  inkMuted:   '#a59a88',
  inkDim:     '#6e6458',
  hair:       'rgba(232,223,203,0.08)',
  hairStrong: 'rgba(232,223,203,0.18)',
  userBubble: '#2a2620',
  accent:     'oklch(0.68 0.14 42)',
  accentInk:  '#15130f',
  danger:     'oklch(0.66 0.17 28)',
  incognitoBg:'#0a0907',
  incognitoInk:'#e8dfcb',
  incognitoEdge:'oklch(0.68 0.16 42)',
};

const FONT = {
  display: '"Cormorant Garamond", "EB Garamond", Georgia, serif',
  ui:      'Inter, "Helvetica Neue", system-ui, sans-serif',
  mono:    '"JetBrains Mono", "SF Mono", ui-monospace, monospace',
};

// Общий stylesheet — применяется глобально.
function SanctumGlobalStyle() {
  return (
    <style>{`
      @import url('https://fonts.googleapis.com/css2?family=Cormorant+Garamond:ital,wght@0,400;0,500;0,600;1,400;1,500&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap');
      * { box-sizing: border-box; }
      body { margin: 0; -webkit-font-smoothing: antialiased; text-rendering: optimizeLegibility; }
      .sm-display { font-family: ${FONT.display}; font-weight: 500; letter-spacing: -0.01em; }
      .sm-ui { font-family: ${FONT.ui}; }
      .sm-mono { font-family: ${FONT.mono}; font-feature-settings: 'zero' 1, 'ss01' 1; }
      .sm-kicker { font-family: ${FONT.mono}; font-size: 10px; letter-spacing: 0.18em; text-transform: uppercase; }
    `}</style>
  );
}

// Общие мелкие SVG-иконки (line-style, 20px grid, strokeWidth=1.5)
function SmIcon({ name, size = 20, color = 'currentColor' }) {
  const paths = {
    menu: <><line x1="3" y1="6" x2="17" y2="6"/><line x1="3" y1="10" x2="17" y2="10"/><line x1="3" y1="14" x2="17" y2="14"/></>,
    settings: <><circle cx="10" cy="10" r="2.5"/><path d="M10 2v2M10 16v2M18 10h-2M4 10H2M15.6 4.4l-1.4 1.4M5.8 14.2l-1.4 1.4M15.6 15.6l-1.4-1.4M5.8 5.8L4.4 4.4"/></>,
    plus: <><line x1="10" y1="4" x2="10" y2="16"/><line x1="4" y1="10" x2="16" y2="10"/></>,
    bolt: <polyline points="11 2 4 11 9 11 8 18 15 9 10 9 11 2" fill="none"/>,
    ghost: <><path d="M4 9a6 6 0 0 1 12 0v7l-2-1.5L12 16l-2-1.5L8 16l-2-1.5L4 16z"/><circle cx="8" cy="9" r="0.8" fill="currentColor" stroke="none"/><circle cx="12" cy="9" r="0.8" fill="currentColor" stroke="none"/></>,
    eyeOff: <><path d="M2 10s3-6 8-6c1.7 0 3.2.7 4.4 1.6"/><path d="M18 10s-3 6-8 6c-1.7 0-3.2-.7-4.4-1.6"/><line x1="3" y1="3" x2="17" y2="17"/><circle cx="10" cy="10" r="2.5"/></>,
    send: <><path d="M3 10l14-6-6 14-2-6-6-2z"/></>,
    send2: <><path d="M3 17l14-7L3 3l2 7zM5 10h8"/></>,
    mic: <><rect x="8" y="2" width="4" height="9" rx="2"/><path d="M5 9a5 5 0 0 0 10 0M10 14v4M7 18h6"/></>,
    attach: <path d="M13 5l-6 6a2.5 2.5 0 0 0 3.5 3.5l7-7a4 4 0 0 0-5.7-5.7l-7 7a5.5 5.5 0 0 0 7.8 7.8l5-5"/>,
    star: <polygon points="10 2 12.2 7.2 18 7.7 13.6 11.5 15 17 10 14 5 17 6.4 11.5 2 7.7 7.8 7.2" fill="none"/>,
    starFill: <polygon points="10 2 12.2 7.2 18 7.7 13.6 11.5 15 17 10 14 5 17 6.4 11.5 2 7.7 7.8 7.2" />,
    more: <><circle cx="4" cy="10" r="1.2" fill="currentColor" stroke="none"/><circle cx="10" cy="10" r="1.2" fill="currentColor" stroke="none"/><circle cx="16" cy="10" r="1.2" fill="currentColor" stroke="none"/></>,
    check: <polyline points="3 10 8 15 17 5" fill="none"/>,
    back: <><polyline points="11 4 5 10 11 16" fill="none"/><line x1="5" y1="10" x2="18" y2="10"/></>,
    edit: <><path d="M3 17l1-4 10-10 3 3-10 10-4 1z"/><line x1="12" y1="5" x2="15" y2="8"/></>,
    trash: <><polyline points="3 6 17 6" fill="none"/><path d="M5 6l1 11a2 2 0 0 0 2 2h4a2 2 0 0 0 2-2l1-11"/><path d="M8 6V4a2 2 0 0 1 2-2h0a2 2 0 0 1 2 2v2"/></>,
    warn: <><polygon points="10 2 18 17 2 17" fill="none"/><line x1="10" y1="8" x2="10" y2="12"/><circle cx="10" cy="14.5" r="0.6" fill="currentColor" stroke="none"/></>,
    chevronDown: <polyline points="5 8 10 13 15 8" fill="none"/>,
    download: <><polyline points="6 10 10 14 14 10" fill="none"/><line x1="10" y1="4" x2="10" y2="14"/><line x1="4" y1="17" x2="16" y2="17"/></>,
    sparkle: <><path d="M10 2l1.5 5.5L17 9l-5.5 1.5L10 16l-1.5-5.5L3 9l5.5-1.5z"/></>,
    spinner: <circle cx="10" cy="10" r="6" strokeDasharray="28" strokeDashoffset="18" fill="none"/>,
  };
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none"
         stroke={color} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      {paths[name]}
    </svg>
  );
}

Object.assign(window, { SANCTUM_LIGHT, SANCTUM_DARK, FONT, SanctumGlobalStyle, SmIcon });
