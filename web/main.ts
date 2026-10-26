import { createApp } from 'vue'
import { installUI } from '@devline/ui-web'
import { createI18n } from 'vue-i18n'
import SpeechEditor from './TellfairPanel.vue'
import Settings from './TellfairSettings.vue'
import Cabinet from './TellfairCabinet.vue'
import en from './en.json'
import ru from './ru.json'
import { sdk } from './sdk'
import { russianPlural } from './plural'
import '@devline/ui-web/style.css'
import './tellfair.scss'

void sdk.ready.then(() => {
  const context = sdk.getContext()
  const app = createApp(context.view?.location === 'settings' ? Settings : context.document ? SpeechEditor : Cabinet)
  installUI(app)
  app.use(createI18n({ legacy: false, locale: sdk.getContext().locale.startsWith('ru') ? 'ru' : 'en', fallbackLocale: 'en', pluralRules: { ru: russianPlural }, messages: { en, ru } }))
  app.mount('#app')
})
