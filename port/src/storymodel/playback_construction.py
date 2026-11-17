"""Admit supplied native observations into the existing graph without inventing source text."""

from hashlib import sha256
from typing import Any, Literal, Self

from pydantic import Field, model_validator

from .model import (
    AnchoredContext,
    AnyContext,
    Context,
    Digest,
    Entity,
    Event,
    ExtendedContext,
    Identified,
    Inventory,
    Model,
    Name,
    Participation,
    Recipe,
    State,
    Unit,
    _validate_instance,
)
from .playback import (
    MediaSource,
    PlaybackReadout,
    PlaybackSupport,
    TickInterval,
    contains_playback,
    project_playback,
    union_playback,
    validate_playback,
)
from .rich_account import RichAccount, validate_rich_account
from .serialization import canonical, digest, strict_json

RECIPE = "supplied-playback-construction/v1"
ContextKind = Literal[
    "reported",
    "believed",
    "intended",
    "desire",
    "memory",
    "imagination",
    "hypothetical",
    "counterfactual",
]


class PlaybackObservation(Model):
    """A supplied physical envelope retains who produced it and the exact observation inputs."""

    key: Name
    support: PlaybackSupport
    origin: Name
    procedure: Name
    input_sha256: tuple[Digest, ...] = Field(min_length=1)


class PlaybackRequest(Model):
    """Independent source or recall observations, with no compulsory text document."""

    schema_version: Literal["storymodel.playback-request/v1"] = "storymodel.playback-request/v1"
    source: MediaSource
    observations: tuple[PlaybackObservation, ...] = Field(min_length=1)
    export_permission: Literal["allowed", "denied"] = "denied"

    @model_validator(mode="after")
    def owned(self) -> Self:
        if len({o.key for o in self.observations}) != len(self.observations):
            raise ValueError("duplicate observation key")
        for observation in self.observations:
            validate_playback(self.source, observation.support)
        return self


def prepare_playback(
    source: MediaSource,
    observations: tuple[PlaybackObservation, ...],
    *,
    export_permission: Literal["allowed", "denied"] = "denied",
) -> PlaybackRequest:
    """Check supplied media support; annotation row starts do not infer observation envelopes."""
    return PlaybackRequest(
        source=source, observations=observations, export_permission=export_permission
    )


def validate_playback_request(value: PlaybackRequest | str | bytes) -> PlaybackRequest:
    if isinstance(value, PlaybackRequest):
        return _validate_instance(PlaybackRequest, value)
    return PlaybackRequest.model_validate_json(canonical(strict_json(value)))


class PlaybackContextProposal(Model):
    """Context cues and scopes are explicit playback observations, not character offsets."""

    key: Name
    kind: ContextKind
    parent: Name = "narrated"
    cue: PlaybackSupport
    scope: PlaybackSupport


class PlaybackEntityProposal(Model):
    """A local described entity retains native evidence rather than a fabricated text mention."""

    key: Name
    label: Name
    evidence: PlaybackSupport


class PlaybackRoleProposal(Model):
    """A role must cite support within the situation that asserts it."""

    entity: Name
    role: Literal["agent", "patient", "speaker", "recipient", "experiencer", "location"]
    evidence: PlaybackSupport


class PlaybackSituationProposal(Model):
    """Predicates are attributed semantic descriptions, not verbatim media text."""

    key: Name
    kind: Literal["event", "state"]
    context: Name = "narrated"
    predicate: Name
    evidence: PlaybackSupport
    participants: tuple[PlaybackRoleProposal, ...] = ()
    polarity: Literal["positive", "negative", "unknown"] = "unknown"
    modality: Literal[
        "asserted",
        "possible",
        "probable",
        "necessary",
        "intended",
        "desired",
        "counterfactual",
        "reported",
        "unknown",
    ] = "unknown"

    @model_validator(mode="after")
    def state_qualifiers(self) -> Self:
        if self.kind == "event" and (self.polarity != "unknown" or self.modality != "unknown"):
            raise ValueError("state qualifiers cannot be assigned to an event")
        return self


