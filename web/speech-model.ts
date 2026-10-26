export interface Speech {
  schemaVersion: number; kind: string; text: string; presetId?: string; voiceId?: string; customVoiceId?: string
  format: string; seed?: number; params?: Record<string, number>
}
export interface Preset { id: string; name: string; hint?: string; voice?: string; params?: Record<string, number> }
export interface Voice { id: string; name: string; lang?: string; vibe?: string }
export const speechParameters = [
  { key: 'speed_factor', label: 'speed', min: .5, max: 2, fallback: 1 },
  { key: 'exaggeration', label: 'exaggeration', min: .25, max: 2, fallback: .5 },
  { key: 'cfg_weight', label: 'cfg_weight', min: 0, max: 1, fallback: .5 },
  { key: 'temperature', label: 'temperature', min: .05, max: 1.5, fallback: .7 },
] as const
export function parseSpeech(text: string): Speech {
  const parsed = JSON.parse(text)
  if (!parsed || parsed.schemaVersion !== 1 || parsed.kind !== 'tellfairSpeech' || typeof parsed.text !== 'string'
    || !['mp3', 'wav', 'opus'].includes(parsed.format ?? 'mp3')
    || Object.keys(parsed).some(key => !['schemaVersion','kind','text','presetId','voiceId','customVoiceId','params','format','seed'].includes(key))
    || ['presetId', 'voiceId', 'customVoiceId'].some(key => parsed[key] !== undefined && (typeof parsed[key] !== 'string' || !parsed[key].trim() || parsed[key].length > 200))
    || parsed.seed !== undefined && (!Number.isInteger(parsed.seed) || parsed.seed < -2147483648 || parsed.seed > 2147483647)
    || parsed.params !== undefined && (!parsed.params || typeof parsed.params !== 'object' || Array.isArray(parsed.params)
      || Object.entries(parsed.params).some(([key, value]) => { const field = speechParameters.find(item => item.key === key); return !field || typeof value !== 'number' || !Number.isFinite(value) || value < field.min || value > field.max }))) throw Error('invalid')
  return { ...parsed, format: parsed.format ?? 'mp3' }
}
