"""Explicit rich account versions share graph checks while legacy wire records stay readable."""

from typing import Any, ClassVar, Literal, Self, overload

from pydantic import SerializerFunctionWrapHandler, model_serializer, model_validator

from .chronology import TemporalClaim, validate_temporal_claims
from .model import (
    Account,
    Document,
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
from .playback import MediaSource, PlaybackSupport, validate_playback
from .serialization import canonical, digest, strict_json


class Circumstance(Identified):
    """An evidenced time or manner phrase is not a measured time or an entity referent."""

    namespace: ClassVar[str] = "circumstance"
    situation: Name
    kind: Literal["time", "manner"]
    label: Name
    evidence: Evidence


class RichAccount(_AccountFields):
    """One graph: v5 adds contexts, v6 native evidence and v7 retained temporal claims."""

    schema_version: Literal[
        "storymodel.account/v4",
        "storymodel.account/v5",
        "storymodel.account/v6",
        "storymodel.account/v7",
    ] = "storymodel.account/v4"
    states: tuple[State, ...] = ()
    circumstances: tuple[Circumstance, ...] = ()
    media_sources: tuple[MediaSource, ...] = ()
    temporal_relations: tuple[TemporalClaim, ...] = ()

    @model_validator(mode="after")
    def historical_fields(self) -> Self:
        if self.schema_version not in ("storymodel.account/v6", "storymodel.account/v7") and (
            "media_sources" in self.model_fields_set or self.media_sources
        ):
            raise ValueError("media_sources field requires account v6")
        if self.schema_version != "storymodel.account/v7" and (
            "temporal_relations" in self.model_fields_set or self.temporal_relations
        ):
            raise ValueError("temporal_relations field requires account v7")
        return self

    @model_serializer(mode="wrap")
    def wire_fields(self, handler: SerializerFunctionWrapHandler) -> dict[str, Any]:
        data: dict[str, Any] = handler(self)
        if self.schema_version not in ("storymodel.account/v6", "storymodel.account/v7"):
            data.pop("media_sources", None)
        if self.schema_version != "storymodel.account/v7":
            data.pop("temporal_relations", None)
        return data

    def _media_sources(self) -> tuple[MediaSource, ...]:
        return self.media_sources

    def _situations(self) -> tuple[Event | State, ...]:
        return (*self.events, *self.states)

    @model_validator(mode="after")
    def circumstances_belong(self) -> Self:
        indexed(self.circumstances, "circumstance")
        situations = {s.id: s for s in self._situations()}
        docs = {d.id: d for d in self.documents}
        media = {s.id: s for s in self.media_sources}
        for circumstance in self.circumstances:
            situation = situations.get(circumstance.situation)
            if situation is None:
                raise ValueError("circumstance requires an existing situation")
            for support in circumstance.evidence:
                if isinstance(support, PlaybackSupport):
                    if self.schema_version not in (
                        "storymodel.account/v6",
                        "storymodel.account/v7",
                    ):
                        raise ValueError("playback evidence requires account v6")
                    source = media.get(support.source)
                    if source is None or source.side != situation.side:
                        raise ValueError("circumstance evidence has a foreign media source or side")
                    validate_playback(source, support)
                else:
                    doc = docs.get(support.document)
                    if doc is None or doc.side != situation.side:
                        raise ValueError("circumstance evidence has a foreign document or side")
                    for span in support.spans:
                        span.slice(doc.text)
                if not contained_in(support, situation.evidence):
                    raise ValueError("circumstance evidence lies outside its situation")
        return self

    @model_validator(mode="after")
    def temporal_relations_belong(self) -> Self:
        if self.schema_version == "storymodel.account/v7":
            validate_temporal_claims(self, self.temporal_relations)
        return self


def validate_rich_account(value: RichAccount | str | bytes) -> RichAccount:
    """Recheck copied objects, version vocabulary and graph relationships at public boundaries."""
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
    if parsed.get("schema_version") in (
        "storymodel.account/v4",
        "storymodel.account/v5",
        "storymodel.account/v6",
        "storymodel.account/v7",
    ):
        return validate_rich_account(raw)
    return validate_account(raw)


@overload
def require_text_account(value: RichAccount) -> RichAccount: ...


@overload
def require_text_account(value: Account) -> Account: ...


@overload
def require_text_account(value: SupportedAccount | str | bytes) -> SupportedAccount: ...


def require_text_account(value: SupportedAccount | str | bytes) -> SupportedAccount:
    """Refuse an unqualified media consumer explicitly instead of stripping native evidence."""
    account = validate_supported_account(value)
    if isinstance(account, RichAccount) and account.media_sources:
        raise ValueError("unsupported modality: this consumer requires a text-only account")
    return account


def account_schema(version: Literal["v4", "v5", "v6", "v7"]) -> dict[str, Any]:
    """Export a declared rich wire version, with historical schemas kept in the adapter."""
    from ._legacy import legacy_rich_schema, legacy_rich_v6_schema

    if version in ("v4", "v5"):
        return legacy_rich_schema(version)
    if version == "v6":
        return legacy_rich_v6_schema()
    if version != "v7":
        raise ValueError("unsupported rich account schema version")
    schema = RichAccount.model_json_schema()
    schema["properties"]["schema_version"] = {
        "const": "storymodel.account/v7",
        "default": "storymodel.account/v7",
        "title": "Schema Version",
        "type": "string",
    }
    return schema


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
    """Join independent snapshots, preserving collections and selecting the newest input version."""
    left, right = validate_rich_account(source), validate_rich_account(recall)
    for account, side in ((left, "source"), (right, "recall")):
        origins: tuple[Document | MediaSource, ...] = (*account.documents, *account.media_sources)
        if not origins or any(d.side != side for d in origins):
            raise ValueError(f"rich pairing requires a {side}-only account")
    a, b = left.model_dump(mode="json"), right.model_dump(mode="json")
    versions = (left.schema_version, right.schema_version)
    output: dict[str, Any] = {
        "schema_version": "storymodel.account/v7"
        if "storymodel.account/v7" in versions
        else "storymodel.account/v6"
        if "storymodel.account/v6" in versions
        else "storymodel.account/v5"
        if "storymodel.account/v5" in versions
        else "storymodel.account/v4"
    }
    # Every other graph field is a collection. New fields must be preserved or explicitly
    # handled here; an unknown scalar fails instead of silently disappearing during pairing.
    for name in RichAccount.model_fields:
        if name in ("schema_version", "recipe", "export_permission"):
            continue
        if name == "media_sources" and output["schema_version"] not in (
            "storymodel.account/v6",
            "storymodel.account/v7",
        ):
            continue
        if name == "temporal_relations" and output["schema_version"] != "storymodel.account/v7":
            continue
        av, bv = a.get(name, []), b.get(name, [])
        if not isinstance(av, list) or not isinstance(bv, list):
            raise ValueError(f"rich pairing requires an explicit rule for {name}")
        output[name] = av + bv
    all_sources: tuple[Document | MediaSource, ...] = (
        *left.documents,
        *right.documents,
        *left.media_sources,
        *right.media_sources,
    )
    output["recipe"] = Recipe(
        name="pair-rich-accounts/v1",
        input_sha256=(
            *(d.sha256 for d in all_sources),
            digest(a),
            digest(b),
        ),
    ).model_dump(mode="json")
    output["export_permission"] = (
        "allowed" if left.export_permission == right.export_permission == "allowed" else "denied"
    )
    return validate_rich_account(canonical(output))


def upgrade_context_account(value: RichAccount) -> RichAccount:
    """Explicitly move a v4 snapshot to v5 vocabulary without changing graph occurrences."""
    account = validate_rich_account(value)
    if account.schema_version != "storymodel.account/v4":
        raise ValueError("context upgrade requires a v4 account")
    return validate_rich_account(
        account.model_copy(
            update={
                "schema_version": "storymodel.account/v5",
                "recipe": Recipe(
                    name="upgrade-account-v4-to-v5/v1",
                    input_sha256=(
                        *(d.sha256 for d in account.documents),
                        digest(account.model_dump(mode="json")),
                    ),
                ),
            }
        )
    )


def upgrade_temporal_account(value: RichAccount) -> RichAccount:
    """Explicitly move any rich snapshot to v7 without changing its graph collections."""
    account = validate_rich_account(value)
    if account.schema_version == "storymodel.account/v7":
        return account
    original = account.model_dump(mode="json")
    return validate_rich_account(
        account.model_copy(
            update={
                "schema_version": "storymodel.account/v7",
                "recipe": Recipe(
                    name="upgrade-account-to-v7/v1",
                    input_sha256=(
                        *(d.sha256 for d in account.documents),
                        *(source.sha256 for source in account.media_sources),
                        digest(original),
                    ),
                ),
            }
        )
    )


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
