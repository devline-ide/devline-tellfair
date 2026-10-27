<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { UISkeleton, UIButton, UIIcon, UITextArea, UIAudioPlayer, vUITooltip as vTippy } from '@devline/ui-web/controls'
import { useTellfair } from './useTellfair'
import SpeechProperties from './SpeechProperties.vue'

const s = useTellfair(), { t, locale } = useI18n()
const filename = computed(() => s.context.value.document?.path.split('/').at(-1) ?? 'Tellfair')
const generateHint = computed(() => !s.settingsReady.value ? t('loading') : !s.configured.value ? t('missingKey') : !s.loaded.value ? t('loading') : !s.canEdit.value ? t('readOnly') : !s.fields.valid.value ? t('fixFields') : s.count.value > 5000 ? t('textLimit') : !s.doc.text.trim() ? t('emptyText') : t('generateShortcut'))
const playback = reactive({ volume: 1, muted: false, speed: 1, loop: false })
const audioSize = computed(() => new Intl.NumberFormat(locale.value, { maximumFractionDigits: 1 }).format((s.job.value?.size ?? 0) / 1024) + ' KB')
const chargedCount = computed(() => { const value = Number(s.job.value?.charged); return s.job.value?.charged && Number.isSafeInteger(value) && value >= 0 ? value : undefined })
const playerLabels = computed(() => ({ player: t('listen'), play: t('audioPlay'), pause: t('audioPause'), stop: t('audioStop'), loop: t('audioLoop'), mute: t('audioMute'), unmute: t('audioUnmute'), volume: t('audioVolume'), speed: t('audioSpeed'), seek: t('audioSeek'), waveform: t('audioWaveform'), noWaveform: t('audioNoWaveform'), loading: t('audioLoading'), error: t('audioError') }))
function mediaError(message: string) { void s.safe(async () => { throw Error(message) }) }
function audioRef(value: unknown) { s.audio.value = value instanceof HTMLAudioElement ? value : undefined }
</script>

<template>
  <section class="tellfair tellfair--editor" @keydown="s.keydown">
      <header class="tellfair__toolbar">
        <UIIcon name="soundwave" /><strong class="tellfair__filename">{{ filename }}</strong>
        <span class="tellfair__toolbar-actions"><UIButton variant="quiet" :disabled="!s.canEdit.value || !s.fields.valid.value || s.busy.value || !s.dirty.value && !s.changed.value" :aria-label="t('save')" v-tippy="t('saveShortcut')" @click="s.safe(s.saveDocument)"><UIIcon name="floppy" /></UIButton><UIButton variant="quiet" :disabled="s.changed.value || s.busy.value || !s.fields.valid.value" :aria-label="t('refresh')" v-tippy="t('refresh')" @click="s.safe(s.read)"><UIIcon name="arrow-clockwise" /></UIButton><UIButton variant="quiet" :aria-label="t('settings')" v-tippy="t('settings')" @click="s.safe(s.openSettings)"><UIIcon name="key" /></UIButton></span>
      </header>
      <div v-if="s.settingsReady.value && !s.configured.value" class="tellfair__prerequisite"><UIIcon name="key" /><span>{{ t('missingKey') }}</span><UIButton variant="quiet" @click="s.safe(s.openSettings)">{{ t('setupKey') }}</UIButton></div>

      <p v-if="s.invalid.value" class="tellfair__state" role="alert">{{ t('invalid') }}</p>
      <UISkeleton v-else-if="!s.loaded.value" variant="editor" :label="t('loading')" />
      <div v-else class="tellfair__workspace">
        <main class="tellfair__script">
          <div class="tellfair__script-heading"><label for="tf-text">{{ t('text') }}</label><span v-if="!s.canEdit.value" class="tellfair__hint">{{ t('readOnly') }}</span><span id="tf-text-count" class="tellfair__hint" :class="{ 'tellfair__limit': s.count.value > 5000 }">{{ t('chars', { count: s.count.value }) }}</span></div>
          <UITextArea id="tf-text" v-model="s.doc.text" class="tellfair__text" :readonly="!s.canEdit.value" :placeholder="t('textPlaceholder')" aria-describedby="tf-text-count" :aria-invalid="s.count.value > 5000 || undefined" />
          <p v-if="s.count.value > 5000" class="tellfair__limit" role="alert">{{ t('textLimit') }}</p>
          <section v-if="s.job.value?.state === 'ready'" class="tellfair__result" :aria-label="t('ready')">
            <div class="tellfair__result-heading"><UIIcon name="check-circle" /><strong>{{ t('ready') }}</strong><span class="tellfair__hint">{{ s.job.value.format.toUpperCase() }} · {{ audioSize }}</span></div>
            <p v-if="s.stale.value" class="tellfair__stale" role="status"><UIIcon name="exclamation-circle" />{{ t('staleAudio') }}</p>
            <UIAudioPlayer v-if="s.audioUrl.value" v-model:volume="playback.volume" v-model:muted="playback.muted" v-model:speed="playback.speed" v-model:loop="playback.loop" :src="s.audioUrl.value" :data="s.audioData.value" :active="s.context.value.visible" :labels="playerLabels" @player="audioRef" @error="mediaError" />
            <UISkeleton v-else-if="s.audioBusy.value" variant="waveform" :label="t('loadingAudio')" />
            <div class="tellfair__result-actions"><span v-if="chargedCount !== undefined" class="tellfair__hint">{{ t('charged', chargedCount) }}</span><UIButton :busy="s.exporting.value" :disabled="s.exporting.value" v-tippy="t('exportAudio')" @click="s.safe(s.exportAudio)"><UIIcon name="download" />{{ t('exportShort') }}</UIButton></div>
          </section>
          <div v-if="s.exportedPath.value" class="tellfair__exported"><span class="tellfair__hint is-selectable" role="status">{{ s.message.value }}</span><UIButton variant="quiet" @click="s.safe(s.revealAudio)"><UIIcon name="folder2-open" />{{ t('revealAudio') }}</UIButton></div>
        </main>
        <SpeechProperties :state="s" />
      </div>
      <div v-if="s.pollError.value" class="tellfair__prerequisite" role="status"><UIIcon name="exclamation-circle" /><span>{{ t('jobConnectionLost') }}</span><UIButton :busy="s.pollBusy.value" :disabled="s.pollBusy.value" @click="s.safe(s.poll)">{{ t('retryStatus') }}</UIButton></div>
      <footer class="tellfair__footer">
        <UIButton v-if="s.job.value?.state === 'running'" :aria-label="t('cancel')" @click="s.safe(s.cancelJob)"><UIIcon name="stop-fill" />{{ t('cancel') }}</UIButton>
        <UIButton v-else variant="primary" :disabled="!s.canGenerate.value" :busy="s.busy.value" v-tippy="generateHint" @click="s.safe(s.generate)"><UIIcon name="play-fill" />{{ t('generate') }}</UIButton>
        <span class="tellfair__hint" role="status">{{ s.job.value?.state === 'running' ? t('running') : s.job.value?.state === 'cancelled' ? t('cancelled') : !s.fields.valid.value ? t('fixFields') : t('generateShortcut') }}</span>
      </footer>

  </section>
</template>
