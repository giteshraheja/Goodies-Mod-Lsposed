# Security policy

This module runs inside SystemUI with root-framework privileges, so reports are taken seriously.
Please report vulnerabilities privately via GitHub "Report a vulnerability" (Security tab) rather
than a public issue. Control of the in-SystemUI hook is limited to apps signed with the same key
(signature-level permission `dev.gitesh.sbmods.permission.CONTROL`).
Never commit keystores or passwords; builds read the password from `SBMODS_KS_PASS` or `pass.txt` in the key folder (`$SBMODS_KEYS`, default `~/.sbmods-keys`, outside the project).
