import { useStore } from '../store/useStore.js';
import { t } from '../lib/i18n.js';

export default function Toasts() {
  const toasts = useStore((s) => s.toasts);
  const dismissToast = useStore((s) => s.dismissToast);

  if (!toasts.length) return null;

  return (
    <div className="fixed bottom-3 left-3 right-3 z-50 flex flex-col gap-2">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`rounded-md border px-3 py-2 text-xs shadow-lg flex items-start gap-2 ${
            toast.type === 'error'
              ? 'bg-danger-bg border-danger-stroke text-fg1'
              : 'bg-success-bg border-success-stroke text-fg1'
          }`}
        >
          <span className="flex-1">{toast.message}</span>
          {toast.action && (
            <button
              onClick={() => {
                dismissToast(toast.id);
                toast.action.run();
              }}
              className="font-semibold underline shrink-0"
            >
              {toast.action.label}
            </button>
          )}
          <button
            onClick={() => dismissToast(toast.id)}
            className="shrink-0 opacity-70 hover:opacity-100"
            title={t('titleDismiss')}
            aria-label={t('titleDismiss')}
          >
            <span aria-hidden="true">✕</span>
          </button>
        </div>
      ))}
    </div>
  );
}
