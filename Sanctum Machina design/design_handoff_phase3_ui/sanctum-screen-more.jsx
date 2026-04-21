// Draft chat, TopAppBar states, Model Manager, Empty.

function ScreenDraft({ theme }) {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', color: theme.ink, background: theme.bg }}>
      <SmAppBar
        theme={theme}
        left={<SmIcon name="back" size={20} color={theme.ink}/>}
        center={
          <button style={{
            display: 'inline-flex', alignItems: 'center', gap: 6,
            background: 'transparent', border: `1px solid ${theme.hairStrong}`,
            padding: '5px 10px', borderRadius: 2, color: theme.ink,
            fontFamily: FONT.mono, fontSize: 12, cursor: 'pointer',
          }}>
            <SmDot state="ready" theme={theme}/>
            gemma-4-E4B
            <SmIcon name="chevronDown" size={12} color={theme.inkMuted}/>
          </button>
        }
        right={<SmIcon name="more" size={18} color={theme.ink}/>}
        sub="Черновик · модель можно сменить до первой отправки"
      />

      {/* Dropdown model picker */}
      <div style={{ padding: '10px 16px 0', position: 'relative' }}>
        <div style={{
          background: theme.bgRaised, border: `1px solid ${theme.hairStrong}`,
          borderRadius: 2, padding: '4px 0', boxShadow: '0 12px 24px rgba(0,0,0,0.06)',
        }}>
          {[
            { id: 'gemma-4-E4B', size: '4.8 GB', active: true, star: true },
            { id: 'gemma-4-E2B', size: '2.7 GB' },
          ].map(m => (
            <div key={m.id} style={{
              display: 'flex', alignItems: 'center', gap: 10,
              padding: '10px 14px',
              background: m.active ? theme.bg : 'transparent',
            }}>
              <SmIcon name={m.star ? 'starFill' : 'star'} size={13} color={m.star ? theme.accent : theme.inkDim}/>
              <span className="sm-mono" style={{ fontSize: 12.5, color: theme.ink, flex: 1 }}>{m.id}</span>
              <span style={{ fontFamily: FONT.mono, fontSize: 10, color: theme.inkDim }}>{m.size}</span>
              {m.active && <SmIcon name="check" size={14} color={theme.accent}/>}
            </div>
          ))}
        </div>
      </div>

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', padding: '0 28px', textAlign: 'center' }}>
        <SmSigil size={36} color={theme.inkDim}/>
        <div className="sm-display" style={{ fontSize: 22, color: theme.inkMuted, marginTop: 12 }}>
          Новый разговор
        </div>
        <div style={{ fontFamily: FONT.ui, fontSize: 13, color: theme.inkDim, marginTop: 6, maxWidth: 240 }}>
          Выберите модель и начните. До первого сообщения чат не сохраняется.
        </div>
      </div>

      {/* Input */}
      <div style={{ padding: '10px 12px 12px', borderTop: `1px solid ${theme.hair}` }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8,
          background: theme.bgRaised, border: `1px solid ${theme.hairStrong}`,
          borderRadius: 22, padding: '6px 6px 6px 14px',
        }}>
          <SmIcon name="attach" size={18} color={theme.inkMuted}/>
          <div style={{ flex: 1, padding: '8px 0', fontFamily: FONT.ui, fontSize: 14, color: theme.inkDim }}>
            Спросите что-нибудь…
          </div>
          <button style={{
            width: 34, height: 34, borderRadius: '50%',
            background: theme.ink, color: theme.bg, border: 'none',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <SmIcon name="send2" size={16} color={theme.bg}/>
          </button>
        </div>
      </div>
    </div>
  );
}

// ── TopAppBar states: ready / warming / failed / loading ─────
function SmStateBar({ theme, state, label }) {
  const content = {
    ready: (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <SmDot state="ready" theme={theme}/>
        <span className="sm-mono" style={{ fontSize: 12, color: theme.ink, letterSpacing: '0.06em' }}>gemma-4-E4B</span>
      </div>
    ),
    warming: (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', border: `1.5px solid ${theme.hairStrong}`, borderTopColor: theme.ink, animation: 'sm-spin 0.9s linear infinite' }}/>
        <span className="sm-mono" style={{ fontSize: 12, color: theme.inkMuted }}>gemma-4-E4B</span>
        <span style={{ fontFamily: FONT.mono, fontSize: 10, color: theme.inkDim }}>· прогрев</span>
      </div>
    ),
    failed: (
      <button style={{
        display: 'inline-flex', alignItems: 'center', gap: 6,
        background: 'transparent', border: `1px solid ${theme.danger}`,
        padding: '5px 10px', borderRadius: 2, color: theme.danger,
        fontFamily: FONT.ui, fontSize: 12, cursor: 'pointer',
      }}>
        <SmIcon name="warn" size={12} color={theme.danger}/>
        Загрузить снова
      </button>
    ),
    loading: (
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', border: `1.5px solid ${theme.hairStrong}`, borderTopColor: theme.accent, animation: 'sm-spin 0.9s linear infinite' }}/>
        <span className="sm-mono" style={{ fontSize: 12, color: theme.inkMuted }}>Модель перезагружается…</span>
      </div>
    ),
  }[state];
  return (
    <div>
      <style>{`@keyframes sm-spin { to { transform: rotate(360deg); } } @keyframes sm-pulse { 0%,100%{opacity:.35} 50%{opacity:1} }`}</style>
      <SmAppBar
        theme={theme}
        left={<SmIcon name="menu" size={20} color={theme.ink}/>}
        center={content}
        right={<SmIcon name="more" size={18} color={theme.ink}/>}
        sub={label}
      />
    </div>
  );
}

