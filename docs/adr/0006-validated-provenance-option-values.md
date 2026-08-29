# ADR 0006 — Validate present provenance option values at construction

**Status:** Accepted architecture (chief approval 2026-08-29); implementation under review

**Date:** 2026-08-29

**Decider:** `claude-storymodel4s`

**Author:** `codex-storymodel-collab`

**Independent reviewer:** `codex-storymodel-new-engineer`

## Context

`ProviderCall.promptTemplateVersion` and `CriticFinding.note` were raw `Option[String]` fields.
Both admitted `Some("")` and whitespace-only variants even though those values carry no information
beyond `None`. The surface-to-narrative compiler needed an injective option renderer to keep those
structurally distinct admitted values from colliding in content fingerprints.

That renderer is correct and remains useful, but it protects only one consumer. A future receipt,
codec, or fingerprint renderer could repeat the ambiguity because the meaningless states remained
constructible through generated case-class `apply`, `copy`, and `fromProduct` methods.

The two strings also have different meanings. A prompt-template version identifies a provider
artifact; a finding note is explanatory critic text. A generic nonblank string would permit those
semantically unrelated values to be exchanged accidentally.

## Decision

Introduce two semantic opaque types:

- `core.PromptTemplateVersion` for the present branch of
  `ProviderCall.promptTemplateVersion`;
- `acquire.FindingNote` for the present branch of `CriticFinding.note`.

Each companion exposes:

- `from(String): Either[DomainError, A]`, which rejects empty and whitespace-only input;
- a validating `unsafe(String)` helper for literals and fixtures, which throws on the same invalid
  inputs rather than bypassing validation;
- a public `value: String` observation.

Accepted strings are preserved byte-for-byte. Validation uses the portable character
`isWhitespace` predicate only to decide whether every character is whitespace; it does not store a
trimmed form. This avoids both host-specific `String.trim` behavior and a second, hidden
canonicalization, while preserving provider-supplied identities and explanatory text exactly.

`ProviderCall` and `CriticFinding` become private-constructor non-case classes. Scala case-class
`fromProduct` accepts an untyped `Product`, so an opaque field alone does not close that generated
door. Explicit `apply` and `copy` operations retain construction and update ergonomics while
requiring the semantic opaque types; no `fromProduct` operation exists. Both classes implement
structural equality and hashing over the same fields as before.

The ProviderCall JSON representation remains a nullable or absent string. Encoding unwraps an
admitted value. Decoding validates the string and rejects blank legacy payloads. Existing
injective option framing in receipt and narrative fingerprints remains as defense in depth; tests
continue to prove that absent and present admitted values affect fingerprints distinctly.

## Consequences

- Source callers constructing a present prompt-template version or finding note must validate it
  explicitly.
- `None` call sites and wire payloads are unchanged.
- The public field types become more precise and are source-incompatible for callers that passed
  `Some(String)` directly.
- No `NarrativeCompilation`, provider receipt, or critic finding can retain an empty present value.
- Distinct nonblank values retain their exact bytes and remain distinct in codecs and fingerprints.

## Evidence

External-package compile-time probes demonstrate that raw-string `apply`, `copy`, and
`fromProduct` construction fails for both containing types. Runtime laws reject empty and
whitespace-only inputs, prove admitted byte preservation, and prove the unsafe helpers validate.
Codec evidence rejects blank legacy JSON. Receipt and narrative tests retain present-versus-absent
fingerprint discrimination for admitted values.
