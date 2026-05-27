# Security Policy

## Reporting a Vulnerability

If you believe you have found a security vulnerability, please **do not**
open a public issue. Instead, contact the maintainer privately by either:

1. Opening a **private** [security advisory](../../security/advisories/new)
   on GitHub (preferred), or
2. Emailing the maintainer through their GitHub profile contact.

You can expect:

- An acknowledgment within 7 days
- An initial assessment within 14 days
- A fix or detailed mitigation plan within 30 days for high/critical
  severity issues

## Supported Versions

The latest tagged release is supported. Older versions receive only
documented security backports if explicitly listed in a release note.

## Scope

In-scope:
- The code in this repository
- The default-mode runtime behavior

Out of scope:
- Third-party dependencies (please report upstream)
- Issues that require a malicious local user with shell access
- Social-engineering attacks on the user
