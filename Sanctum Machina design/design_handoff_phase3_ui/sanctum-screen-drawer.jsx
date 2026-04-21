// Drawer — история чатов с группами и incognito-акцентом.

function ScreenDrawer({ theme }) {
  const groups = [
    { label: 'Сегодня', items: [
      { title: 'Review NOTES.md · Phase 3 warmup', model: 'E4B', date: '14:22', active: true },
      { title: 'Отладка silent init failure', model: 'E2B', date: '10:05' },
    ]},
    { label: 'Вчера', items: [
      { title: 'auto-title алгоритм: 20 символов…', model: 'E4B', date: '20:41' },
      { title: 'Room schema v1 review', model: 'E2B', date: '16:12' },
      { title: 'Перевод Gemma-промптов', model: 'E4B', date: '09:30' },
    ]},
    { label: 'На этой неделе', items: [
      { title: 'Сравнение TTFT Phase 3 vs Gallery', model: 'E2B', date: 'Ср' },
      { title: 'Staging-dir для вложений', model: 'E4B', date: 'Вт' },
    ]},
    { label: 'Раньше', items: [
      { title: 'Первый тест на Honor 200', model: 'E2B', date: '12.04' },
      { title: 'Разбор manifest.on-device', model: 'E4B', date: '08.04' },
    ]},
  ];

  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: theme.bgSunk, color: theme.ink }}>
      {/* Header */}
      <div style={{
        padding: '16px 20px 14px',
        borderBottom: `1px solid ${theme.hair}`,
      }}>
        <div className="sm-kicker" style={{ color: theme.inkDim, marginBottom: 6 }}>
          Historia · История чатов
        </div>
        <div className="sm-display" style={{ fontSize: 26, color: theme.ink, lineHeight: 1 }}>
          Sanctum<span style={{ color: theme.inkDim }}>/</span><em style={{ fontStyle: 'italic', fontWeight: 400 }}>Machina</em>
        </div>
      </div>

      {/* New chat + incognito */}
      <div style={{ padding: '12px 12px 8px', display: 'flex', gap: 8 }}>
        <button style={{
          flex: 1, padding: '11px 12px', borderRadius: 2,
          background: theme.ink, color: theme.bg, border: 'none',
          fontFamily: FONT.ui, fontSize: 13, fontWeight: 500, cursor: 'pointer',
          display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'center',
        }}>
          <SmIcon name="plus" size={14} color={theme.bg}/>
          Новый чат
        </button>
        <button style={{
          padding: '11px 12px', borderRadius: 2,
          background: theme.incognitoBg, color: theme.incognitoInk,
          border: `1px solid ${theme.incognitoEdge}`,
          fontFamily: FONT.ui, fontSize: 13, cursor: 'pointer',
          display: 'flex', alignItems: 'center', gap: 8,
        }}>
          <SmIcon name="eyeOff" size={14} color={theme.incognitoEdge}/>
          Быстрый
        </button>
      </div>

      {/* List */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        {groups.map(g => (
          <div key={g.label}>
            <div className="sm-kicker" style={{
              padding: '14px 20px 6px', color: theme.inkDim,
            }}>{g.label}</div>
            {g.items.map((it, i) => (
              <div key={i} style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '11px 20px',
                background: it.active ? theme.bgRaised : 'transparent',
                borderLeft: it.active ? `2px solid ${theme.accent}` : `2px solid transparent`,
                cursor: 'pointer',
              }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{
                    fontFamily: FONT.ui, fontSize: 13.5,
                    color: theme.ink,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                  }}>{it.title}</div>
                </div>
                <SmModelChip label={it.model} tone={it.active ? 'accent' : 'default'} theme={theme}/>
                <span style={{
                  fontFamily: FONT.mono, fontSize: 10, color: theme.inkDim,
                  letterSpacing: '0.08em', minWidth: 30, textAlign: 'right',
                }}>{it.date}</span>
              </div>
            ))}
          </div>
        ))}
      </div>

      {/* Footer */}
      <div style={{
        borderTop: `1px solid ${theme.hair}`,
        padding: '8px 6px',
        display: 'flex',
      }}>
        {['Модели','О приложении'].map((l, i) => (
          <button key={i} style={{
            flex: 1, padding: '10px 8px',
            background: 'transparent', border: 'none',
            fontFamily: FONT.ui, fontSize: 12.5, color: theme.inkMuted,
            cursor: 'pointer',
          }}>{l}</button>
        ))}
      </div>
    </div>
  );
}

Object.assign(window, { ScreenDrawer });
