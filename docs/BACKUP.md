# Backup
Backup export is local and encrypted. The backup format has a magic header, database version, random salt, random GCM IV, SHA-256 digest and encrypted database bytes.

Restore validates the format and integrity into staging before replacement. Settings may be included by future format versions, but cloud secrets and provider tokens must never be exported.
