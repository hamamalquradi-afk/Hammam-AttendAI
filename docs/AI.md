# AI
The assistant is read-only by default. Local intent parsing supports common attendance questions without Internet. Cloud AI is an optional `AiProvider` adapter and must receive grounded facts from safe query tools; it is not allowed to issue arbitrary SQL.

Any send request becomes a draft/confirmation flow. If cloud AI is unavailable, attendance and normal reports continue working.