class PlaybackRowProposal(Model):
    """One atomic observation graph or an explicit processing outcome."""

    observation: Name
    status: Literal["completed", "unsupported", "not-attempted", "failed", "search-limited"]
    reason: Name | None = None
    contexts: tuple[PlaybackContextProposal, ...] = ()
    entities: tuple[PlaybackEntityProposal, ...] = ()
    situations: tuple[PlaybackSituationProposal, ...] = ()

    @model_validator(mode="after")
    def processing(self) -> Self:
        if self.status != "completed" and self.reason is None:
            raise ValueError("unfinished observation requires a reason")
        if self.status in ("unsupported", "not-attempted", "failed") and (
            self.contexts or self.entities or self.situations
        ):
            raise ValueError("unprocessed observation cannot carry graph claims")
        keys = [p.key for group in (self.contexts, self.entities, self.situations) for p in group]
        if "narrated" in keys or len(keys) != len(set(keys)):
            raise ValueError("duplicate or reserved local graph key")
        return self


class PlaybackProposals(Model):
    """Recorded semantic proposals bind all source and observation provenance in the request."""

    schema_version: Literal["storymodel.playback-proposals/v1"] = "storymodel.playback-proposals/v1"
    request_sha256: Digest
    observations: tuple[PlaybackRowProposal, ...]


class PlaybackResolution(Model):
    """Every supplied observation is accounted for, even when it yields no accepted graph."""

    observation: Name
    unit: Name
    status: Literal["admitted", "partial", "rejected", "unsupported", "not-attempted", "failed"]
    reason: str | None = None
    accepted: dict[str, str] = Field(default_factory=dict)


class PlaybackResult(Model):
    """A native graph and deterministic admission receipt, not audiovisual accuracy evidence."""

    schema_version: Literal["storymodel.playback-result/v1"] = "storymodel.playback-result/v1"
    recipe: Literal["supplied-playback-construction/v1"] = "supplied-playback-construction/v1"
    request_sha256: Digest
    response_sha256: Digest
    account: RichAccount
    resolutions: tuple[PlaybackResolution, ...]

    @model_validator(mode="after")
    def native_version(self) -> Self:
        if self.account.schema_version != "storymodel.account/v6":
            raise ValueError("native construction requires account v6")
        return self


def _overlap(left: PlaybackSupport, right: PlaybackSupport) -> bool:
    if left.source != right.source:
        return False
    # Canonical unions are sparse; never allocate a dense interval-pair matrix.
    i = j = 0
    from ._coordinates import tick

    while i < len(left.intervals) and j < len(right.intervals):
        a, b = left.intervals[i], right.intervals[j]
        if max(tick(a.start), tick(b.start)) < min(tick(a.end), tick(b.end)):
            return True
        if tick(a.end) <= tick(b.end):
            i += 1
        else:
            j += 1
    return any(
        contains_playback(PlaybackSupport(source=left.source, points=(p,)), right)
        for p in left.points
    ) or any(
        contains_playback(PlaybackSupport(source=right.source, points=(p,)), left)
        for p in right.points
    )


