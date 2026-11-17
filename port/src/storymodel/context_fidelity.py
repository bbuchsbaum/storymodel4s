"""Checked context/identity evidence for the explicit contextual fidelity profile."""

from typing import Any, Self

from pydantic import field_validator, model_validator

from .held_identity import resolve_attributed_identity
from .model import (
    Detail,
    Hypothesis,
    Model,
    Name,
    State,
    Support,
    _has_holder,
    contained_in,
    text_evidence,
)
from .rich_account import SupportedAccount


class ContextEvidence(Model):
    """Exact recorded interpretations are recomputed, never trusted as derived identity results."""

    identity_annotations: Name
    holder_proposals: Name


class ContextAccounting(Model):
    """The assessor declares the context and identity dependencies of one conditional judgment."""

    considered_contexts: tuple[Name, ...]
    required_holders: tuple[Name, ...] = ()
    required_mentions: tuple[Name, ...] = ()
    reason: Name

    @field_validator("considered_contexts", "required_holders", "required_mentions")
    @classmethod
    def unique(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        if len(values) != len(set(values)):
            raise ValueError("duplicate context accounting alias")
        return tuple(sorted(values))

    @model_validator(mode="after")
    def holders_considered(self) -> Self:
        if not set(self.required_holders) <= set(self.considered_contexts):
            raise ValueError("required holders must belong to considered contexts")
        return self


def context_questions(
    account: SupportedAccount,
    questions: tuple[tuple[str, Detail, Hypothesis], ...],
    recordings: ContextEvidence,
) -> dict[str, dict[str, Any]]:
    """Render bounded contextual evidence with local aliases and excerpt-local coordinates.

    Only explicitly linked recall situations, their ancestors, roles and holder candidates
    enter. Identity is computed from the full account, but unrelated referent members and
    annotation explanations are withheld. Missing placement never becomes a root-world fact.
    """
    identity = resolve_attributed_identity(
        account, recordings.identity_annotations, recordings.holder_proposals
    )
    contexts = {c.id: c for c in account.contexts}
    holders = {h.context: h for h in identity.attribution.holders}
    decisions = {m.mention: m for m in identity.mentions}
    referents = {r.id: r for r in identity.referents}
    base_to_final = {r.base_referent: r.id for r in identity.referents}
    situations = {s.id: s for s in account._situations()}
    units = {u.id: u for u in account.units}
    entities = {e.id: e for e in account.entities}
    documents = {d.id: d for d in account.documents}

    def excerpts(supports: tuple[Support, ...]) -> list[dict[str, Any]]:
        # Each excerpt starts at zero. No recall document ID, global offset or free-text
        # annotation explanation is emitted; original coordinates stay in the private input.
        return [
            {
                "side": documents[support.document].side,
                "span": {"start": 0, "end": span.end - span.start},
                "text": span.slice(documents[support.document].text),
            }
            for support in supports
            for span in support.spans
        ]

    result: dict[str, dict[str, Any]] = {}
    for key, detail, hypothesis in questions:
        target_support = tuple(units[t].support for t in hypothesis.targets)
        source = tuple(
            s
            for s in situations.values()
            if s.side == "source" and all(contained_in(e, target_support) for e in s.evidence)
        )
        recall = () if detail.event is None else (situations[detail.event],)
        selected = (*source, *recall)
        visible_contexts: set[str] = set()
        for situation in selected:
            current: str | None = situation.context
            while current is not None:
                visible_contexts.add(current)
                current = contexts[current].parent
        context_alias = {c: f"c{i}" for i, c in enumerate(sorted(visible_contexts))}
        visible_mentions = {p.entity for s in selected for p in s.participants}
        visible_mentions.update(
            candidate.mention for c in visible_contexts for candidate in holders[c].candidates
        )
        # Text holder admission already enforces same-document candidates. Verify the
        # recalled closure cannot reach another recall document through future producers.
        recall_document = detail.support.document
        if any(
            documents[entities[m].origin_document].side == "recall"
            and entities[m].origin_document != recall_document
            for m in visible_mentions
        ):
            raise ValueError("contextual recall evidence crosses recall documents")
        for c in visible_contexts:
            if contexts[c].side == "recall" and any(
                e.document != recall_document for e in text_evidence(contexts[c].evidence)
            ):
                raise ValueError("contextual recall evidence crosses recall documents")
        for s in recall:
            if any(e.document != recall_document for e in text_evidence(s.evidence)):
                raise ValueError("linked recall situation crosses recall documents")
        mention_alias = {m: f"m{i}" for i, m in enumerate(sorted(visible_mentions))}
        class_ids = {r for m in visible_mentions if (r := decisions[m].referent) is not None}
        class_ids.update(
            base_to_final[r] for c in visible_contexts if (r := holders[c].referent) is not None
        )
        referent_alias = {r: f"r{i}" for i, r in enumerate(sorted(class_ids))}

        def complete_identity(mention: str) -> bool:
            decision = decisions[mention]
            return (
                decision.referent is not None
                and set(referents[decision.referent].members) <= visible_mentions
            )

        mention_rows = [
            {
                "key": mention_alias[m],
                "side": documents[entities[m].origin_document].side,
                "evidence": excerpts(text_evidence(entities[m].evidence)),
                "identity": {
                    "outcome": decisions[m].outcome,
                    "referent": referent_alias.get(decisions[m].referent or ""),
                    "rule": decisions[m].rule,
                    "reason": decisions[m].reason,
                    "available": complete_identity(m),
                    "witnesses_withheld": decisions[m].referent is not None
                    and not complete_identity(m),
                },
            }
            for m in sorted(visible_mentions)
        ]
        context_rows = []
        for c in sorted(visible_contexts):
            frame, holder = contexts[c], holders[c]
            named = holder.referent is not None
            witnesses = (
                set(referents[base_to_final[holder.referent]].members)
                if holder.referent is not None
                else set()
            )
            context_rows.append(
                {
                    "key": context_alias[c],
                    "side": frame.side,
                    "kind": frame.kind,
                    "parent": context_alias.get(frame.parent or ""),
                    "background_evidence": excerpts(text_evidence(frame.evidence)),
                    "holder": {
                        "applicable": _has_holder(frame),
                        "status": holder.status,
                        "gap": holder.gap,
                        "referent": referent_alias.get(base_to_final[holder.referent])
                        if holder.referent is not None
                        else None,
                        "candidates": [mention_alias[m.mention] for m in holder.candidates],
                        "background_evidence": excerpts(holder.evidence),
                        "available": named and witnesses <= visible_mentions,
                        "witnesses_withheld": named and not witnesses <= visible_mentions,
                    },
                }
            )
        result[key] = {
            "recall_placement": "linked" if recall else "no-recorded-situation",
            "source_placement": "contained-situations"
            if source
            else "no-complete-scoped-situation",
            "contexts": context_rows,
            "mentions": mention_rows,
            "situations": [
                {
                    "key": f"s{i}",
                    "side": s.side,
                    "kind": "state" if isinstance(s, State) else "event",
                    "predicate": s.predicate,
                    **(
                        {"polarity": s.polarity, "modality": s.modality}
                        if isinstance(s, State)
                        else {}
                    ),
                    "context": context_alias[s.context],
                    "evidence": excerpts(text_evidence(s.evidence)),
                    "participants": [
                        {
                            "role": p.role,
                            "mention": mention_alias[p.entity],
                            "evidence": excerpts(text_evidence(p.evidence)),
                        }
                        for p in s.participants
                    ],
                }
                for i, s in enumerate(selected)
            ],
            "identity_scope": (
                "document-local; aliases do not establish cross-document correspondence"
            ),
            "background_is_grade_evidence": False,
        }
    return result


def check_context_accounting(
    accounting: ContextAccounting, view: dict[str, Any], status: str, grade: str | None
) -> None:
    """Check dependency declarations without certifying the assessor's relevance judgment."""
    contexts = {c["key"]: c for c in view["contexts"]}
    mentions = {m["key"]: m for m in view["mentions"]}
    if set(accounting.considered_contexts) != set(contexts):
        raise ValueError("context accounting must consider every supplied context exactly once")
    if any(not contexts[c]["holder"]["applicable"] for c in accounting.required_holders):
        raise ValueError("required holder alias must name a holder-bearing context")
    if not set(accounting.required_mentions) <= set(mentions):
        raise ValueError("foreign context accounting mention")
    unresolved = any(not contexts[c]["holder"]["available"] for c in accounting.required_holders)
    unresolved |= any(
        not mentions[m]["identity"]["available"] for m in accounting.required_mentions
    )
    if unresolved and status == "completed" and grade != "unverifiable":
        raise ValueError("required context or identity dependency is unresolved or withheld")
