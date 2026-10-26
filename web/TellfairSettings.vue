<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { UISkeleton, UIButton, UIIcon, UIInfoTip } from '@devline/ui-web'
import { sdk } from './sdk'
import TellfairKeySettings from './TellfairKeySettings.vue'
import TellfairMiniSettings from './TellfairMiniSettings.vue'

const { t, locale } = useI18n(), configured = ref(false), miniConfigured = ref(false), ready = ref(false), error = ref('')
let disposed = false
async function load() {
  error.value = ''
  try { const result = await sdk.call<{ configured: boolean; miniConfigured: boolean }>('settings'); if (!disposed) { configured.value = result.configured; miniConfigured.value = result.miniConfigured; ready.value = true } }
  catch { if (!disposed) error.value = t('settingsLoadFailed') }
}
async function cabinet() { try { await sdk.call('cabinet') } catch { error.value = t('failure') } }
const unsubscribe = sdk.onContextChanged(context => { locale.value = context.locale.startsWith('ru') ? 'ru' : 'en'; if (context.visible) void load() })
onUnmounted(() => { disposed = true; unsubscribe() })
void load()
</script>

<template>
  <section class="tellfair tellfair--settings">
    <header class="tellfair__settings-heading"><h2>{{ t('settingsTitle') }}</h2><p class="tellfair__hint">{{ t('settingsDescription') }}</p></header>
    <TellfairKeySettings v-if="ready" :configured="configured" @configured="configured = $event" />
    <TellfairMiniSettings v-if="ready" :configured="miniConfigured" @configured="miniConfigured = $event" />
    <UISkeleton v-else-if="!error" variant="settings" :label="t('loading')" />
    <div class="tellfair__account-link"><UIButton variant="quiet" @click="cabinet"><UIIcon name="box-arrow-up-right" />{{ t('cabinetShort') }}</UIButton><UIInfoTip :text="t('keyHelp')" :label="t('key')" /></div>
    <div v-if="error" class="tellfair-key__error" role="alert">{{ error }} <UIButton variant="quiet" @click="load">{{ t('retry') }}</UIButton></div>
  </section>
</template>
