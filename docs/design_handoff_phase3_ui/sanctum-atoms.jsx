// Sanctum Machina — shared UI atoms built on top of tokens.

// Render ruler marks (занавесочка/декор) — тонкая «оккультная» подпись-мотив.
function SmRule({ color, mono = false }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8, color,
      fontFamily: mono ? FONT.mono : FONT.ui,
      fontSize: 10, letterSpacing: '0.18em', textTransform: 'uppercase',
      opacity: 0.7,
    }}>
      <span style={{ flex: 1, height: 1, background: 'currentColor', opacity: 0.35 }}/>
      <span>·</span>
      <span style={{ flex: 1, height: 1, background: 'currentColor', opacity: 0.35 }}/>
    </div>
  );
}

// Монограмма-сигил для ассистента — ромб с засечкой, собственный знак.
function SmSigil({ size = 20, color = 'currentColor' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none"
         stroke={color} strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="10 2 18 10 10 18 2 10"/>
      <circle cx="10" cy="10" r="2.2"/>
      <line x1="10" y1="2" x2="10" y2="7.8"/>
      <line x1="10" y1="12.2" x2="10" y2="18"/>
    </svg>
  );
}

// Чип модели — моно-шрифт в мелких капителях, тонкая рамка.
function SmModelChip({ label, tone = 'default', theme }) {
  const fg = tone === 'accent' ? theme.accent : theme.inkMuted;
  return (
    <span className="sm-mono" style={{
      fontSize: 10, letterSpacing: '0.1em', textTransform: 'uppercase',
      padding: '3px 7px',
      border: `1px solid ${theme.hairStrong}`,
      color: fg,
      borderRadius: 2,
      whiteSpace: 'nowrap',
      background: 'transparent',
    }}>{label}</span>
  );
}

// Точка состояния (ready/warming/failed)
function SmDot({ state, theme }) {
  const color = {
    ready:   theme.accent,
    warming: theme.inkMuted,
    failed:  theme.danger,
    idle:    theme.inkDim,
  }[state];
  return (
    <span style={{
      display: 'inline-block', width: 6, height: 6, borderRadius: '50%',
      background: color,
      boxShadow: state === 'ready' ? `0 0 0 2px ${color}22` : 'none',
    }}/>
  );
}

// TopAppBar (Sanctum) — кастомный, тонкий, с линиями вместо Material fill.
function SmAppBar({ theme, left, center, right, sub, incognito = false }) {
  const bg = incognito ? theme.incognitoBg : theme.bg;
  const ink = incognito ? theme.incognitoInk : theme.ink;
  return (
    <div style={{
      background: bg, color: ink,
      borderBottom: `1px solid ${incognito ? 'rgba(232,223,203,0.08)' : theme.hair}`,
      boxShadow: incognito ? `inset 0 -2px 0 ${theme.incognitoEdge}` : 'none',
    }}>
      <div style={{
        height: 52, display: 'flex', alignItems: 'center', padding: '0 4px',
      }}>
        <div style={{ width: 48, height: 48, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          {left}
        </div>
        <div style={{ flex: 1, minWidth: 0, display: 'flex', justifyContent: 'center' }}>
          {center}
        </div>
        <div style={{ width: 48, height: 48, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          {right}
        </div>
      </div>
      {sub && (
        <div style={{
          padding: '0 16px 8px', fontFamily: FONT.mono,
          fontSize: 10, letterSpacing: '0.18em', textTransform: 'uppercase',
          color: incognito ? theme.incognitoEdge : theme.inkDim,
        }}>{sub}</div>
      )}
    </div>
  );
}

// Маленькая кнопка-пилюля (текстовая)
function SmGhostButton({ children, theme, tone = 'default', icon, onClick }) {
  const fg = tone === 'accent' ? theme.accent : theme.ink;
  return (
    <button onClick={onClick} style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '5px 10px', borderRadius: 2,
      background: 'transparent',
      border: `1px solid ${theme.hairStrong}`,
      color: fg, fontFamily: FONT.ui, fontSize: 13, cursor: 'pointer',
    }}>
      {icon && <SmIcon name={icon} size={14} />}
      {children}
    </button>
  );
}

Object.assign(window, { SmRule, SmSigil, SmModelChip, SmDot, SmAppBar, SmGhostButton });
