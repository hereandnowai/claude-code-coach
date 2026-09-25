import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { THEME_STORAGE_KEY, ThemeProvider } from '@/lib/theme'
import { media } from '@/test/setup'

import { ThemeToggle } from './ThemeToggle'

function renderToggle() {
  return render(
    <ThemeProvider>
      <ThemeToggle />
    </ThemeProvider>,
  )
}

async function choose(label: string) {
  await userEvent.click(screen.getByRole('button', { name: /change theme/i }))
  await userEvent.click(await screen.findByRole('menuitemradio', { name: label }))
}

describe('ThemeToggle', () => {
  it('defaults to system and follows the OS preference', () => {
    media.prefersDark = true
    renderToggle()
    expect(screen.getByRole('button', { name: /theme: system/i })).toBeInTheDocument()
    expect(document.documentElement).toHaveClass('dark')
    media.prefersDark = false
  })

  it('switches to dark and persists the choice', async () => {
    renderToggle()
    await choose('Dark')
    expect(document.documentElement).toHaveClass('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
  })

  it('switches to light and back to system', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    renderToggle()
    expect(document.documentElement).toHaveClass('dark')
    await choose('Light')
    expect(document.documentElement).not.toHaveClass('dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
    await choose('System')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('system')
    expect(document.documentElement).not.toHaveClass('dark')
  })
})
