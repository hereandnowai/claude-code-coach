import { MoreHorizontal, PanelLeftClose, Pencil, Plus, Trash2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useHealth } from '@/hooks/useHealth'
import type { ConversationSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

import { CoachMark } from '../brand/CoachMark'

interface SidebarProps {
  open: boolean
  conversations: ConversationSummary[]
  activeId: string | null
  onClose: () => void
  onNewChat: () => void
  onOpen: (id: string) => void
  onRename: (id: string, title: string) => Promise<void>
  onDelete: (id: string) => Promise<void>
}

function groupByDate(items: ConversationSummary[]) {
  const startOfToday = new Date()
  startOfToday.setHours(0, 0, 0, 0)
  const weekAgo = startOfToday.getTime() - 6 * 86_400_000
  const groups: { label: string; items: ConversationSummary[] }[] = [
    { label: 'Today', items: [] },
    { label: 'Previous 7 days', items: [] },
    { label: 'Older', items: [] },
  ]
  for (const c of items) {
    const t = new Date(c.updatedAt).getTime()
    const bucket = t >= startOfToday.getTime() ? 0 : t >= weekAgo ? 1 : 2
    groups[bucket]?.items.push(c)
  }
  return groups.filter((g) => g.items.length > 0)
}

function ConversationRow({
  conversation,
  active,
  onOpen,
  onRename,
  onAskDelete,
}: {
  conversation: ConversationSummary
  active: boolean
  onOpen: () => void
  onRename: (title: string) => Promise<void>
  onAskDelete: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(conversation.title)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (editing) inputRef.current?.select()
  }, [editing])

  const commit = async () => {
    const title = draft.trim()
    setEditing(false)
    if (title && title !== conversation.title) await onRename(title)
    else setDraft(conversation.title)
  }

  if (editing) {
    return (
      <form
        onSubmit={(e) => {
          e.preventDefault()
          void commit()
        }}
        className="px-1"
      >
        <label htmlFor={`rename-${conversation.id}`} className="sr-only">
          Chat title
        </label>
        <input
          id={`rename-${conversation.id}`}
          ref={inputRef}
          value={draft}
          maxLength={200}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={() => void commit()}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              setDraft(conversation.title)
              setEditing(false)
            }
          }}
          className="w-full rounded-lg border border-primary/60 bg-card px-2.5 py-1.5 text-sm ring-4 ring-primary/15 outline-none"
        />
      </form>
    )
  }

  return (
    <div
      className={cn(
        'group/row relative flex items-center rounded-lg transition-colors',
        active ? 'bg-sidebar-accent' : 'hover:bg-sidebar-accent/60',
      )}
    >
      <button
        type="button"
        onClick={onOpen}
        aria-current={active ? 'page' : undefined}
        className="min-w-0 flex-1 truncate rounded-lg py-2 pr-9 pl-3 text-left text-sm"
        title={conversation.title}
      >
        {conversation.title}
      </button>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon-xs"
            aria-label={`Options for ${conversation.title}`}
            className={cn(
              'absolute right-1.5 opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100 aria-expanded:opacity-100',
              active && 'opacity-100',
            )}
          >
            <MoreHorizontal aria-hidden />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="min-w-36">
          <DropdownMenuItem onSelect={() => setEditing(true)}>
            <Pencil aria-hidden />
            Rename
          </DropdownMenuItem>
          <DropdownMenuItem variant="destructive" onSelect={onAskDelete}>
            <Trash2 aria-hidden />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

function StatusFooter() {
  const health = useHealth()
  const model = health?.components?.model?.details?.name
  const index = health?.components?.index?.details
  const ready = index?.state === 'READY' || index?.state === 'REFRESHING'
  const status = !health
    ? 'Checking the server…'
    : ready
      ? `${index?.documents ?? 0} doc pages indexed`
      : index?.state === 'INDEXING'
        ? 'Indexing the docs…'
        : 'Docs index unavailable'

  return (
    <div className="border-t border-sidebar-border px-4 py-3 text-xs text-muted-foreground">
      <p className="flex items-center gap-2">
        <span
          aria-hidden
          className={cn(
            'size-1.5 rounded-full',
            ready ? 'bg-primary' : health ? 'bg-amber-500' : 'bg-muted-foreground/50',
          )}
        />
        {status}
      </p>
      {model && <p className="mt-1 truncate font-mono text-[0.7rem]">{model}</p>}
    </div>
  )
}

export function Sidebar({
  open,
  conversations,
  activeId,
  onClose,
  onNewChat,
  onOpen,
  onRename,
  onDelete,
}: SidebarProps) {
  const [pendingDelete, setPendingDelete] = useState<ConversationSummary | null>(null)
  const groups = groupByDate(conversations)

  return (
    <>
      {/* mobile scrim */}
      <div
        aria-hidden
        onClick={onClose}
        className={cn(
          'fixed inset-0 z-30 bg-black/40 backdrop-blur-[2px] transition-opacity md:hidden',
          open ? 'opacity-100' : 'pointer-events-none opacity-0',
        )}
      />
      <aside
        id="sidebar"
        aria-label="Chats"
        className={cn(
          'fixed inset-y-0 left-0 z-40 flex w-[272px] flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground transition-transform duration-200 ease-out md:static md:z-auto',
          open ? 'translate-x-0' : '-translate-x-full md:hidden',
        )}
      >
        <div className="flex h-14 items-center gap-2.5 px-4">
          <CoachMark className="size-6" />
          <span className="font-mono text-sm font-medium tracking-tight">claude-code-coach</span>
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={onClose}
            aria-label="Close sidebar"
            className="ml-auto"
          >
            <PanelLeftClose aria-hidden />
          </Button>
        </div>
        <div className="px-3 pb-2">
          <Button
            onClick={onNewChat}
            variant="outline"
            className="w-full justify-start gap-2 bg-card shadow-none"
          >
            <Plus aria-hidden />
            New chat
          </Button>
        </div>
        <nav aria-label="Chat history" className="min-h-0 flex-1 overflow-y-auto px-2 pb-4">
          {groups.length === 0 ? (
            <p className="px-3 pt-4 text-sm text-muted-foreground">Your chats will show up here.</p>
          ) : (
            groups.map((g) => (
              <section key={g.label} className="mt-4 first:mt-2">
                <h2 className="px-3 pb-1 text-xs font-medium text-muted-foreground">{g.label}</h2>
                <ul className="space-y-0.5">
                  {g.items.map((c) => (
                    <li key={c.id}>
                      <ConversationRow
                        conversation={c}
                        active={c.id === activeId}
                        onOpen={() => onOpen(c.id)}
                        onRename={(title) => onRename(c.id, title)}
                        onAskDelete={() => setPendingDelete(c)}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            ))
          )}
        </nav>
        <StatusFooter />
      </aside>

      <Dialog open={pendingDelete !== null} onOpenChange={(o) => !o && setPendingDelete(null)}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Delete this chat?</DialogTitle>
            <DialogDescription>
              “{pendingDelete?.title}” and its feedback will be removed. This can't be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">Cancel</Button>
            </DialogClose>
            <Button
              variant="destructive"
              onClick={() => {
                if (pendingDelete) void onDelete(pendingDelete.id)
                setPendingDelete(null)
              }}
            >
              Delete chat
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
