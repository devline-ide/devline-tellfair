<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { UIButton, UIIcon, UIInput, UIInfoTip } from '@devline/ui-web/controls'
import { sdk } from './sdk'

const props = defineProps<{ configured: boolean; startOpen?: boolean }>()
const emit = defineEmits<{ configured: [value: boolean]; saved: [] }>()
const { t } = useI18n(), editing = ref(props.startOpen || !props.configured), key = ref(''), busy = ref(false), error = ref('')
const input = ref<InstanceType<typeof UIInput>>()
const keyError = computed(() => key.value.trim() && !/^tf_live_[A-Za-z0-9_-]{8,480}$/.test(key.value.trim()) ? t('keyInvalid') : '')
async function edit() { editing.value = true; await nextTick(); input.value?.focus() }
async function save(remove = false) {
  if (busy.value || !remove && (!key.value.trim() || keyError.value)) return
  busy.value = true; error.value = ''
  try {
    const result = await sdk.call<{ configured: boolean }>('settings', { key: remove ? '' : key.value.trim() })
    key.value = ''; editing.value = !result.configured; emit('configured', result.configured); emit('saved')
  } catch { error.value = t('keySaveFailed') }
  finally { busy.value = false }
}
function cancel() { key.value = ''; error.value = ''; editing.value = false }
</script>

<template>
  <section class="tellfair-key" :aria-label="t('key')">
    <div class="tellfair-key__status"><UIIcon :name="configured ? 'check-circle' : 'key'" :class="{ 'tellfair-key__saved': configured }" /><span>{{ configured ? t('configuredShort') : t('missingKey') }}</span><UIInfoTip :text="t('keyHelp')" :label="t('key')" /><UIButton v-if="configured && !editing" variant="quiet" @click="edit">{{ t('changeKey') }}</UIButton></div>
    <div v-if="editing" class="tellfair-key__form">
      <label for="tf-key">{{ t('key') }}</label>
      <UIInput id="tf-key" ref="input" v-model="key" type="password" autocomplete="off" placeholder="tf_live_…" :error-message="keyError || undefined" :disabled="busy" :maxlength="512" @keydown.enter.prevent="save()" />
      <div class="tellfair__actions"><UIButton variant="primary" @click="save()" :disabled="!key.trim() || !!keyError" :busy="busy">{{ t('saveKey') }}</UIButton><UIButton v-if="configured" variant="quiet" :disabled="busy" @click="cancel">{{ t('cancel') }}</UIButton><UIButton v-if="configured" variant="quiet" :disabled="busy" @click="save(true)">{{ t('removeKey') }}</UIButton></div>
    </div>
    <p v-if="error" class="tellfair-key__error" role="alert">{{ error }}</p>
  </section>
</template>
