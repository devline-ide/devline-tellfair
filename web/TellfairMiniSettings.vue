<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { UIButton, UIInput, UIIcon, UIInfoTip } from '@devline/ui-web/controls'
import { sdk } from './sdk'
const props = defineProps<{ configured: boolean }>(), emit = defineEmits<{ configured: [value: boolean] }>()
const { t } = useI18n(), url = ref(''), editing = ref(false), busy = ref(false), error = ref('')
const input = ref<InstanceType<typeof UIInput>>()
async function edit() { url.value = ''; editing.value = true; await nextTick(); input.value?.focus() }
async function save(remove = false) {
  if (busy.value || !remove && !url.value.trim()) return
  busy.value = true; error.value = ''
  try { const result = await sdk.call<{ miniConfigured: boolean }>('settings', { miniCabinetUrl: remove ? '' : url.value.trim() }); url.value = ''; editing.value = false; emit('configured', result.miniConfigured) }
  catch { error.value = t('miniSaveFailed') }
  finally { busy.value = false }
}
</script>
<template>
  <section class="tellfair-key" :aria-label="t('miniCabinet')">
    <div class="tellfair-key__status"><UIIcon :name="configured ? 'check-circle' : 'window-sidebar'" :class="{ 'tellfair-key__saved': configured }" /><span>{{ configured ? t('miniSaved') : t('miniCabinet') }}</span><UIInfoTip :text="t('miniHelp')" :label="t('miniCabinet')" /><UIButton v-if="!editing" variant="quiet" @click="edit">{{ configured ? t('changeKey') : t('miniConnect') }}</UIButton></div>
    <form v-if="editing" class="tellfair-key__form" @submit.prevent="save()"><label for="tf-mini">{{ t('miniUrl') }}</label><UIInput id="tf-mini" ref="input" v-model="url" type="password" autocomplete="off" :disabled="busy" :maxlength="2048" :placeholder="t('miniPaste')" @keydown.enter.prevent="save()" /><div class="tellfair__actions"><UIButton variant="primary" :disabled="!url.trim()" :busy="busy" @click="save()">{{ t('miniSave') }}</UIButton><UIButton variant="quiet" :disabled="busy" @click="editing = false; url = ''; error = ''">{{ t('cancel') }}</UIButton><UIButton v-if="configured" variant="quiet" :disabled="busy" @click="save(true)">{{ t('miniRemove') }}</UIButton></div></form>
    <p v-if="error" role="alert" class="tellfair-key__error">{{ error }}</p>
  </section>
</template>
