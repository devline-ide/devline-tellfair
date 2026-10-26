import { computed, onUnmounted, reactive, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseSpeech, type Speech, type Preset, type Voice } from './speech-model'
import { useSpeechFields } from './useSpeechFields'
import { sdk } from './sdk'
import { accountRead } from './account-api'

interface Job { id: string; state: string; error: string; charged: string; size: number; format: string }
export function useTellfair() {
  const { t, te, locale } = useI18n()
  const context = ref(sdk.getContext()), documentMode = computed(() => !!context.value.document)
  const configured = ref(false), settingsReady = ref(false), busy = ref(false), loaded = ref(false), invalid = ref(false)
  const pollError = ref(false), pollBusy = ref(false)
  const catalogBusy = ref(false), catalogError = ref(false), audioBusy = ref(false), exporting = ref(false), dirty = ref(false)
  const audioData = shallowRef<Blob>(), exportedPath = ref('')
  const documentRevision = ref(0), generatedSource = ref('')
  const error = ref(''), message = ref(''), audioUrl = ref(''), job = ref<Job>()
  const doc = reactive<Speech>({ schemaVersion: 1, kind: 'tellfairSpeech', text: '', presetId: 'news-anchor', format: 'mp3' })
  const presets = ref<Preset[]>([]), voices = ref<Voice[]>([]), customVoices = ref<Voice[]>([])
  const count = computed(() => Array.from(doc.text).length)
  const options = computed(() => [{ value: '', label: t('default') }, ...(doc.presetId && !presets.value.some(item => item.id === doc.presetId) ? [{ value: doc.presetId, label: doc.presetId }] : []), ...presets.value.map(item => ({ value: item.id, label: item.name }))])
  const formatOptions = ['mp3', 'wav', 'opus'].map(value => ({ value, label: value.toUpperCase() }))
  const writable = ref(false), canEdit = computed(() => loaded.value && !invalid.value && writable.value)
  const changed = ref(false)
  const fields = useSpeechFields(doc, canEdit, documentRevision)
  const selectedPreset = computed(() => presets.value.find(item => item.id === doc.presetId))
  const voiceOptions = computed(() => [{ value: '', label: t('presetVoice') }, ...(doc.customVoiceId && !customVoices.value.some(item => item.id === doc.customVoiceId) ? [{ value: '__custom', label: t('customVoice') }] : []), ...(doc.voiceId && !voices.value.some(item => item.id === doc.voiceId) ? [{ value: doc.voiceId, label: doc.voiceId }] : []), ...voices.value.map(item => ({ value: item.id, label: item.name + (item.lang ? ' · ' + item.lang.toUpperCase() : '') })), ...customVoices.value.map(item => ({ value: 'custom:' + item.id, label: item.name + ' · ' + t('ownVoice') }))])
  const stale = computed(() => job.value?.state === 'ready' && !!generatedSource.value && (!fields.valid.value || generatedSource.value !== serialized()))
  const canGenerate = computed(() => configured.value && canEdit.value && fields.valid.value && !!doc.text.trim() && count.value <= 5000 && !busy.value && job.value?.state !== 'running')
  let revision = 0, localEdits = 0, applying = false, disposed = false, pollTimer: ReturnType<typeof setTimeout> | undefined
  let editTimer: ReturnType<typeof setTimeout> | undefined, writing: Promise<void> = Promise.resolve()
  function report(cause: unknown) {
    error.value = cause instanceof Error ? te(cause.message) ? t(cause.message) : cause.message : t('failure')
    if (documentMode.value) void sdk.call('document', { action: 'notice', message: error.value.slice(0, 1000) }).catch(() => {})
  }
  async function safe(action: () => Promise<unknown>) {
    const hadError = !!error.value; error.value = ''
    try {
      await action()
      if (hadError && !error.value && documentMode.value) await sdk.call('document', { action: 'notice', message: '' })
    } catch (cause) { report(cause) }
  }
  function serialized() { return JSON.stringify(doc, null, 2) + '\n' }
  function flush(): Promise<void> {
    clearTimeout(editTimer)
    writing = writing.catch(() => {}).then(async () => {
      while (changed.value) {
        const text = serialized(); changed.value = false
        try {
          await sdk.call('document', { action: 'begin', revision })
          for (let offset = 0; offset < text.length; offset += 4000) await sdk.call('document', { action: 'chunk', text: text.slice(offset, offset + 4000) })
          const result = await sdk.call<{ revision: number; dirty: boolean }>('document', { action: 'commit' }); revision = result.revision; dirty.value = result.dirty
        } catch (cause) { changed.value = true; throw cause }
      }
      const status = await sdk.call<{ dirty: boolean }>('document', { action: 'activity', dirty: !fields.valid.value || changed.value })
      dirty.value = status.dirty
    })
    return writing
  }
  watch(doc, () => {
    if (applying || !canEdit.value) return
    localEdits++
    if (job.value?.state === 'cancelled' || job.value?.state === 'failed') job.value = undefined
    if (!changed.value) void sdk.call('document', { action: 'activity', dirty: true }).catch(report)
    changed.value = true; clearTimeout(editTimer)
    editTimer = setTimeout(() => { void safe(flush) }, 150)
  }, { deep: true, flush: 'sync' })
  async function read() {
    if (changed.value) await flush()
    await writing
    const expectedEdits = localEdits
    const source = await sdk.call<{ revision: number; text: string; length: number; writable: boolean; dirty: boolean }>('document', { action: 'read' })
    if (disposed || localEdits !== expectedEdits) return
    invalid.value = true
    if (source.length > source.text.length) throw Error(t('invalid'))
    let parsed: Speech
    try { parsed = parseSpeech(source.text) } catch { throw Error(t('invalid')) }
    applying = true
    try { for (const key of Object.keys(doc)) delete (doc as unknown as Record<string, unknown>)[key]; Object.assign(doc, parsed) }
    finally { applying = false }
    revision = source.revision; writable.value = source.writable; dirty.value = source.dirty; invalid.value = false; loaded.value = true; documentRevision.value++
  }
  let settingsLoading = false, settingsAt = 0
  async function settings() {
    if (settingsLoading) return
    settingsLoading = true
    try { configured.value = (await sdk.call<{ configured: boolean }>('settings')).configured; settingsReady.value = true; settingsAt = Date.now() }
    finally { settingsLoading = false }
  }
  function focusSettings() { if (context.value.visible && Date.now() - settingsAt > 1000) void safe(settings) }
  async function loadCatalog() {
    if (catalogBusy.value || !context.value.visible) return
    catalogBusy.value = true; catalogError.value = false
    try {
      const catalog = await accountRead<{ presets: Preset[]; voices: Voice[]; piperVoices?: Voice[] }>('presets', {}, () => !disposed && context.value.visible)
      if (!disposed) { presets.value = catalog.presets ?? []; voices.value = [...(catalog.voices ?? []), ...(catalog.piperVoices ?? []).map(item => ({ ...item, name: item.name + ' · Piper' }))] }
      if (configured.value) {
        try { const own = await accountRead<{ voices: Voice[] }>('voices', {}, () => !disposed && context.value.visible); if (!disposed) customVoices.value = own.voices ?? [] }
        catch (cause) { if (!disposed && context.value.visible) report(cause) }
      } else customVoices.value = []
    } catch { if (!disposed) catalogError.value = true }
    finally { catalogBusy.value = false }
  }
  async function prepareSave() { if (!fields.valid.value) throw Error(t('fixFields')); await flush() }
  watch(() => fields.errors.value.length > 0, invalid => { if (canEdit.value) void sdk.call('document', { action: 'activity', dirty: invalid }).catch(report) })
  function optional(name: 'presetId' | 'voiceId' | 'customVoiceId', value: string) { if (value) doc[name] = value; else delete doc[name] }
  function clearAudio() { if (audioUrl.value) URL.revokeObjectURL(audioUrl.value); audioUrl.value = ''; audioData.value = undefined }
  async function receiveAudio(value: Job) {
    const chunks: Uint8Array[] = []
    for (let offset = 0; offset < value.size; offset += 48000) {
      if (disposed || !context.value.visible || job.value?.id !== value.id) return
      const chunk = await sdk.call<{ data: string }>('audio', { id: value.id, offset })
      chunks.push(Uint8Array.from(atob(chunk.data), character => character.charCodeAt(0)))
    }
    if (disposed || !context.value.visible || job.value?.id !== value.id) return
    clearAudio(); audioData.value = new Blob(chunks as BlobPart[], { type: value.format === 'mp3' ? 'audio/mpeg' : value.format === 'wav' ? 'audio/wav' : 'audio/ogg' }); audioUrl.value = URL.createObjectURL(audioData.value)
  }
  async function poll() {
    clearTimeout(pollTimer)
    if (disposed || !context.value.visible || !job.value || pollBusy.value) return
    pollBusy.value = true; pollError.value = false
    const id = job.value.id
    try {
      const value = await sdk.call<Job>('job', { id })
      if (disposed || job.value?.id !== id) return
      job.value = value
      if (value.state === 'running') pollTimer = setTimeout(() => { void poll() }, 900)
      else if (value.state === 'ready' && !audioUrl.value) { audioBusy.value = true; try { await receiveAudio(value) } finally { audioBusy.value = false } }
      else if (value.error) report(Error(value.error))
    } catch (cause) { pollError.value = true; report(cause) }
    finally { pollBusy.value = false }
  }
  async function generate() {
    if (!canGenerate.value) return
    busy.value = true; message.value = ''
    try {
      await prepareSave(); const source = serialized()
      const next = await sdk.call<Job>('generate', { source })
      audio.value?.pause(); clearAudio(); exportedPath.value = ''; job.value = next; generatedSource.value = source; await poll()
    }
    finally { busy.value = false }
  }
  async function openCabinet() { await sdk.call('cabinet') }
  async function openSettings() { await sdk.call('host.openSettings') }
  async function saveDocument() { await prepareSave(); await sdk.call('document', { action: 'save' }); dirty.value = false }
  async function cancelJob() { job.value = await sdk.call<Job>('cancel', { id: job.value!.id }) }
  async function exportAudio() {
    if (exporting.value || job.value?.state !== 'ready') return
    exporting.value = true
    try {
      const id = job.value.id, result = await sdk.call<{ path: string }>('exportAudio', { id })
      if (disposed || job.value?.id !== id) return
      exportedPath.value = result.path; message.value = t('saved', { path: result.path })
      await sdk.call('document', { action: 'filesChanged', path: result.path })
    }
    finally { exporting.value = false }
  }
  async function revealAudio() { if (exportedPath.value) await sdk.call('document', { action: 'revealFile', path: exportedPath.value }) }
  async function initialize() {
    await settings()
    if (!documentMode.value) return
    await read()
    void loadCatalog()
  }
  const stopContext = sdk.onContextChanged(value => {
    const becameVisible = value.visible && !context.value.visible
    context.value = value; locale.value = value.locale.startsWith('ru') ? 'ru' : 'en'
    if (!value.visible) { clearTimeout(pollTimer); audio.value?.pause() }
    else { if (becameVisible) void safe(settings)
      if (documentMode.value && loaded.value && fields.valid.value && !busy.value) void safe(read)
      if (job.value && ['running', 'ready'].includes(job.value.state)) void poll()
    }
  })
  const stopSave = sdk.onBeforeDocumentSave(prepareSave)
  async function keydown(event: KeyboardEvent) {
    if (event.defaultPrevented || event.isComposing) return
    if (!(event.ctrlKey || event.metaKey) || event.altKey || !documentMode.value) return
    if (event.code === 'Enter' && !event.shiftKey) { event.preventDefault(); await safe(generate); return }
    const action = event.code === 'KeyS' ? 'save' : event.code === 'KeyZ' ? event.shiftKey ? 'redo' : 'undo' : event.code === 'KeyY' ? 'redo' : ''
    if (!action) return
    event.preventDefault()
    await safe(async () => { if (action === 'save') await prepareSave(); else await flush(); await sdk.call('document', { action }); if (action !== 'save') await read(); else dirty.value = false })
  }
  const audio = ref<HTMLAudioElement>()
  window.addEventListener('focus', focusSettings)
  onUnmounted(() => { disposed = true; clearTimeout(editTimer); clearTimeout(pollTimer); stopContext(); stopSave(); clearAudio(); window.removeEventListener('focus', focusSettings) })
  void safe(initialize)
  return { t, locale, context, documentMode, configured, settingsReady, settings, busy, loaded, invalid, error, message, audioUrl, job, doc,
    presets, voices, count, options, voiceOptions, formatOptions, canEdit, changed, dirty, safe, read, optional, generate, openCabinet, openSettings, saveDocument, cancelJob, exportAudio, keydown, audio,
    audioData, exportedPath, revealAudio, poll, pollError, pollBusy, catalogBusy, catalogError, loadCatalog, selectedPreset, fields, canGenerate, stale, audioBusy, exporting }
}
