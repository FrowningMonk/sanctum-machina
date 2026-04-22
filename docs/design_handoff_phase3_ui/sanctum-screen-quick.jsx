// Quick chat — incognito: ярко, с цветной каймой и плашкой.

function ScreenQuickChat({ theme }) {
  const edge = theme.incognitoEdge;
  return (
    <div style={{
      flex: 1, display: 'flex', flexDirection: 'column',
      background: theme.incognitoBg, color: theme.incognitoInk,
      boxShadow: `inset 0 0 0 2px ${edge}`,
    }}>
      <style>{`@keyframes sm-pulse { 0%,100%{opacity:.35} 50%{opacity:1} }`}</style>
      <SmAppBar
        theme={theme}
        incognito
        left={<SmIcon name="menu" size={20} color={theme.incognitoInk}/>}
        center={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <SmIcon name="eyeOff" size={16} color={edge}/>
            <span className="sm-mono" style={{ fontSize: 12, color: theme.incognitoInk, letterSpacing: '0.08em' }}>
              gemma-4-E2B
            </span>
          </div>
        }
        right={<SmIcon name="more" size={18} color={theme.incognitoInk}/>}
        sub="Быстрый чат · ничего не сохраняется"
      />

      {/* «Печать» с сургучом — плашка incognito */}
      <div style={{
        margin: '16px 18px 8px',
        padding: '12px 14px',
        border: `1px solid ${edge}`,
        background: 'rgba(0,0,0,0.35)',
        display: 'flex', alignItems: 'flex-start', gap: 12,
      }}>
        <div style={{
          width: 32, height: 32, borderRadius: '50%',
          border: `1px solid ${edge}`, color: edge,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0,
        }}>
          <SmIcon name="eyeOff" size={16} color={edge}/>
        </div>
        <div style={{ flex: 1 }}>
          <div className="sm-kicker" style={{ color: edge, marginBottom: 4 }}>
            Sigillum · Incognito
          </div>
          <div style={{ fontFamily: FONT.ui, fontSize: 12.5, color: theme.incognitoInk, lineHeight: 1.45, opacity: 0.85 }}>
            Этот чат не попадёт в историю. При закрытии вложения и текст будут уничтожены.
          </div>
        </div>
      </div>

      <div style={{ flex: 1, overflow: 'auto', padding: '4px 18px 8px' }}>
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
          <div style={{
            maxWidth: '82%',
            background: 'rgba(232,223,203,0.08)',
            color: theme.incognitoInk,
            padding: '10px 13px', borderRadius: 14,
            borderBottomRightRadius: 4,
            border: `1px solid rgba(232,223,203,0.08)`,
            fontFamily: FONT.ui, fontSize: 14.5, lineHeight: 1.45,
          }}>
            Перепиши этот абзац нейтральнее и короче.
          </div>
        </div>

        <div style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, color: edge }}>
            <SmSigil size={14} color={edge}/>
            <span className="sm-kicker" style={{ fontSize: 9, color: edge }}>Machina · эфемерно</span>
          </div>
          <div style={{ fontFamily: FONT.ui, fontSize: 14.5, lineHeight: 1.55, color: theme.incognitoInk }}>
            Готово — оставил смысл, убрал оценочные слова и сократил на треть. Хочешь ещё короче?
          </div>
        </div>
      </div>

      {/* Input */}
      <div style={{
        padding: '10px 12px 12px',
        borderTop: `1px solid rgba(232,223,203,0.1)`,
      }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 8,
          background: 'rgba(232,223,203,0.06)',
          border: `1px solid ${edge}`,
          borderRadius: 22,
          padding: '6px 6px 6px 14px',
        }}>
          <SmIcon name="attach" size={18} color={theme.incognitoInk}/>
          <div style={{ flex: 1, padding: '8px 0', fontFamily: FONT.ui, fontSize: 14, color: 'rgba(232,223,203,0.5)' }}>
            Напишите и забудьте…
          </div>
          <button style={{
            width: 34, height: 34, borderRadius: '50%',
            background: edge, color: theme.incognitoBg, border: 'none',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <SmIcon name="send2" size={16} color={theme.incognitoBg}/>
          </button>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { ScreenQuickChat });
