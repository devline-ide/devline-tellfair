import type { DevLineWebviewContext, DevLineWebviewSdk } from '@devline/extension-devkit'
export type TellfairContext = DevLineWebviewContext
export type TellfairSDK = DevLineWebviewSdk<TellfairContext>

declare global { interface Window { devline: TellfairSDK } }
export const sdk = window.devline
