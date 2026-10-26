<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { UIButton, UIIcon, UIInfoTip } from '@devline/ui-web'
import { sdk } from './sdk'
import { accountRead } from './account-api'
const { t, locale } = useI18n(), error = ref(''), busy = ref(false), configured = ref(false)
const balance = ref<{ plan?: { name: string }; chars?: { total: number; plan: number; pack: number } }>()
const usage = ref<{ delta: number; reason: string; at: number }[]>([])
let disposed = false
async function open(method: 'cabinet' | 'host.openSettings') { error.value = ''; try { await sdk.call(method) } catch { error.value = t('failure') } }
async function load() {
  if (busy.value || !sdk.getContext().visible) return
  busy.value = true; error.value = ''
  try {
    configured.value = (await sdk.call<{ configured: boolean }>('settings')).configured
    if (configured.value) {
      const alive = () => !disposed && sdk.getContext().visible
      balance.value = await accountRead('balance', {}, alive)
      usage.value = (await accountRead<{ items: typeof usage.value }>('usage', { limit: 10 }, alive)).items
    } else { balance.value = undefined; usage.value = [] }
  } catch (cause) { if (!disposed && sdk.getContext().visible) error.value = cause instanceof Error ? t(cause.message) : t('failure') }
  finally { busy.value = false }
}
const unsubscribe = sdk.onContextChanged(context => { locale.value = context.locale.startsWith('ru') ? 'ru' : 'en'; if (context.visible) void load() })
onUnmounted(() => { disposed = true; unsubscribe() })
void load()
</script>
<template>
  <section class="tellfair tellfair--account">
    <div class="tellfair__account-link"><UIButton variant="primary" @click="open('cabinet')"><UIIcon name="box-arrow-up-right" />{{ t('cabinet') }}</UIButton><UIInfoTip :text="t('embed')" :label="t('accountTitle')" /></div>
    <p class="tellfair__hint">{{ t('accountHint') }}</p>
    <div v-if="balance?.chars" class="tellfair__balance is-selectable"><strong>{{ t('balanceChars', { count: balance.chars.total }, balance.chars.total) }}</strong><span>{{ balance.plan?.name }}</span><small>{{ t('balanceParts', { plan: balance.chars.plan, pack: balance.chars.pack }) }}</small></div>
    <p v-if="!configured && !busy" class="tellfair__hint">{{ t('missingKey') }}</p>
    <details v-if="usage.length" class="tellfair__usage"><summary>{{ t('usageTitle') }}</summary><div v-for="(item, index) in usage" :key="index" class="is-selectable"><time>{{ new Date(item.at).toLocaleDateString(locale) }}</time><span>{{ item.reason }}</span><strong>{{ item.delta > 0 ? '+' : '' }}{{ item.delta }}</strong></div></details>
    <div class="tellfair__actions"><UIButton variant="quiet" @click="open('host.openSettings')"><UIIcon name="gear" />{{ t('openSettings') }}</UIButton><UIButton variant="quiet" :busy="busy" @click="load"><UIIcon name="arrow-clockwise" />{{ t('retry') }}</UIButton></div>
    <p v-if="error" role="alert" class="tellfair-key__error is-selectable">{{ error }}</p>
  </section>
</template>