function ScreenStates({ theme }) {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: theme.bgSunk }}>
      <div style={{
        padding: '14px 18px 10px',
        background: theme.bg,
        borderBottom: `1px solid ${theme.hair}`,
      }}>
        <div className="sm-kicker" style={{ color: theme.inkDim }}>Состояния TopAppBar</div>
        <div className="sm-display" style={{ fontSize: 20, color: theme.ink, marginTop: 2 }}>
          Четыре режима
        </div>
      </div>

      {[
        { state: 'ready',   title: 'Ready',   sub: 'Готов — имя модели, read-only label' },
        { state: 'warming', title: 'Warming', sub: 'Фоновый прогрев при старте app' },
        { state: 'loading', title: 'Loading', sub: 'Cross-model reinit в открытом чате' },
        { state: 'failed',  title: 'Failed',  sub: 'Silent init failure — кнопка «Загрузить»' },
      ].map((s, i) => (
        <div key={i} style={{ marginTop: 14, background: theme.bg }}>
          <div className="sm-kicker" style={{ padding: '8px 18px 0', color: theme.inkDim }}>
            {String(i+1).padStart(2,'0')} · {s.title}
          </div>
          <SmStateBar theme={theme} state={s.state} label={s.sub}/>
        </div>
      ))}
    </div>
  );
}

// ── Model Manager ────────────────────────────────────────────
function ScreenModels({ theme }) {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: theme.bg, color: theme.ink }}>
      <SmAppBar
        theme={theme}
        left={<SmIcon name="back" size={20} color={theme.ink}/>}
        center={
          <span className="sm-kicker" style={{ color: theme.inkMuted }}>Gradus · Модели</span>
        }
        right={<SmIcon name="more" size={18} color={theme.ink}/>}
      />

      <div style={{ padding: '18px 20px 10px' }}>
        <div className="sm-display" style={{ fontSize: 26, color: theme.ink, lineHeight: 1.1 }}>
          Scientia<br/><em style={{ fontStyle: 'italic', fontWeight: 400, color: theme.inkMuted }}>локальные модели</em>
        </div>
        <div style={{ fontFamily: FONT.ui, fontSize: 13, color: theme.inkMuted, marginTop: 10, lineHeight: 1.5 }}>
          ⭐ — модель по умолчанию, прогревается при старте приложения.
        </div>
      </div>

      {/* Model list */}
      <div style={{ padding: '10px 12px' }}>
        {[
          { id: 'gemma-4-E4B-it', variant: 'litert-lm', size: '4.8 GB', state: 'downloaded', star: true, active: true },
          { id: 'gemma-4-E2B-it', variant: 'litert-lm', size: '2.7 GB', state: 'downloaded' },
        ].map(m => (
          <div key={m.id} style={{
            border: `1px solid ${m.active ? theme.accent : theme.hairStrong}`,
            borderRadius: 2, padding: 14, marginBottom: 10,
            background: m.active ? theme.bgRaised : 'transparent',
            position: 'relative',
          }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
              <SmIcon name={m.star ? 'starFill' : 'star'} size={16} color={m.star ? theme.accent : theme.inkDim}/>
              <div style={{ flex: 1 }}>
                <div className="sm-mono" style={{ fontSize: 13, color: theme.ink }}>{m.id}</div>
                <div style={{ fontFamily: FONT.mono, fontSize: 10, color: theme.inkDim, marginTop: 3, letterSpacing: '0.08em' }}>
                  {m.variant.toUpperCase()} · {m.size}
                </div>
              </div>
              <SmIcon name="more" size={16} color={theme.inkMuted}/>
            </div>
            <div style={{
              display: 'flex', alignItems: 'center', gap: 8,
              marginTop: 12, paddingTop: 10,
              borderTop: `1px solid ${theme.hair}`,
            }}>
              <SmDot state={m.active ? 'ready' : 'idle'} theme={theme}/>
              <span style={{ fontFamily: FONT.ui, fontSize: 12, color: theme.inkMuted, flex: 1 }}>
                {m.active ? 'Прогрета и активна' : 'Скачана · не в памяти'}
              </span>
              <button style={{
                padding: '5px 12px', borderRadius: 2,
                background: m.active ? theme.ink : 'transparent',
                color: m.active ? theme.bg : theme.ink,
                border: m.active ? 'none' : `1px solid ${theme.hairStrong}`,
                fontFamily: FONT.ui, fontSize: 12, cursor: 'pointer',
              }}>{m.active ? 'Открыть чат' : 'Загрузить'}</button>
            </div>
          </div>
        ))}

        {/* Available */}
        <div className="sm-kicker" style={{ padding: '14px 6px 6px', color: theme.inkDim }}>Доступно</div>
        {[
          { id: 'gemma-4-E4B-base', size: '4.8 GB' },
        ].map(m => (
          <div key={m.id} style={{
            border: `1px dashed ${theme.hairStrong}`,
            padding: 14, marginBottom: 10,
            display: 'flex', alignItems: 'center', gap: 10,
          }}>
            <SmIcon name="download" size={16} color={theme.inkMuted}/>
            <div style={{ flex: 1 }}>
              <div className="sm-mono" style={{ fontSize: 13, color: theme.ink }}>{m.id}</div>
              <div style={{ fontFamily: FONT.mono, fontSize: 10, color: theme.inkDim, letterSpacing: '0.08em' }}>{m.size}</div>
            </div>
            <button style={{
              padding: '5px 12px', borderRadius: 2,
              background: 'transparent', color: theme.ink,
              border: `1px solid ${theme.hairStrong}`,
              fontFamily: FONT.ui, fontSize: 12,
            }}>Скачать</button>
          </div>
        ))}
      </div>
    </div>
  );
}

Object.assign(window, { ScreenDraft, SmStateBar, ScreenStates, ScreenModels });
