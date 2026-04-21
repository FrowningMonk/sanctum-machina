// Home hub — центральная точка, имя продукта крупной гарнитурой.

function ScreenHome({ theme }) {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', color: theme.ink }}>
      <SmAppBar
        theme={theme}
        left={<SmIcon name="menu" size={20} color={theme.ink}/>}
        center={
          <span style={{ fontFamily: FONT.mono, fontSize: 10, letterSpacing: '0.22em', textTransform: 'uppercase', color: theme.inkMuted }}>
            Sanctum · Home
          </span>
        }
        right={<SmIcon name="settings" size={18} color={theme.ink}/>}
      />
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', padding: '0 28px', textAlign: 'center' }}>
        {/* Sigil */}
        <div style={{ marginBottom: 24, color: theme.accent }}>
          <svg width="56" height="56" viewBox="0 0 80 80" fill="none" stroke="currentColor" strokeWidth="1" strokeLinecap="round" strokeLinejoin="round">
            <polygon points="40 6 74 40 40 74 6 40"/>
            <polygon points="40 18 62 40 40 62 18 40"/>
            <circle cx="40" cy="40" r="8"/>
            <line x1="40" y1="6" x2="40" y2="32"/>
            <line x1="40" y1="48" x2="40" y2="74"/>
            <line x1="6" y1="40" x2="32" y2="40"/>
            <line x1="48" y1="40" x2="74" y2="40"/>
          </svg>
        </div>
        <div className="sm-display" style={{ fontSize: 40, lineHeight: 0.95, color: theme.ink, marginBottom: 6 }}>
          Sanctum<br/><em style={{ fontStyle: 'italic', fontWeight: 400 }}>Machina</em>
        </div>
        <div className="sm-kicker" style={{ color: theme.inkDim, marginBottom: 44 }}>
          On-device · no network · private
        </div>

        {/* Primary action — быстрый чат */}
        <button style={{
          width: '100%', padding: '16px 18px',
          background: theme.ink, color: theme.bg,
          border: 'none', borderRadius: 2,
          fontFamily: FONT.ui, fontSize: 15, fontWeight: 500,
          letterSpacing: 0.1,
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          cursor: 'pointer',
        }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
            <SmIcon name="bolt" size={16} color={theme.bg}/>
            Новый быстрый чат
          </span>
          <SmIcon name="send2" size={16} color={theme.bg}/>
        </button>

        {/* Secondary — зайти в историю */}
        <button style={{
          width: '100%', marginTop: 10, padding: '14px 18px',
          background: 'transparent', color: theme.ink,
          border: `1px solid ${theme.hairStrong}`, borderRadius: 2,
          fontFamily: FONT.ui, fontSize: 14,
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          cursor: 'pointer',
        }}>
          <span>Открыть историю</span>
          <span style={{ fontFamily: FONT.mono, fontSize: 11, color: theme.inkDim }}>12</span>
        </button>
      </div>

      {/* Нижний статус — прогрев модели */}
      <div style={{
        padding: '14px 20px',
        borderTop: `1px solid ${theme.hair}`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        fontFamily: FONT.ui, fontSize: 12,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: theme.inkMuted }}>
          <SmDot state="ready" theme={theme}/>
          <span>Прогрета</span>
          <SmModelChip label="Gemma-4-E2B" theme={theme}/>
        </div>
        <span style={{ fontFamily: FONT.mono, fontSize: 10, color: theme.inkDim, letterSpacing: '0.1em' }}>
          2.7 GB · CPU
        </span>
      </div>
    </div>
  );
}

Object.assign(window, { ScreenHome });
