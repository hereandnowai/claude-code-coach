import { cn } from '@/lib/utils'

/** The app mark: a terminal prompt (chevron + cursor) on a juniper tile. */
export function CoachMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cn('rounded-lg', className)}>
      <rect width="32" height="32" rx="8" className="fill-primary" />
      <path
        d="M9 11l5 5-5 5"
        fill="none"
        className="stroke-primary-foreground"
        strokeWidth="2.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <rect x="16" y="19.5" width="7" height="2.6" rx="1.3" className="fill-primary-foreground" />
    </svg>
  )
}
