// Sanctum Android frame — тоньше, без Material-цветов, собственная оправа.

function SmPhoneFrame({ theme, children, label }) {
  const W = 380, H = 780;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start' }}>
      {label && (
        <div className="sm-kicker" style={{ color: 'rgba(60,50,40,0.75)', marginBottom: 10 }}>
          {label}
        </div>
      )}
      <div style={{
        width: W, height: H,
        borderRadius: 42,
        padding: 10,
        background: theme.name === 'dark' ? '#06050a' : '#1a1511',
        boxShadow: '0 30px 80px rgba(20,15,10,0.35), 0 8px 24px rgba(20,15,10,0.2), inset 0 0 0 1px rgba(255,255,255,0.04)',
      }}>
        <div style={{
          width: '100%', height: '100%',
          borderRadius: 34,
          overflow: 'hidden',
          background: theme.bg,
          display: 'flex', flexDirection: 'column',
          position: 'relative',
        }}>
          {/* Status bar */}
          <div style={{
            height: 32, display: 'flex', alignItems: 'center',
            justifyContent: 'space-between', padding: '0 20px',
            color: theme.ink, fontFamily: FONT.ui, fontSize: 13, fontWeight: 500,
            flexShrink: 0,
          }}>
            <span>9:30</span>
            <div style={{
              position: 'absolute', left: '50%', top: 10, transform: 'translateX(-50%)',
              width: 16, height: 16, borderRadius: '50%',
              background: theme.name === 'dark' ? '#000' : '#111',
            }}/>
            <div style={{ display: 'flex', gap: 5, alignItems: 'center' }}>
              <svg width="12" height="12" viewBox="0 0 12 12"><path d="M0 10L6 4l6 6H0z" fill={theme.ink}/></svg>
              <svg width="12" height="12" viewBox="0 0 12 12"><rect x="1" y="3" width="9" height="6" rx="1" fill="none" stroke={theme.ink}/><rect x="2" y="4" width="6" height="4" fill={theme.ink}/></svg>
            </div>
          </div>
          {children}
          {/* Home indicator */}
          <div style={{
            height: 18, display: 'flex', alignItems: 'center', justifyContent: 'center',
            flexShrink: 0, background: 'transparent',
          }}>
            <div style={{
              width: 100, height: 3, borderRadius: 2,
              background: theme.inkMuted, opacity: 0.5,
            }}/>
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { SmPhoneFrame });
