"""Explicit v4 accounts share graph checks while legacy wire records remain unchanged."""

from typing import Any, ClassVar, Literal, Self

from pydantic import model_validator

from .model import (
    Account,
    Event,
    Evidence,
    Identified,
    Model,
    Name,
    Participation,
    Recipe,
    State,
    _AccountFields,
    _validate_instance,
    contained_in,
    indexed,
    validate_account,
)
from .serialization import canonical, digest, strict_json


class Circumstance(Identified):
    """An evidenced time or manner phrase is not a measured time or an entity referent."""

    namespace: ClassVar[str] = "circumstance"
    situation: Name
    kind: Literal["time", "manner"]
    label: Name
    evidence: Evidence


class RichAccount(_AccountFields):
    """V4 adds states and circumstances without silently changing old snapshots or prompts."""

    schema_version: Literal["storymodel.account/v4"] = "storymodel.account/v4"
    states: tuple[State, ...] = ()
    circumstances: tuple[Circumstance, ...] = ()

    def _situations(self) -> tuple[Event | State, ...]:
        return (*self.events, *self.states)

    @model_validator(mode="after")
    def circumstances_belong(self) -> Self:
        indexed(self.circumstances, "circumstance")
        situations = {s.id: s for s in self._situations()}
        docs = {d.id: d for d in self.documents}
        for circumstance in self.circumstances:
            situation = situations.get(circumstance.situation)
            if situation is None:
                raise ValueError("circumstance requires an existing situation")
            for support in circumstance.evidence:
                doc = docs.get(support.document)
                if doc is None or doc.side != situation.side:
                    raise ValueError("circumstance evidence has a foreign document or side")
                for span in support.spans:
                    span.slice(doc.text)
                if not contained_in(support, situation.evidence):
                    raise ValueError("circumstance evidence lies outside its situation")
        return self


def validate_rich_account(value: RichAccount | str | bytes) -> RichAccount:
    """Recheck copied objects and complete graph relationships at every v4 consumer boundary."""
    if isinstance(value, RichAccount):
        return _validate_instance(RichAccount, value)
    return RichAccount.model_validate_json(canonical(strict_json(value)))


SupportedAccount = Account | RichAccount


def validate_supported_account(value: SupportedAccount | str | bytes) -> SupportedAccount:
    """Dispatch explicitly by wire version for consumers qualified to retain both graph shapes."""
    if isinstance(value, RichAccount):
        return validate_rich_account(value)
    if isinstance(value, Account):
        return validate_account(value)
    parsed = strict_json(value)
    if not isinstance(parsed, dict):
        raise ValueError("account must be a JSON object")
    raw = canonical(parsed)
    if parsed.get("schema_version") == "storymodel.account/v4":
        return validate_rich_account(raw)
    return validate_account(raw)


def upgrade_account(value: Account) -> RichAccount:
    """Explicitly upgrade a v3 graph, preserving every existing occurrence ID and collection."""
    legacy = validate_account(value)
    if legacy.schema_version != "storymodel.account/v3":
        raise ValueError("v4 upgrade currently requires an independent-account v3 snapshot")
    data = legacy.model_dump(mode="json")
    data["schema_version"] = "storymodel.account/v4"
    data["recipe"] = Recipe(
        name="upgrade-account-v3-to-v4/v1",
        input_sha256=(
            *(d.sha256 for d in legacy.documents),
            digest(legacy.model_dump(mode="json")),
        ),
    ).model_dump(mode="json")
    return validate_rich_account(canonical(data))


def pair_rich_accounts(source: RichAccount, recall: RichAccount) -> RichAccount:
    """Join independent v4 snapshots and retain every graph collection, including new families."""
    left, right = validate_rich_account(source), validate_rich_account(recall)
    for account, side in ((left, "source"), (right, "recall")):
        if not account.documents or any(d.side != side for d in account.documents):
            raise ValueError(f"rich pairing requires a {side}-only account")
    a, b = left.model_dump(mode="json"), right.model_dump(mode="json")
    output: dict[str, Any] = {"schema_version": "storymodel.account/v4"}
    # Every other graph field is a collection. New fields must be preserved or explicitly
    # handled here; an unknown scalar fails instead of silently disappearing during pairing.
    for name in RichAccount.model_fields:
        if name in ("schema_version", "recipe", "export_permission"):
            continue
        if not isinstance(a[name], list) or not isinstance(b[name], list):
            raise ValueError(f"rich pairing requires an explicit rule for {name}")
        output[name] = a[name] + b[name]
    output["recipe"] = Recipe(
        name="pair-rich-accounts/v1",
        input_sha256=(
            *(d.sha256 for d in (*left.documents, *right.documents)),
            digest(a),
            digest(b),
        ),
    ).model_dump(mode="json")
    output["export_permission"] = (
        "allowed" if left.export_permission == right.export_permission == "allowed" else "denied"
    )
    return validate_rich_account(canonical(output))


class ParticipantRow(Participation):
    """A readable role retains its exact evidence and entity occurrence ID."""

    label: Name


class SituationRow(Model):
    """A graph readout retains kind, participants, context and literal circumstances."""

    situation: Name
    kind: Literal["event", "state"]
    side: Literal["source", "recall"]
    context: Name
    predicate: Name
    polarity: str | None
    modality: str | None
    participants: tuple[ParticipantRow, ...]
    evidence: Evidence
    circumstances: tuple[Circumstance, ...]


def situation_table(value: RichAccount) -> tuple[SituationRow, ...]:
    """Consume all situations without treating state conditions as event-only search results."""
    account = validate_rich_account(value)
    entities = {e.id: e for e in account.entities}
    by_situation: dict[str, list[Circumstance]] = {}
    for circumstance in account.circumstances:
        by_situation.setdefault(circumstance.situation, []).append(circumstance)
    return tuple(
        SituationRow(
            situation=s.id,
            kind="state" if isinstance(s, State) else "event",
            side=s.side,
            context=s.context,
            predicate=s.predicate,
            polarity=s.polarity if isinstance(s, State) else None,
            modality=s.modality if isinstance(s, State) else None,
            participants=tuple(
                ParticipantRow(
                    entity=p.entity,
                    label=entities[p.entity].label,
                    role=p.role,
                    evidence=p.evidence,
                )
                for p in s.participants
            ),
            evidence=s.evidence,
            circumstances=tuple(by_situation.get(s.id, ())),
        )
        for s in account._situations()
    )
