// Persistent chat — обычный чат, USER в bubble, ASSISTANT full-width.

function SmUserBubble({ text, theme }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 18 }}>
      <div style={{
        maxWidth: '82%',
        background: theme.userBubble,
        color: theme.ink,
        padding: '10px 13px',
        borderRadius: 14,
        borderBottomRightRadius: 4,
        fontFamily: FONT.ui, fontSize: 14.5, lineHeight: 1.45,
      }}>{text}</div>
    </div>
  );
}

function SmAssistantMsg({ theme, children, streaming = false, sigilColor }) {
  return (
    <div style={{ marginBottom: 22 }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8,
        color: theme.inkDim,
      }}>
        <SmSigil size={14} color={sigilColor || theme.accent}/>
        <span className="sm-kicker" style={{ fontSize: 9, color: theme.inkDim }}>
          Machina
        </span>
        {streaming && (
          <span style={{
            display: 'inline-block', width: 6, height: 6, borderRadius: '50%',
            background: theme.accent, animation: 'sm-pulse 1.1s ease-in-out infinite',
          }}/>
        )}
      </div>
      <div style={{
        fontFamily: FONT.ui, fontSize: 14.5, lineHeight: 1.55,
        color: theme.ink,
      }}>{children}</div>
    </div>
  );
}

function ScreenChat({ theme, bubbleStyle = 'gpt' }) {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', color: theme.ink, background: theme.bg }}>
      <style>{`@keyframes sm-pulse { 0%,100%{opacity:.35} 50%{opacity:1} }`}</style>
      {/* TopAppBar с именем модели */}
      <SmAppBar
        theme={theme}
        left={<SmIcon name="menu" size={20} color={theme.ink}/>}
        center={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <SmDot state="ready" theme={theme}/>
            <span className="sm-mono" style={{ fontSize: 12, color: theme.ink, letterSpacing: '0.06em' }}>
              gemma-4-E4B
            </span>
            <span style={{ fontFamily: FONT.mono, fontSize: 10, color: theme.inkDim }}>· it</span>
          </div>
        }
        right={<SmIcon name="more" size={18} color={theme.ink}/>}
      />

      {/* Лента */}
      <div style={{ flex: 1, overflow: 'auto', padding: '18px 18px 8px' }}>
        <div className="sm-kicker" style={{ color: theme.inkDim, textAlign: 'center', marginBottom: 18 }}>
          ── Вчера ──
        </div>

        <SmUserBubble theme={theme} text="Объясни простыми словами, что такое LoRA-адаптер и зачем он нужен для локальной модели."/>

        <SmAssistantMsg theme={theme}>
          <p style={{ margin: '0 0 10px' }}>
            <b style={{ fontWeight: 600 }}>LoRA</b> (Low-Rank Adaptation) — это способ «пришить» к уже обученной модели
            маленький набор параметров, не трогая её основной вес.
          </p>
          <p style={{ margin: '0 0 10px' }}>
            Вместо того чтобы заново считать миллиарды чисел, вы тренируете всего пару
            матриц низкого ранга — десятки мегабайт вместо гигабайт.
          </p>
          <p style={{ margin: 0 }}>
            Для локального запуска это выгодно: базовая модель лежит один раз, а поверх можно
            подменять «характеры» и задачи за доли секунды.
          </p>
        </SmAssistantMsg>

        <SmUserBubble theme={theme} text="А на телефоне это вообще потянет?"/>

        <SmAssistantMsg theme={theme} streaming>
          <p style={{ margin: '0 0 8px' }}>
            Да — при условии, что базовая модель уже квантована (INT4/INT8) и запущена через
            LiteRT-LM или llama.cpp. LoRA-дельта весит десятки Мб и почти не
          </p>
          <span style={{
            display: 'inline-block', width: 8, height: 14,
            background: theme.accent, verticalAlign: 'middle',
            animation: 'sm-pulse 0.8s ease-in-out infinite',
          }}/>
        </SmAssistantMsg>
      </div>

      {/* Input bar */}
      <div style={{
        padding: '10px 12px 12px',
        borderTop: `1px solid ${theme.hair}`,
        background: theme.bg,
      }}>
        <div style={{
          display: 'flex', alignItems: 'flex-end', gap: 8,
          background: theme.bgRaised,
          border: `1px solid ${theme.hairStrong}`,
          borderRadius: 22,
          padding: '6px 6px 6px 14px',
        }}>
          <SmIcon name="attach" size={18} color={theme.inkMuted}/>
          <div style={{
            flex: 1, padding: '8px 0',
            fontFamily: FONT.ui, fontSize: 14, color: theme.inkDim,
          }}>Напишите здесь…</div>
          <button style={{
            width: 34, height: 34, borderRadius: '50%',
            background: theme.ink, color: theme.bg,
            border: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center',
            cursor: 'pointer',
          }}>
            <SmIcon name="send2" size={16} color={theme.bg}/>
          </button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { ScreenChat, SmUserBubble, SmAssistantMsg });