def _graph(
    request: PlaybackRequest,
    observation: PlaybackObservation,
    proposal: PlaybackRowProposal,
    root: Context,
) -> tuple[
    tuple[AnyContext, ...], tuple[Entity, ...], tuple[Event, ...], tuple[State, ...], dict[str, str]
]:
    def within(support: PlaybackSupport) -> None:
        validate_playback(request.source, support)
        if not contains_playback(support, observation.support):
            raise ValueError("claim support escapes the exact observation envelope")

    contexts: dict[str, AnyContext] = {"narrated": root}
    scopes = {c.key: c for c in proposal.contexts}
    pending = dict(scopes)
    ancestry: dict[str, tuple[str, ...]] = {"narrated": ()}
    while pending:
        ready = sorted(k for k, c in pending.items() if c.parent in contexts)
        if not ready:
            raise ValueError("cyclic or foreign context parent")
        for key in ready:
            context_proposal = pending.pop(key)
            within(context_proposal.cue)
            within(context_proposal.scope)
            if context_proposal.parent != "narrated":
                enclosing = scopes[context_proposal.parent].scope
                if not contains_playback(context_proposal.scope, enclosing) or contains_playback(
                    enclosing, context_proposal.scope
                ):
                    raise ValueError(
                        "nested playback scope must be strictly narrower than its parent"
                    )
            ancestry[key] = (*ancestry[context_proposal.parent], context_proposal.parent)
            if context_proposal.kind in ("reported", "believed", "intended"):
                contexts[key] = AnchoredContext(
                    side=request.source.side,
                    kind=context_proposal.kind,
                    parent=contexts[context_proposal.parent].id,
                    evidence=(context_proposal.cue,),
                )
            elif context_proposal.kind in (
                "desire",
                "memory",
                "imagination",
                "hypothetical",
                "counterfactual",
            ):
                contexts[key] = ExtendedContext(
                    side=request.source.side,
                    kind=context_proposal.kind,
                    parent=contexts[context_proposal.parent].id,
                    evidence=(context_proposal.cue,),
                )
    ordered = sorted(scopes)
    for index, left in enumerate(ordered):
        for right in ordered[index + 1 :]:
            if (
                left not in ancestry[right]
                and right not in ancestry[left]
                and _overlap(scopes[left].scope, scopes[right].scope)
            ):
                raise ValueError(
                    "overlapping playback scopes require an explicit parent relationship"
                )
    entities: dict[str, Entity] = {}
    for entity_proposal in proposal.entities:
        within(entity_proposal.evidence)
        entities[entity_proposal.key] = Entity(
            source_document=request.source.id,
            label=entity_proposal.label,
            evidence=(entity_proposal.evidence,),
        )
    events: list[Event] = []
    states: list[State] = []
    accepted = {k: v.id for k, v in contexts.items() if k != "narrated"}
    accepted.update({k: v.id for k, v in entities.items()})
    for situation_proposal in proposal.situations:
        within(situation_proposal.evidence)
        if situation_proposal.context not in contexts:
            raise ValueError("foreign situation context")
        applicable = [
            key
            for key, c in scopes.items()
            if contains_playback(situation_proposal.evidence, c.scope)
        ]
        deepest = max(applicable, key=lambda key: len(ancestry[key]), default="narrated")
        if situation_proposal.context != deepest:
            raise ValueError("situation must use its deepest declared playback context")
        for key, c in scopes.items():
            if _overlap(situation_proposal.evidence, c.scope) and key not in (
                *ancestry[situation_proposal.context],
                situation_proposal.context,
            ):
                raise ValueError("situation crosses a playback context boundary")
        participants = []
        for role in situation_proposal.participants:
            within(role.evidence)
            if role.entity not in entities:
                raise ValueError("foreign local participant")
            if not contains_playback(role.evidence, situation_proposal.evidence):
                raise ValueError("participant role escapes its situation support")
            participants.append(
                Participation(
                    entity=entities[role.entity].id, role=role.role, evidence=(role.evidence,)
                )
            )
        common: dict[str, Any] = dict(
            side=request.source.side,
            context=contexts[situation_proposal.context].id,
            predicate=situation_proposal.predicate,
            participants=tuple(participants),
            evidence=(situation_proposal.evidence,),
        )
        if situation_proposal.kind == "event":
            event = Event(**common)
            events.append(event)
            accepted[situation_proposal.key] = event.id
        else:
            state = State(
                **common, polarity=situation_proposal.polarity, modality=situation_proposal.modality
            )
            states.append(state)
            accepted[situation_proposal.key] = state.id
    return (
        tuple(c for k, c in contexts.items() if k != "narrated"),
        tuple(entities.values()),
        tuple(events),
        tuple(states),
        accepted,
    )


def _unique[T: Identified](values: tuple[T, ...]) -> tuple[T, ...]:
    return tuple({v.id: v for v in values}.values())


