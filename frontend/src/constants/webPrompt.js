// 联网功能写入系统提示的固定文案与拼接逻辑。

export const WEB_PROMPT = '对于实时信息联网查询相关信息，不确定时列出所有可能的信息；'

/** 按联网开关把固定提示词拼进或移出系统提示。 */
export function applyWebPrompt(systemPrompt, enabled) {
  const base = String(systemPrompt || '').split(WEB_PROMPT).join('').trim()
  return enabled ? (base ? base + WEB_PROMPT : WEB_PROMPT) : base
}