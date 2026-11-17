"""Native observation evidence for text-recall assessment, without invented transcripts."""

from typing import Any

from .model import Event, Hypothesis, Model, Name, State, Support, contained_in
from .playback import PlaybackSupport, union_playback
from .rich_account import RichAccount, SupportedAccount, validate_supported_account


class NativeCitation(Model):
    """A native citation identifies an admitted situation and exact playback support."""

    situation: Name
    support: PlaybackSupport


def require_native_source_account(value: SupportedAccount) -> RichAccount:
    """Qualify the entire account for native-source/text-recall consumers."""
    account = validate_supported_account(value)
    if not isinstance(account, RichAccount) or account.schema_version != "storymodel.account/v8":
        raise ValueError("native assessment requires explicit account v8 upgrade")
    if not account.media_sources or any(s.side != "source" for s in account.media_sources):
        raise ValueError("unsupported modality: native assessment requires source-only media")
    if any(not isinstance(u.support, Support) for u in account.units if u.kind == "recall"):
        raise ValueError("unsupported modality: native assessment requires text recall")
    return account


def native_source_view(account: RichAccount) -> dict[str, Any]:
    """Render admitted source observations with their actual epistemic contexts and provenance.

    These are supplied symbolic observations, not a claim of access to media bytes.
    No recall graph, neighboring recall or previous judgment enters the rendering.
    """
    checked = require_native_source_account(account)
    source_ids = {s.id for s in checked.media_sources} | {
        d.id for d in checked.documents if d.side == "source"
    }
    situations: tuple[Event | State, ...] = (*checked.events, *checked.states)
    situation_ids = {s.id for s in situations if s.side == "source"}
    return {
        "information_condition": "admitted-source-observations/text-recall/v1",
        "media_sources": [
            s.model_dump(mode="json") for s in sorted(checked.media_sources, key=lambda s: s.id)
        ],
        "contexts": [
            c.model_dump(mode="json")
            for c in sorted(checked.contexts, key=lambda c: c.id)
            if c.side == "source"
        ],
        "entities": [
            e.model_dump(mode="json")
            for e in sorted(checked.entities, key=lambda e: e.id)
            if e.origin_source in source_ids
        ],
        "situations": [
            {"kind": "event" if s in checked.events else "state", **s.model_dump(mode="json")}
            for s in sorted(situations, key=lambda s: s.id)
            if s.side == "source"
        ],
        "circumstances": [
            c.model_dump(mode="json")
            for c in sorted(checked.circumstances, key=lambda c: c.id)
            if c.situation in situation_ids
        ],
        "limits": (
            "Symbolic observations only. Missing observation is not absence. "
            "Context holders and cross-modal identity are not resolved by this profile. "
            "Reported/believed/intended content is not root-world fact."
        ),
    }


def native_citation_supports(
    account: RichAccount, hypothesis: Hypothesis, citations: tuple[NativeCitation, ...]
) -> tuple[PlaybackSupport, ...]:
    """Check exact source ownership, situation scope and hypothesis containment before admission."""
    all_situations: tuple[Event | State, ...] = (*account.events, *account.states)
    situations = {s.id: s for s in all_situations if s.side == "source"}
    targets = {u.id: u for u in account.units if u.kind != "recall"}
    if any(t not in targets for t in hypothesis.targets):
        raise ValueError("foreign source target")
    target_supports = tuple(targets[t].support for t in hypothesis.targets)
    grouped: dict[str, list[PlaybackSupport]] = {}
    for citation in citations:
        situation = situations.get(citation.situation)
        if situation is None:
            raise ValueError("native citation requires an admitted source situation")
        if not all(contained_in(e, target_supports) for e in situation.evidence):
            raise ValueError("native cited situation extends outside its source hypothesis")
        if not contained_in(citation.support, situation.evidence):
            raise ValueError("native citation lies outside its situation's exact support")
        if not contained_in(citation.support, target_supports):
            raise ValueError("native citation lies outside its source hypothesis")
        grouped.setdefault(citation.support.source, []).append(citation.support)
    return tuple(union_playback(values) for _, values in sorted(grouped.items()))
