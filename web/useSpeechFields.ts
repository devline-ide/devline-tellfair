import { computed, reactive, watch, type Ref } from 'vue'
import { speechParameters, type Speech } from './speech-model'

/** Invalid field drafts stay visible without silently replacing valid document values. */
export function useSpeechFields(doc: Speech, canEdit: Ref<boolean>, documentRevision: Ref<number>) {
  const drafts = reactive<Record<string, string>>({ seed: '' })
  function sync() {
    drafts.seed = doc.seed === undefined ? '' : String(doc.seed)
    for (const field of speechParameters) drafts[field.key] = doc.params?.[field.key] === undefined ? '' : String(doc.params[field.key])
  }
  watch(documentRevision, sync, { immediate: true, flush: 'sync' })
  const invalid = (key: string, text: string) => {
    if (!text.trim()) return false
    const number = Number(text), field = speechParameters.find(item => item.key === key)
    return !Number.isFinite(number) || (key === 'seed' ? !Number.isInteger(number) || number < -2147483648 || number > 2147483647 : !field || number < field.min || number > field.max)
  }
  const errors = computed(() => Object.keys(drafts).filter(key => invalid(key, drafts[key] ?? '')))
  function update(key: string, text: string) {
    if (!canEdit.value) return
    drafts[key] = text
    if (invalid(key, text)) return
    if (key === 'seed') { if (text.trim()) doc.seed = Number(text); else delete doc.seed; return }
    if (text.trim()) { doc.params ??= {}; doc.params[key] = Number(text) }
    else if (doc.params) { delete doc.params[key]; if (!Object.keys(doc.params).length) delete doc.params }
  }
  function reset() { if (!canEdit.value) return; delete doc.params; delete doc.seed; sync() }
  return { drafts, errors, update, reset, valid: computed(() => !errors.value.length) }
}
