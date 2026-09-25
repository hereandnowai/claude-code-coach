> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt

# Connect Claude Code to tools via MCP

> Learn how to connect Claude Code to your tools with the Model Context Protocol.

Claude Code can connect to external tools and data sources through MCP servers.

## Add an MCP server

<Steps>
  <Step title="Add a remote HTTP server">
    ```bash
    claude mcp add --transport http github https://api.githubcopilot.com/mcp/
    ```
  </Step>
  <Step title="Add a local stdio server">
    ```bash
    claude mcp add --transport stdio db -- npx -y @bytebase/dbhub
    ```
  </Step>
</Steps>

<Warning>
  Verify you trust each server before connecting it.
</Warning>

## MCP installation scopes

Use `--scope project` to share a server with your team through a `.mcp.json` file.
