import { sdk } from './sdk'
/** Follow lazy backend reads only while the initiating view is still visible. */
export async function accountRead<T>(method: string, args: Record<string, unknown> = {}, alive: () => boolean = () => sdk.getContext().visible): Promise<T> {
  const until = Date.now() + 18000
  while (alive()) {
    const result = await sdk.call<T & { state?: string }>(method, args)
    if (!alive()) throw Error('TELLFAIR_CANCELLED')
    if (result.state !== 'loading') return result
    if (Date.now() > until) throw Error('TELLFAIR_NETWORK')
    await new Promise(resolve => setTimeout(resolve, 150))
  }
  throw Error('TELLFAIR_CANCELLED')
}
