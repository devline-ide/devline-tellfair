export interface TellfairContext {
  locale: string
  theme: string
  visible: boolean
  view?: { id: string; location: 'dock' | 'sidebar' | 'settings' }
  document?: { path: string }
}
export interface TellfairSDK {
  ready: Promise<TellfairContext>
  getContext(): TellfairContext
  getState(): unknown
  setState(value: unknown): void
  onContextChanged(callback: (context: TellfairContext) => void): () => void
  onBeforeDocumentSave(callback: () => Promise<void>): () => void
  call<T = unknown>(method: string, arguments_?: Record<string, unknown>): Promise<T>
}
declare global { interface Window { devline: TellfairSDK } }
export const sdk = window.devline