def compile_playback(value: PlaybackRequest, raw: str) -> PlaybackResult:
    """Resolve each observation atomically; valid neighbors survive a rejected observation."""
    request = validate_playback_request(value)
    request_sha = digest(request.model_dump(mode="json"))
    response_sha = sha256(raw.encode("utf-8")).hexdigest()
    supplied = strict_json(raw)
    if (
        not isinstance(supplied, dict)
        or set(supplied) != {"schema_version", "request_sha256", "observations"}
        or supplied["schema_version"] != "storymodel.playback-proposals/v1"
        or supplied["request_sha256"] != request_sha
        or not isinstance(supplied["observations"], list)
    ):
        raise ValueError("playback proposals have an invalid envelope or foreign request")
    keys = {o.key for o in request.observations}
    rows: dict[str, Any] = {}
    for row in supplied["observations"]:
        key = row.get("observation") if isinstance(row, dict) else None
        if not isinstance(key, str) or key not in keys or key in rows:
            raise ValueError("duplicate or foreign observation proposal")
        rows[key] = row
    root = Context(side=request.source.side, kind="narrated")
    contexts: tuple[AnyContext, ...] = (root,)
    entities: tuple[Entity, ...] = ()
    events: tuple[Event, ...] = ()
    states: tuple[State, ...] = ()
    units: tuple[Unit, ...] = _unique(
        tuple(
            Unit(kind="row" if request.source.side == "source" else "recall", support=o.support)
            for o in request.observations
        )
    )
    inventories = tuple(
        Inventory(unit=u.id, status="not-attempted", reason="detail extraction not run")
        for u in units
        if u.kind == "recall"
    )
    recipe = Recipe(name=RECIPE, input_sha256=(request.source.sha256, request_sha, response_sha))

    def account(
        cs: tuple[AnyContext, ...],
        es: tuple[Entity, ...],
        evs: tuple[Event, ...],
        ss: tuple[State, ...],
    ) -> RichAccount:
        return RichAccount(
            schema_version="storymodel.account/v6",
            documents=(),
            media_sources=(request.source,),
            units=units,
            contexts=cs,
            entities=es,
            events=evs,
            states=ss,
            inventories=inventories,
            recipe=recipe,
            export_permission=request.export_permission,
        )

    resolutions = []
    for observation in request.observations:
        unit = Unit(
            kind="row" if request.source.side == "source" else "recall", support=observation.support
        )
        if observation.key not in rows:
            resolutions.append(
                PlaybackResolution(
                    observation=observation.key,
                    unit=unit.id,
                    status="not-attempted",
                    reason="no proposal supplied",
                )
            )
            continue
        try:
            proposal = PlaybackRowProposal.model_validate_json(canonical(rows[observation.key]))
            cs, es, evs, ss, accepted = _graph(request, observation, proposal, root)
            candidate = account(
                _unique((*contexts, *cs)),
                _unique((*entities, *es)),
                _unique((*events, *evs)),
                _unique((*states, *ss)),
            )
        except ValueError as error:
            resolutions.append(
                PlaybackResolution(
                    observation=observation.key, unit=unit.id, status="rejected", reason=str(error)
                )
            )
            continue
        contexts, entities, events, states = (
            candidate.contexts,
            candidate.entities,
            candidate.events,
            candidate.states,
        )
        status: Literal["admitted", "partial", "unsupported", "not-attempted", "failed"] = (
            "admitted"
            if proposal.status == "completed"
            else "partial"
            if proposal.status == "search-limited"
            else proposal.status
        )
        resolutions.append(
            PlaybackResolution(
                observation=observation.key,
                unit=unit.id,
                status=status,
                reason=proposal.reason,
                accepted=accepted,
            )
        )
    return PlaybackResult(
        request_sha256=request_sha,
        response_sha256=response_sha,
        account=account(contexts, entities, events, states),
        resolutions=tuple(resolutions),
    )


def verify_playback(value: PlaybackRequest, raw: str, result: PlaybackResult) -> PlaybackResult:
    """Recompute from the full observation contract and exact reply bytes."""
    checked = _validate_instance(PlaybackResult, result)
    expected = compile_playback(value, raw)
    if checked != expected:
        raise ValueError("playback result differs from its recorded inputs")
    return expected


class SituationPlaybackReadout(Model):
    """Physical exposure remains bound to the actual contextualized situation and account."""

    account_sha256: Digest
    situation: Name
    kind: Literal["event", "state"]
    context: AnyContext
    projection: PlaybackReadout


def situation_playback(
    value: RichAccount, situation: str, source: str, bins: tuple[TickInterval, ...] = ()
) -> SituationPlaybackReadout:
    """Project an existing situation's native evidence on an explicitly selected source."""
    account = validate_rich_account(value)
    media = next((s for s in account.media_sources if s.id == source), None)
    target = next((s for s in account._situations() if s.id == situation), None)
    if media is None or target is None:
        raise ValueError("foreign playback source or situation")
    supports = [e for e in target.evidence if isinstance(e, PlaybackSupport) and e.source == source]
    if not supports:
        raise ValueError("situation has no playback evidence on selected source")
    return SituationPlaybackReadout(
        account_sha256=digest(account.model_dump(mode="json")),
        situation=target.id,
        kind="state" if isinstance(target, State) else "event",
        context=next(c for c in account.contexts if c.id == target.context),
        projection=project_playback(media, union_playback(supports), bins),
    )


def verify_situation_playback(
    value: RichAccount,
    situation: str,
    source: str,
    bins: tuple[TickInterval, ...],
    result: SituationPlaybackReadout,
) -> SituationPlaybackReadout:
    """Recompute the saved support measurement against its graph and explicit selected bins."""
    checked = _validate_instance(SituationPlaybackReadout, result)
    expected = situation_playback(value, situation, source, bins)
    if checked != expected:
        raise ValueError("situation playback readout differs from its graph or projection inputs")
    return expected
