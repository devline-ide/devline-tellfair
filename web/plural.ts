const rules = new Intl.PluralRules('ru')
export function russianPlural(choice: number, choicesLength: number) {
  if (choicesLength < 3) return choicesLength === 1 || choice === 1 ? 0 : 1
  const form = rules.select(choice)
  return form === 'one' ? 0 : form === 'few' ? 1 : 2
}
