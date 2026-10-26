<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { UISkeleton, UIButton, UIIcon, UIInfoTip, UIInput, UISelect, UISlider, vUITooltip as vTippy } from '@devline/ui-web'
import { speechParameters } from './speech-model'
import type { useTellfair } from './useTellfair'

const props = defineProps<{ state: ReturnType<typeof useTellfair> }>()
const { t } = useI18n(), s = props.state
const advanced = ref(false)
watch(s.loaded, loaded => { if (loaded && (s.doc.seed !== undefined || s.doc.customVoiceId || Object.keys(s.doc.params ?? {}).length)) advanced.value = true }, { immediate: true })
const modified = computed(() => Object.keys(s.doc.params ?? {}).length + (s.doc.seed === undefined ? 0 : 1))
function fieldError(key: string) {
  if (!s.fields.errors.value.includes(key)) return undefined
  const field = speechParameters.find(item => item.key === key)
  return field ? t('numberRange', { min: field.min, max: field.max }) : t('seedRange')
}
function sliderValue(field: typeof speechParameters[number]) {
  const value = s.doc.params?.[field.key] ?? s.selectedPreset.value?.params?.[field.key] ?? field.fallback
  return Number.isFinite(value) ? Math.max(field.min, Math.min(field.max, value)) : field.fallback
}
function custom(value: string) { s.optional('customVoiceId', value.trim()); if (value.trim()) delete s.doc.voiceId }
function voice(value: string) { if (value.startsWith('custom:')) { s.optional('customVoiceId', value.slice(7)); delete s.doc.voiceId; return }; if (value === '__custom') { advanced.value = true; return }; s.optional('voiceId', value); delete s.doc.customVoiceId }
</script>

<template>
  <aside class="tellfair__properties" :aria-label="t('voiceSettings')">
    <h2>{{ t('voiceSettings') }}</h2>
    <div class="tellfair__field">
      <div class="tellfair__field-label"><label for="tf-preset">{{ t('preset') }}</label><UIButton variant="quiet" :disabled="s.catalogBusy.value" :busy="s.catalogBusy.value" :aria-label="t('reloadVoices')" v-tippy="t('reloadVoices')" @click="s.loadCatalog"><UIIcon name="arrow-clockwise" /></UIButton></div>
      <UISelect input-id="tf-preset" :model-value="s.doc.presetId ?? ''" :options="s.options.value" searchable :disabled="!s.canEdit.value" :placeholder="t('default')" @update:model-value="s.optional('presetId', String($event ?? ''))" />
      <UISkeleton v-if="s.catalogBusy.value" variant="text" :label="t('loadingVoices')" />
      <p v-else-if="s.catalogError.value" class="tellfair__hint" role="status">{{ t('catalogFailed') }}</p>
      <p v-else-if="s.selectedPreset.value?.hint" class="tellfair__hint">{{ s.selectedPreset.value.hint }}</p>
    </div>
    <div class="tellfair__field"><label for="tf-voice">{{ t('voice') }}</label><UISelect input-id="tf-voice" :model-value="s.doc.customVoiceId ? s.voiceOptions.value.some(item => item.value === 'custom:' + s.doc.customVoiceId) ? 'custom:' + s.doc.customVoiceId : '__custom' : s.doc.voiceId ?? ''" :options="s.voiceOptions.value" searchable :placeholder="t('presetVoice')" :disabled="!s.canEdit.value" @update:model-value="voice(String($event ?? ''))" /></div>
    <p v-if="s.doc.voiceId?.startsWith('chat:')" class="tellfair__hint">{{ t('piperHint') }}</p>
    <div class="tellfair__property-row"><label for="tf-format">{{ t('format') }}</label><UISelect input-id="tf-format" v-model="s.doc.format" :options="s.formatOptions" :disabled="!s.canEdit.value" /></div>
    <UIButton class="tellfair__disclosure" variant="quiet" :aria-expanded="advanced" aria-controls="tf-advanced" @click="advanced = !advanced"><UIIcon :name="advanced ? 'chevron-down' : 'chevron-right'" :size="12" /><span>{{ t('advanced') }}</span><span v-if="modified" class="tellfair__hint">{{ modified }}</span></UIButton>
    <div v-show="advanced" id="tf-advanced" class="tellfair__advanced">
      <div class="tellfair__field"><label for="tf-custom">{{ t('customVoice') }} <UIInfoTip :text="t('customVoiceHelp')" :label="t('customVoice')" /></label><UIInput id="tf-custom" :model-value="s.doc.customVoiceId ?? ''" :disabled="!s.canEdit.value" :maxlength="200" @update:model-value="custom" /></div>
      <div class="tellfair__properties-title"><strong>{{ t('params') }}</strong><UIButton variant="quiet" :aria-label="t('resetParams')" v-tippy="t('resetParams')" :disabled="!s.canEdit.value || !modified && s.fields.valid.value" @click="s.fields.reset"><UIIcon name="arrow-counterclockwise" /></UIButton></div>
      <div v-for="field in speechParameters" :key="field.key" class="tellfair__parameter">
        <div class="tellfair__property-row"><label :for="'tf-' + field.key">{{ t(field.label) }}</label><UIInput :id="'tf-' + field.key" variant="property" inputmode="decimal" :model-value="s.fields.drafts[field.key] ?? ''" :error-message="fieldError(field.key)" :help-text="t(field.label + 'Help')" :placeholder="String(s.selectedPreset.value?.params?.[field.key] ?? field.fallback)" :disabled="!s.canEdit.value" @update:model-value="s.fields.update(field.key, $event)" /></div>
        <UISlider :id="'tf-' + field.key + '-slider'" :aria-label="t(field.label)" :min="field.min" :max="field.max" :step="0.01" :model-value="sliderValue(field)" :disabled="!s.canEdit.value" @update:model-value="s.fields.update(field.key, String($event))" />
      </div>
      <div class="tellfair__property-row"><label for="tf-seed">{{ t('seed') }}</label><UIInput id="tf-seed" variant="property" inputmode="numeric" :model-value="s.fields.drafts.seed ?? ''" :error-message="fieldError('seed')" :help-text="t('seedHelp')" :placeholder="t('randomSeed')" :disabled="!s.canEdit.value" @update:model-value="s.fields.update('seed', $event)" /></div>
    </div>
  </aside>
</template>
