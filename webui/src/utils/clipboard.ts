export async function copyText(text: string): Promise<void> {
  if (navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const input = document.createElement('textarea');
  input.value = text;
  input.style.cssText = 'position:fixed;top:0;left:-9999px;';
  document.body.appendChild(input);
  const active = document.activeElement as HTMLElement | null;
  try {
    input.select();
    if (!document.execCommand('copy')) throw new Error('Clipboard unavailable');
  } finally {
    input.remove();
    active?.focus();
  }
}
