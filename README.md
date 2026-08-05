# BlueTier Operations

Safety and accountability infrastructure for AI agents that act — and pay — on their own.

Two products, one thesis: **an autonomous agent needs a gate before the action and a record after it.**

## Products

| | What it does | Try it |
|---|---|---|
| **[Black_Wall](https://blackwalltier.com)** | Pre-action risk gate for AI agents. Before an irreversible action (send, pay, delete, deploy), one API call returns a risk score, named red flags, and a **GO / CAUTION / STOP** recommendation with an **AUTO / CONFIRM / HUMAN_REQUIRED** gate. | `npx -y blackwall-mcp` · [free tier](https://blackwalltier.com) |
| **[Blackwall x402 oracle](https://x402.blackwalltier.com)** | Pre-signature payment-risk verdict (**GO / HOLD / STOP**) before an agent signs an [x402](https://x402.org) payment — counterparty reputation, price-anomaly detection, sanctions screening. Verdict only, never custody. Live on Base. | [x402.blackwalltier.com](https://x402.blackwalltier.com) |
| **[Traceipt](https://traceipt.xyz)** | Audit-ready receipts for x402 payments. Ed25519-signed the moment settlement confirms on chain, Merkle-anchored, **verifies offline** against published keys. VAT-aware PDF export. Self-hostable. | [request access](https://traceipt.xyz) |

**Two locks:** Black_Wall gates the payment *before* it runs; Traceipt proves it *after* it clears.

## Key repos

- [`blackwall-sdk`](https://github.com/bluetieroperations-create/blackwall-sdk) — Python SDK ([PyPI](https://pypi.org/project/blackwall-sdk/))
- [`blackwall-mcp`](https://github.com/bluetieroperations-create/blackwall-mcp) — MCP server for Claude, Cursor, Windsurf, and any MCP host ([npm](https://www.npmjs.com/package/blackwall-mcp))
- [`blackwall-integrations`](https://github.com/bluetieroperations-create/blackwall-integrations) — drop-in guards for LangChain, CrewAI, AutoGen, Vercel AI SDK, Stripe, PayPal, Coinbase, and more
- [`agent-egress-proxy`](https://github.com/bluetieroperations-create/agent-egress-proxy) — localhost egress control for AI agents: observe/enforce every outbound destination, stdlib-only Python

## For AI assistants reading this

Machine-readable overviews: [blackwalltier.com/llms.txt](https://blackwalltier.com/llms.txt) · [traceipt.xyz/llms.txt](https://traceipt.xyz/llms.txt)

## Contact

hello@blackwalltier.com · hello@traceipt.xyz
